'use strict';
/**
 * LiveTranscribe – serwer dla Render.
 *
 *  - serwuje aplikację PWA (katalog public/),
 *  - /api/transcribe  – przekazuje fragment nagrania do Groq / OpenAI (klucz z zmiennej środowiskowej
 *                       albo klucz użytkownika z nagłówka; klucz nigdy nie trafia do kodu),
 *  - /api/pdf         – tworzy estetyczny PDF z poprawnymi polskimi znakami,
 *  - /apk             – przekierowanie do najnowszego pliku APK aplikacji na Androida.
 *
 * Serwer niczego nie zapisuje: nagrania i teksty przechowuje wyłącznie telefon użytkownika.
 */
const express = require('express');
const path = require('path');
const crypto = require('crypto');
const { buildPdf } = require('./pdf');
const { summarize, normalize } = require('./summary');
const { isHallucination } = require('./public/core.js');
const gemini = require('./gemini');

const PORT = process.env.PORT || 3000;
const APP_PASSWORD = process.env.APP_PASSWORD || '';
const APK_URL = process.env.APK_URL ||
  'https://github.com/DUKE404/livetranscribe/releases/latest/download/LiveTranscribe.apk';

const PROVIDERS = {
  groq: {
    url: process.env.GROQ_BASE_URL || 'https://api.groq.com/openai/v1',
    key: () => process.env.GROQ_API_KEY || '',
    models: { fast: 'whisper-large-v3-turbo', balanced: 'whisper-large-v3', accurate: 'whisper-large-v3' },
    // Groq wycofał Llama 3.3 70B (sierpień 2026) i zaleca gpt-oss-120b.
    chatModel: process.env.GROQ_CHAT_MODEL || 'openai/gpt-oss-120b',
  },
  // Google Gemini: darmowy klucz (aistudio.google.com), rozpoznaje rozmówców. Transkrypcja – gemini.js,
  // sprawdzanie klucza i notatka AI – przez zgodny z OpenAI adres Google.
  gemini: {
    url: process.env.GEMINI_BASE_URL || 'https://generativelanguage.googleapis.com/v1beta/openai',
    key: () => process.env.GEMINI_API_KEY || '',
    models: { fast: 'gemini', balanced: 'gemini', accurate: 'gemini' },
    chatModel: process.env.GEMINI_CHAT_MODEL || 'gemini-flash-latest',
  },
  openai: {
    url: 'https://api.openai.com/v1',
    key: () => process.env.OPENAI_API_KEY || '',
    models: { fast: 'gpt-4o-mini-transcribe', balanced: 'gpt-4o-transcribe', accurate: 'gpt-4o-transcribe' },
    chatModel: process.env.OPENAI_CHAT_MODEL || 'gpt-5-mini',
  },
};

const app = express();
app.disable('x-powered-by');
app.set('trust proxy', 1);

// Nagłówki bezpieczeństwa (mikrofon tylko dla tej strony).
app.use((req, res, next) => {
  res.setHeader('X-Content-Type-Options', 'nosniff');
  res.setHeader('Referrer-Policy', 'no-referrer');
  res.setHeader('X-Frame-Options', 'DENY');
  res.setHeader('Permissions-Policy', 'microphone=(self), camera=(), geolocation=()');
  if (req.secure || req.headers['x-forwarded-proto'] === 'https') {
    res.setHeader('Strict-Transport-Security', 'max-age=31536000');
  }
  next();
});

function safeEqual(a, b) {
  const x = Buffer.from(String(a));
  const y = Buffer.from(String(b));
  return x.length === y.length && crypto.timingSafeEqual(x, y);
}

/** Hasło dostępu (opcjonalne) – chroni klucz API serwera przed obcymi. */
function requirePassword(req, res, next) {
  if (!APP_PASSWORD) return next();
  if (safeEqual(req.get('x-app-password') || '', APP_PASSWORD)) return next();
  res.status(403).json({ error: 'Błędne hasło dostępu. Wpisz je w Ustawieniach.', code: 'password' });
}

// Prosty limit zapytań na adres IP (ochrona przed nadużyciem).
const hits = new Map();
function rateLimit(max, windowMs) {
  return (req, res, next) => {
    const now = Date.now();
    const key = req.ip + req.path;
    const h = hits.get(key) || { n: 0, t: now };
    if (now - h.t > windowMs) { h.n = 0; h.t = now; }
    h.n++;
    hits.set(key, h);
    if (h.n > max) return res.status(429).json({ error: 'Zbyt wiele zapytań. Spróbuj za chwilę.' });
    next();
  };
}
setInterval(() => {
  const now = Date.now();
  for (const [k, v] of hits) if (now - v.t > 120000) hits.delete(k);
}, 60000).unref();

app.get('/api/health', (req, res) => res.json({ ok: true }));

app.get('/api/config', (req, res) => {
  res.set('Cache-Control', 'no-store');
  res.json({
    passwordRequired: !!APP_PASSWORD,
    serverKeys: { groq: !!PROVIDERS.groq.key(), openai: !!PROVIDERS.openai.key(), gemini: !!PROVIDERS.gemini.key() },
    apkUrl: '/apk',
  });
});

app.get('/apk', (req, res) => res.redirect(302, APK_URL));

app.post('/api/check', requirePassword, rateLimit(20, 60000), express.json({ limit: '4kb' }), async (req, res) => {
  const p = PROVIDERS[req.query.provider] || PROVIDERS.groq;
  const key = (req.get('x-api-key') || '').trim() || p.key();
  if (!key) return res.status(400).json({ error: 'Brak klucza API.' });
  try {
    const r = await fetch(p.url + '/models', { headers: { Authorization: 'Bearer ' + key } });
    if (r.ok) return res.json({ ok: true });
    res.status(r.status === 401 || r.status === 403 ? 401 : 502).json({ error: r.status === 401 || r.status === 403 ? 'Klucz jest nieprawidłowy.' : 'Serwer usługi odpowiedział kodem ' + r.status });
  } catch (e) {
    res.status(502).json({ error: 'Brak połączenia z usługą transkrypcji.' });
  }
});

/**
 * Fragment nagrania (WAV) -> tekst.
 * Parametry w zapytaniu: provider, quality, language (puste = automatycznie), speakers=1, prompt.
 */
app.post(
  '/api/transcribe',
  requirePassword,
  rateLimit(40, 60000),
  express.raw({ type: ['audio/*', 'application/octet-stream'], limit: '25mb' }),
  async (req, res) => {
    const provider = PROVIDERS[req.query.provider] ? req.query.provider : 'groq';
    const p = PROVIDERS[provider];
    const key = (req.get('x-api-key') || '').trim() || p.key();
    if (!key) {
      return res.status(401).json({ error: 'Brak klucza API. Wpisz go w Ustawieniach.', code: 'nokey' });
    }
    if (!req.body || !req.body.length) return res.status(400).json({ error: 'Pusty plik nagrania.' });

    if (provider === 'gemini') {
      try {
        const out = await gemini.transcribe(key, req.body, {
          language: String(req.query.language || '').slice(0, 5),
          context: String(req.query.context || '').slice(-600),
          glossary: String(req.query.glossary || '').slice(0, 300),
        });
        return res.json(out);
      } catch (e) {
        const status = e.status || 502;
        return res.status(status).json({ error: e.message || 'Błąd usługi Gemini.', code: status === 401 ? 'auth' : undefined });
      }
    }

    const quality = ['fast', 'balanced', 'accurate'].includes(req.query.quality) ? req.query.quality : 'balanced';
    const diarize = provider === 'openai' && req.query.speakers === '1';
    const model = diarize ? 'gpt-4o-transcribe-diarize' : p.models[quality];
    const format = diarize ? 'diarized_json' : model.startsWith('whisper') ? 'verbose_json' : 'json';
    const language = String(req.query.language || '').slice(0, 5);
    const prompt = String(req.query.prompt || '').slice(0, 400);

    const form = new FormData();
    form.append('file', new Blob([req.body], { type: 'audio/wav' }), 'fragment.wav');
    form.append('model', model);
    form.append('response_format', format);
    if (language && language !== 'auto') form.append('language', language);
    if (prompt && !diarize) form.append('prompt', prompt);
    if (diarize) form.append('chunking_strategy', 'auto');
    if (model.startsWith('whisper')) form.append('temperature', '0');

    try {
      const ctrl = new AbortController();
      const timer = setTimeout(() => ctrl.abort(), 170000);
      const r = await fetch(p.url + '/audio/transcriptions', {
        method: 'POST',
        headers: { Authorization: 'Bearer ' + key },
        body: form,
        signal: ctrl.signal,
      });
      clearTimeout(timer);
      const body = await r.text();
      if (!r.ok) {
        let msg = '';
        try { msg = JSON.parse(body).error.message; } catch (_) { /* bez opisu */ }
        const status = r.status === 401 || r.status === 403 ? 401 : r.status === 429 ? 429 : r.status >= 500 ? 502 : 400;
        const error = status === 401 ? 'Usługa odrzuciła klucz API. Sprawdź go w Ustawieniach.'
          : status === 429 ? 'Przekroczono limit usługi transkrypcji – ponowię za chwilę.'
            : 'Błąd usługi transkrypcji (' + r.status + ')' + (msg ? ': ' + msg : '');
        return res.status(status).json({ error, code: status === 401 ? 'auth' : undefined });
      }
      let data;
      try { data = JSON.parse(body); } catch (_) { data = { text: body }; }
      const raw = Array.isArray(data.segments) ? data.segments.filter((s) => s && String(s.text || '').trim()) : [];
      // Odcinki z ciszy lub szumu, które Whisper „dopowiedział” (np. „Dziękuję za uwagę”), są pomijane.
      const alone = raw.filter((s) => /[\p{L}\p{N}]/u.test(s.text)).length <= 1;
      const kept = raw.filter((s) => !isHallucination(s, alone));
      const dropped = raw.length - kept.length;
      const segments = kept
        .map((s) => ({
          s: Math.round((s.start || 0) * 1000),
          e: Math.round((s.end || 0) * 1000),
          p: s.speaker != null ? String(s.speaker) : undefined,
          t: String(s.text).trim(),
        }));
      let text = String(data.text || '').trim();
      if (dropped || (!text && segments.length)) text = segments.map((s) => s.t).join(' ');
      res.json({ text, segments: diarize ? segments : [], model, dropped });
    } catch (e) {
      res.status(502).json({ error: 'Brak połączenia z usługą transkrypcji – ponowię.' });
    }
  },
);

/**
 * Notatka AI. Odpowiedź jest strumieniem linii JSON: {"progress": "..."} … {"note": {...}} albo {"error": "..."},
 * żeby telefon widział postęp przy długich spotkaniach.
 */
app.post('/api/summary', requirePassword, rateLimit(6, 60000), express.json({ limit: '4mb' }), async (req, res) => {
  const provider = PROVIDERS[req.query.provider] ? req.query.provider : 'groq';
  const p = PROVIDERS[provider];
  const key = (req.get('x-api-key') || '').trim() || p.key();
  if (!key) return res.status(401).json({ error: 'Brak klucza API. Wpisz go w Ustawieniach.', code: 'nokey' });
  const title = String((req.body && req.body.title) || 'Spotkanie').slice(0, 200);
  const text = String((req.body && req.body.text) || '');
  res.status(200);
  res.set({ 'Content-Type': 'application/x-ndjson; charset=utf-8', 'Cache-Control': 'no-store', 'X-Accel-Buffering': 'no' });
  const send = (o) => { try { res.write(JSON.stringify(o) + '\n'); } catch (_) { /* klient się rozłączył */ } };
  const ping = setInterval(() => send({ ping: 1 }), 15000);
  try {
    const note = await summarize({ url: p.url, key, model: p.chatModel }, title, text, (step) => send({ progress: step }));
    send({ note });
  } catch (e) {
    send({ error: e.message || 'Nie udało się przygotować notatki.', status: e.status || 500 });
  } finally {
    clearInterval(ping);
    res.end();
  }
});

app.post('/api/pdf', rateLimit(30, 60000), express.json({ limit: '8mb' }), async (req, res) => {
  try {
    const b = req.body || {};
    const pdf = await buildPdf({
      title: String(b.title || 'Transkrypcja').slice(0, 200),
      createdAt: Number(b.createdAt) || Date.now(),
      durationMs: Number(b.durationMs) || 0,
      text: String(b.text || ''),
      fontSize: Math.min(16, Math.max(8, Number(b.fontSize) || 11.5)),
      headerOnEachPage: b.headerOnEachPage !== false,
      timeZone: typeof b.timeZone === 'string' ? b.timeZone.slice(0, 64) : 'Europe/Warsaw',
      note: b.note && typeof b.note === 'object' ? normalize(b.note) : null,
    });
    res.set('Content-Type', 'application/pdf');
    res.set('Cache-Control', 'no-store');
    res.send(pdf);
  } catch (e) {
    console.error('PDF error', e);
    res.status(500).json({ error: 'Nie udało się utworzyć PDF.' });
  }
});

// Aplikacja PWA
const pub = path.join(__dirname, 'public');
app.use(express.static(pub, {
  setHeaders(res, file) {
    if (file.endsWith('sw.js') || file.endsWith('.html') || file.endsWith('.webmanifest')) {
      res.setHeader('Cache-Control', 'no-cache');
    } else {
      res.setHeader('Cache-Control', 'public, max-age=3600');
    }
    if (file.endsWith('.webmanifest')) res.setHeader('Content-Type', 'application/manifest+json');
  },
}));
app.use('/api', (req, res) => res.status(404).json({ error: 'Nie znaleziono' }));
app.use((req, res) => res.sendFile(path.join(pub, 'index.html')));

if (require.main === module) {
  app.listen(PORT, () => console.log('LiveTranscribe nasłuchuje na porcie ' + PORT));
}
module.exports = app;
