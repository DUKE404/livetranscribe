'use strict';
/**
 * Transkrypcja przez Google Gemini (darmowy klucz z aistudio.google.com).
 *
 * Gemini słucha całego fragmentu jak człowiek: zapisuje także krótkie wtrącenia i wypowiedzi nakładające się
 * na siebie, a przy okazji oznacza, kto mówi (A, B, C…). Koniec poprzedniego fragmentu (z oznaczeniami)
 * trafia do polecenia, żeby te same osoby zachowywały litery w kolejnych fragmentach.
 */

// Adres Google zgodny z OpenAI: przyjmuje dźwięk (input_audio) i klucz jako „Authorization: Bearer”.
const BASE_URL = process.env.GEMINI_BASE_URL || 'https://generativelanguage.googleapis.com/v1beta/openai';
// Najpierw Flash-Lite: w darmowym planie ma wielokrotnie większy dzienny limit (ok. 500 zapytań wobec ok. 20
// dla Flash, stan na jesień 2026). Każdy model ma osobny limit – po wyczerpaniu dziennego idziemy do następnego.
const MODELS = (process.env.GEMINI_MODELS || 'gemini-flash-lite-latest,gemini-2.5-flash-lite,gemini-flash-latest,gemini-2.5-flash')
  .split(',').map((s) => s.trim()).filter(Boolean);
const DAILY = /PerDay|per[ _-]?day|daily/i;

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
    'Odpowiedz wyłącznie w formacie JSON: {"segments":[{"speaker":"A","text":"…"}]}',
  ].filter(Boolean).join('\n');
}

const SCHEMA = {
  type: 'object',
  properties: {
    segments: {
      type: 'array',
      items: {
        type: 'object',
        properties: { speaker: { type: 'string' }, text: { type: 'string' } },
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
  const messages = [{
    role: 'user',
    content: [
      { type: 'text', text: instruction({ language, context, glossary }) },
      { type: 'input_audio', input_audio: { data: Buffer.from(audio).toString('base64'), format: 'wav' } },
    ],
  }];
  let last = null;
  let schema = true;
  for (let m = 0; m < MODELS.length; m++) {
    const model = MODELS[m];
    const body = { model, messages, temperature: 0 };
    if (schema) body.response_format = { type: 'json_schema', json_schema: { name: 'transkrypcja', schema: SCHEMA } };
    let r;
    try {
      r = await fetchImpl(`${BASE_URL}/chat/completions`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json', Authorization: 'Bearer ' + key },
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
      const raw = (((data.choices || [])[0] || {}).message || {}).content || '';
      const segs = parseSegments(raw).map((x) => ({ s: 0, e: 0, p: x.p, t: x.t }));
      return { text: segs.map((x) => x.t).join(' '), segments: segs, model };
    }
    let msg = '';
    try { const err = JSON.parse(text); msg = (Array.isArray(err) ? err[0] : err).error.message || ''; } catch (_) { /* */ }
    // Model niedostępny dla tego klucza / wycofany – spróbuj następnego z listy.
    if (r.status === 404 || (r.status === 400 && /model/i.test(msg) && /not (found|supported)/i.test(msg))) {
      last = msg;
      continue;
    }
    // Model nie przyjmuje wymuszonego formatu odpowiedzi – ten sam model jeszcze raz, JSON tylko z polecenia.
    if (r.status === 400 && schema && /response_format|schema|json/i.test(msg)) {
      schema = false;
      m--;
      continue;
    }
    if (r.status === 401 || r.status === 403 || (r.status === 400 && /api key/i.test(msg))) {
      throw new GeminiError(401, 'Google odrzucił klucz API. Sprawdź go w Ustawieniach.');
    }
    if (r.status === 429) {
      if (DAILY.test(text)) {
        // Dzienny limit tego modelu wyczerpany – następny model ma własny limit.
        if (m < MODELS.length - 1) { last = msg; continue; }
        throw new GeminiError(429, 'Wyczerpany dzienny limit darmowego planu Gemini. Nagranie czeka w telefonie i dokończy się po odnowieniu limitu (następnego dnia) – albo przełącz silnik na Groq w Ustawieniach.');
      }
      throw new GeminiError(429, 'Limit zapytań Gemini na minutę – ponowię za chwilę.');
    }
    if (r.status >= 500) throw new GeminiError(502, 'Usługa Gemini chwilowo nie odpowiada – ponowię.');
    throw new GeminiError(400, 'Błąd usługi Gemini (' + r.status + ')' + (msg ? ': ' + msg : ''));
  }
  throw new GeminiError(400, 'Żaden model Gemini nie jest dostępny dla tego klucza' + (last ? ': ' + last : '.'));
}

module.exports = { transcribe, parseSegments, instruction, GeminiError, MODELS };
