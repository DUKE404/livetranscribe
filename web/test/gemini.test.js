'use strict';
const test = require('node:test');
const assert = require('node:assert');
const { transcribe, parseSegments, instruction } = require('../gemini.js');

const answer = (obj) => JSON.stringify({ candidates: [{ content: { parts: [{ text: JSON.stringify(obj) }] } }] });
const resp = (status, body) => ({ ok: status >= 200 && status < 300, status, text: async () => body });

test('Gemini: rozmówcy i wtrącenie, kontekst w poleceniu, zapasowy model', async () => {
  const calls = [];
  const fetchImpl = async (url, opt) => {
    calls.push({ url, body: JSON.parse(opt.body), key: opt.headers['x-goog-api-key'] });
    if (calls.length === 1) return resp(404, JSON.stringify({ error: { message: 'models/gemini-flash-latest is not found' } }));
    return resp(200, answer({ segments: [
      { speaker: 'A', text: 'Proponuję piątek.' },
      { speaker: 'B', text: 'Nie, piątek odpada.' },
      { speaker: 'Rozmówca A', text: 'To czwartek?' },
    ] }));
  };
  const out = await transcribe('KLUCZ', Buffer.from('RIFF....WAVE'), { language: 'pl', context: 'A: Dzień dobry.', glossary: 'Kowalczyk', fetchImpl });
  assert.strictEqual(calls.length, 2, 'po 404 drugi model z listy');
  assert.match(calls[1].url, /gemini-2\.5-flash:generateContent$/);
  assert.strictEqual(calls[1].key, 'KLUCZ');
  const parts = calls[1].body.contents[0].parts;
  assert.match(parts[0].text, /A: Dzień dobry\./);
  assert.match(parts[0].text, /Kowalczyk/);
  assert.strictEqual(parts[1].inline_data.mime_type, 'audio/wav');
  assert.deepStrictEqual(out.segments.map((s) => s.p), ['A', 'B', 'A']);
  assert.strictEqual(out.text, 'Proponuję piątek. Nie, piątek odpada. To czwartek?');
});

test('Gemini: błędy klucza i limitu', async () => {
  await assert.rejects(transcribe('X', Buffer.from('a'), { fetchImpl: async () => resp(400, JSON.stringify({ error: { message: 'API key not valid. Please pass a valid API key.' } })) }), (e) => e.status === 401);
  await assert.rejects(transcribe('X', Buffer.from('a'), { fetchImpl: async () => resp(429, '{}') }), (e) => e.status === 429);
  await assert.rejects(transcribe('X', Buffer.from('a'), { fetchImpl: async () => resp(503, '{}') }), (e) => e.status === 502);
});

test('Gemini: odpowiedź w bloku kodu, pusta, śmieci', () => {
  assert.strictEqual(parseSegments('```json\n{"segments":[{"speaker":"a","text":"Tak."}]}\n```')[0].p, 'A');
  assert.strictEqual(parseSegments('{"segments":[]}').length, 0);
  assert.strictEqual(parseSegments('nie wiem').length, 0);
  assert.match(instruction({ language: 'pl' }), /polski/);
});
