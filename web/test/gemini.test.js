'use strict';
const test = require('node:test');
const assert = require('node:assert');
const { transcribe, parseSegments, instruction } = require('../gemini.js');

const answer = (obj) => JSON.stringify({ choices: [{ message: { role: 'assistant', content: JSON.stringify(obj) } }] });
const resp = (status, body) => ({ ok: status >= 200 && status < 300, status, text: async () => body });

test('Gemini: rozmówcy i wtrącenie, kontekst w poleceniu, zapasowy model', async () => {
  const calls = [];
  const fetchImpl = async (url, opt) => {
    calls.push({ url, body: JSON.parse(opt.body), key: opt.headers.Authorization });
    if (calls.length === 1) return resp(404, JSON.stringify({ error: { message: 'models/gemini-flash-latest is not found' } }));
    return resp(200, answer({ segments: [
      { speaker: 'A', text: 'Proponuję piątek.' },
      { speaker: 'B', text: 'Nie, piątek odpada.' },
      { speaker: 'Rozmówca A', text: 'To czwartek?' },
    ] }));
  };
  const out = await transcribe('KLUCZ', Buffer.from('RIFF....WAVE'), { language: 'pl', context: 'A: Dzień dobry.', glossary: 'Kowalczyk', fetchImpl });
  assert.strictEqual(calls.length, 2, 'po 404 drugi model z listy');
  assert.match(calls[1].url, /\/openai\/chat\/completions$/);
  assert.strictEqual(calls[1].body.model, 'gemini-2.5-flash');
  assert.strictEqual(calls[1].key, 'Bearer KLUCZ');
  const parts = calls[1].body.messages[0].content;
  assert.match(parts[0].text, /A: Dzień dobry\./);
  assert.match(parts[0].text, /Kowalczyk/);
  assert.strictEqual(parts[1].input_audio.format, 'wav');
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

test('Gemini: model bez wymuszonego formatu – ponowienie bez response_format', async () => {
  const bodies = [];
  const fetchImpl = async (url, opt) => {
    const b = JSON.parse(opt.body);
    bodies.push(b);
    if (b.response_format) return resp(400, JSON.stringify([{ error: { message: 'Invalid JSON payload: response_format json_schema is not supported' } }]));
    return resp(200, JSON.stringify({ choices: [{ message: { content: '```json\n{"segments":[{"speaker":"A","text":"Dzień dobry."}]}\n```' } }] }));
  };
  const out = await transcribe('K', Buffer.from('a'), { fetchImpl });
  assert.strictEqual(bodies.length, 2);
  assert.strictEqual(bodies[1].model, bodies[0].model, 'ten sam model');
  assert.strictEqual(bodies[1].response_format, undefined);
  assert.strictEqual(out.text, 'Dzień dobry.');
});
