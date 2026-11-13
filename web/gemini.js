'use strict';
/**
 * Transkrypcja przez Google Gemini (darmowy klucz z aistudio.google.com).
 *
 * Gemini słucha całego fragmentu jak człowiek: zapisuje także krótkie wtrącenia i wypowiedzi nakładające się
 * na siebie, a przy okazji oznacza, kto mówi (A, B, C…). Koniec poprzedniego fragmentu (z oznaczeniami)
 * trafia do polecenia, żeby te same osoby zachowywały litery w kolejnych fragmentach.
 */

const NATIVE_URL = process.env.GEMINI_NATIVE_URL || 'https://generativelanguage.googleapis.com/v1beta';
// Alias „latest” wskazuje bieżący model Flash; pozostałe to zapas, gdyby alias był niedostępny.
const MODELS = (process.env.GEMINI_MODELS || 'gemini-flash-latest,gemini-2.5-flash,gemini-2.0-flash').split(',').map((s) => s.trim()).filter(Boolean);

const LANGS = { pl: 'polski', en: 'angielski', de: 'niemiecki', uk: 'ukraiński', fr: 'francuski', es: 'hiszpański' };

class GeminiError extends Error {
  constructor(status, message) { super(message); this.status = status; }
}

function instruction({ language, context, glossary }) {
  const lang = LANGS[language];
  return [
    'Jesteś stenografem. Przepisz dosłownie wszystko, co mówią wszystkie osoby w tym nagraniu' +
      (lang ? ` (język: ${lang})` : '') + ' – także krótkie wtrącenia, potakiwania i wypowiedzi, które nakładają się na siebie.',
    'Nie streszczaj, nie poprawiaj stylu, nie tłumacz i nie dopisuj niczego, czego nie słychać. Postaw interpunkcję.',
    'Każdą wypowiedź przypisz do osoby: A, B, C… Nową literę daj tylko wtedy, gdy mówi inny głos niż wcześniej.',
    context ? `Koniec poprzedniego fragmentu tej samej rozmowy – te same osoby zachowują swoje litery:\n"""${context}"""` : '',
    glossary ? `Nazwy, które mogą paść w rozmowie: ${glossary}.` : '',
    'Jeśli w nagraniu nie ma mowy (cisza, szum, muzyka), zwróć pustą listę wypowiedzi.',
  ].filter(Boolean).join('\n');
}

const SCHEMA = {
  type: 'OBJECT',
  properties: {
    segments: {
      type: 'ARRAY',
      items: {
        type: 'OBJECT',
        properties: { speaker: { type: 'STRING' }, text: { type: 'STRING' } },
        required: ['speaker', 'text'],
      },
    },
  },
  required: ['segments'],
};

/** Odpowiedź modelu -> [{ p, t }]. Toleruje JSON w bloku kodu i brak listy. */
function parseSegments(raw) {
  let s = String(raw || '').trim();
  const fence = s.match(/```(?:json)?\s*([\s\S]*?)```/);
  if (fence) s = fence[1].trim();
  let data;
  try {
    data = JSON.parse(s);
  } catch (_) {
    const i = s.indexOf('{');
    const j = s.lastIndexOf('}');
    if (i < 0 || j <= i) return [];
    try { data = JSON.parse(s.slice(i, j + 1)); } catch (__) { return []; }
  }
  const list = Array.isArray(data) ? data : Array.isArray(data && data.segments) ? data.segments : [];
  return list
    .map((x) => ({
      p: String((x && (x.speaker || x.p)) || '').trim().replace(/^(rozmówca|osoba|speaker)\s*/i, '').toUpperCase().slice(0, 3) || undefined,
      t: String((x && (x.text || x.t)) || '').trim(),
    }))
    .filter((x) => x.t);
}

/**
 * audio: Buffer z WAV. Zwraca { text, segments: [{ s, e, p, t }], model }.
 * Rzuca GeminiError z kodem jak /api/transcribe (401 – klucz, 429 – limit, 502 – usługa, 400 – inne).
 */
async function transcribe(key, audio, { language = 'pl', context = '', glossary = '', fetchImpl = fetch } = {}) {
  const body = {
    contents: [{
      role: 'user',
      parts: [
        { text: instruction({ language, context, glossary }) },
        { inline_data: { mime_type: 'audio/wav', data: Buffer.from(audio).toString('base64') } },
      ],
    }],
    generationConfig: { temperature: 0, responseMimeType: 'application/json', responseSchema: SCHEMA },
  };
  let last = null;
  for (const model of MODELS) {
    let r;
    try {
      r = await fetchImpl(`${NATIVE_URL}/models/${encodeURIComponent(model)}:generateContent`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json', 'x-goog-api-key': key },
        body: JSON.stringify(body),
        signal: AbortSignal.timeout(170000),
      });
    } catch (e) {
      throw new GeminiError(502, 'Brak połączenia z usługą transkrypcji – ponowię.');
    }
    const text = await r.text();
    if (r.ok) {
      let data = {};
      try { data = JSON.parse(text); } catch (_) { /* */ }
      const cand = (data.candidates || [])[0] || {};
      const raw = ((cand.content && cand.content.parts) || []).map((p) => p.text || '').join('');
      const segs = parseSegments(raw).map((x) => ({ s: 0, e: 0, p: x.p, t: x.t }));
      return { text: segs.map((x) => x.t).join(' '), segments: segs, model };
    }
    let msg = '';
    try { msg = JSON.parse(text).error.message || ''; } catch (_) { /* */ }
    // Model niedostępny dla tego klucza / wycofany – spróbuj następnego z listy.
    if (r.status === 404 || (r.status === 400 && /model/i.test(msg) && /not (found|supported)/i.test(msg))) {
      last = msg;
      continue;
    }
    if (r.status === 401 || r.status === 403 || (r.status === 400 && /api key/i.test(msg))) {
      throw new GeminiError(401, 'Google odrzucił klucz API. Sprawdź go w Ustawieniach.');
    }
    if (r.status === 429) throw new GeminiError(429, 'Przekroczono limit darmowego planu Gemini – ponowię za chwilę.');
    if (r.status >= 500) throw new GeminiError(502, 'Usługa Gemini chwilowo nie odpowiada – ponowię.');
    throw new GeminiError(400, 'Błąd usługi Gemini (' + r.status + ')' + (msg ? ': ' + msg : ''));
  }
  throw new GeminiError(400, 'Żaden model Gemini nie jest dostępny dla tego klucza' + (last ? ': ' + last : '.'));
}

module.exports = { transcribe, parseSegments, instruction, GeminiError, MODELS };
