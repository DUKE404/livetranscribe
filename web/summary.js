'use strict';
/**
 * Notatka ze spotkania (streszczenie, decyzje, zadania, otwarte kwestie).
 * Długie transkrypcje są dzielone na części, każda dostaje notatki, a potem powstaje
 * jedna notatka końcowa – tak samo jak w aplikacji na Androida.
 */

const PART_CHARS = 9000;

const MAP_SYSTEM = 'Jesteś asystentem, który sporządza notatki ze spotkań po polsku. Otrzymasz fragment transkrypcji ' +
  '(mogą w nim być błędy rozpoznawania mowy). Wypisz zwięźle w punktach: najważniejsze informacje, ' +
  'podjęte decyzje, zadania (kto, co, do kiedy – jeśli padło) i otwarte kwestie. ' +
  'Opieraj się wyłącznie na treści – niczego nie wymyślaj.';

const REDUCE_SYSTEM = 'Jesteś asystentem, który przygotowuje profesjonalne notatki ze spotkań po polsku. ' +
  'Na podstawie materiału przygotuj notatkę i zwróć WYŁĄCZNIE obiekt JSON o kluczach: ' +
  '"streszczenie" (3–6 zdań: cel spotkania, główne ustalenia), ' +
  '"decyzje" (lista krótkich zdań), ' +
  '"zadania" (lista obiektów {"zadanie", "osoba", "termin"}; puste pole "" gdy nie padło), ' +
  '"pytania_otwarte" (lista). Używaj wyłącznie faktów z materiału, nie wymyślaj osób ani terminów. ' +
  'Popraw oczywiste błędy rozpoznawania mowy. Puste listy zostaw jako [].';

class SummaryError extends Error {
  constructor(status, message) { super(message); this.status = status; }
}

function split(text, max = PART_CHARS) {
  if (text.length <= max) return [text];
  const out = [];
  let start = 0;
  while (start < text.length) {
    let end = Math.min(text.length, start + max);
    if (end < text.length) {
      const w = text.slice(start, end);
      const cut = Math.max(w.lastIndexOf('\n\n'), w.lastIndexOf('. '), w.lastIndexOf('? '), w.lastIndexOf('! '));
      if (cut > max / 2) end = start + cut + 1;
    }
    out.push(text.slice(start, end).trim());
    start = end;
  }
  return out.filter(Boolean);
}

function extractJson(raw) {
  const a = raw.indexOf('{');
  const b = raw.lastIndexOf('}');
  return a >= 0 && b > a ? raw.slice(a, b + 1) : null;
}

const clean = (v) => String(v == null ? '' : v).trim();
const notNull = (v) => v && v !== 'null' && v !== '-';

function normalize(obj) {
  const list = (k) => (Array.isArray(obj[k]) ? obj[k].map(clean).filter(notNull) : []);
  const tasks = (Array.isArray(obj.zadania) ? obj.zadania : []).map((t) => (typeof t === 'string'
    ? { zadanie: clean(t), osoba: '', termin: '' }
    : { zadanie: clean(t && t.zadanie), osoba: notNull(clean(t && t.osoba)) ? clean(t.osoba) : '', termin: notNull(clean(t && t.termin)) ? clean(t.termin) : '' }))
    .filter((t) => t.zadanie);
  return { streszczenie: clean(obj.streszczenie), decyzje: list('decyzje'), zadania: tasks, pytania_otwarte: list('pytania_otwarte') };
}

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

async function chat({ url, key, model }, system, user, json, maxTokens) {
  let useJson = json;
  for (let attempt = 1; ; attempt++) {
    const body = {
      model,
      messages: [{ role: 'system', content: system }, { role: 'user', content: user }],
      max_completion_tokens: maxTokens,
      reasoning_effort: 'low',
    };
    if (useJson) body.response_format = { type: 'json_object' };
    let r;
    try {
      r = await fetch(url + '/chat/completions', {
        method: 'POST',
        headers: { Authorization: 'Bearer ' + key, 'Content-Type': 'application/json' },
        body: JSON.stringify(body),
        signal: AbortSignal.timeout(120000),
      });
    } catch (e) {
      if (attempt >= 4) throw new SummaryError(502, 'Brak połączenia z usługą AI.');
      await sleep(3000 * attempt);
      continue;
    }
    const text = await r.text();
    if (r.ok) {
      let content = '';
      try { content = JSON.parse(text).choices[0].message.content || ''; } catch (_) { /* */ }
      if (content.trim()) return content;
      if (attempt >= 3) throw new SummaryError(502, 'Pusta odpowiedź modelu.');
      continue;
    }
    if (r.status === 401 || r.status === 403) throw new SummaryError(401, 'Usługa odrzuciła klucz API.');
    if (r.status === 429 || r.status >= 500) {
      // Limit darmowego planu (tokeny na minutę) – czekamy, ile każe serwer.
      if (attempt >= 8) throw new SummaryError(429, 'Przekroczono limit usługi AI. Spróbuj za kilka minut.');
      const ra = Number(r.headers.get('retry-after')) || 0;
      await sleep(Math.min(70000, Math.max(2000, ra ? ra * 1000 : 8000 * attempt)));
      continue;
    }
    if (r.status === 400 && useJson) { useJson = false; continue; }
    let msg = '';
    try { msg = JSON.parse(text).error.message; } catch (_) { /* */ }
    throw new SummaryError(400, 'Błąd usługi AI (' + r.status + ')' + (msg ? ': ' + msg : ''));
  }
}

/** onProgress(opis) – wywoływane przy każdej części. */
async function summarize(cfg, title, transcript, onProgress = () => {}) {
  const text = String(transcript || '').trim();
  if (text.length < 40) throw new SummaryError(400, 'Transkrypcja jest za krótka, żeby przygotować notatkę.');
  const parts = split(text);
  let material = text;
  if (parts.length > 1) {
    const notes = [];
    for (let i = 0; i < parts.length; i++) {
      onProgress(`Analizuję część ${i + 1} z ${parts.length}…`);
      notes.push((await chat(cfg, MAP_SYSTEM, `Część ${i + 1} z ${parts.length} transkrypcji spotkania „${title}”:\n\n${parts[i]}`, false, 1500)).trim());
    }
    material = notes.join('\n\n').slice(0, 60000);
  }
  onProgress('Przygotowuję notatkę…');
  const label = parts.length === 1 ? 'Transkrypcja spotkania' : 'Notatki z kolejnych części spotkania';
  const raw = await chat(cfg, REDUCE_SYSTEM, `Tytuł: ${title}\n\n${label}:\n\n${material}`, true, 3000);
  let obj;
  try { obj = JSON.parse(extractJson(raw)); } catch (_) { throw new SummaryError(502, 'Model zwrócił nieczytelną odpowiedź. Spróbuj ponownie.'); }
  const note = normalize(obj);
  if (!note.streszczenie && !note.decyzje.length && !note.zadania.length && !note.pytania_otwarte.length) {
    throw new SummaryError(502, 'Model nie przygotował notatki. Spróbuj ponownie.');
  }
  return note;
}

const taskLine = (t) => {
  const extra = [t.osoba, t.termin].filter(Boolean);
  return extra.length ? `${t.zadanie} — ${extra.join(', ')}` : t.zadanie;
};

module.exports = { summarize, split, extractJson, normalize, taskLine, SummaryError, PART_CHARS };
