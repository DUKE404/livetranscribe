'use strict';
const test = require('node:test');
const assert = require('node:assert');
const { Chunker, Resampler, wavBytes, compose, clean } = require('../public/core.js');

const SR = 16000;
let seed = 7;
const rnd = () => { seed = (seed * 1103515245 + 12345) & 0x7fffffff; return seed / 0x7fffffff; };

const speech = (ms) => Int16Array.from({ length: SR * ms / 1000 }, (_, i) => {
  const env = 0.55 + 0.45 * Math.sin(2 * Math.PI * 3 * i / SR);
  return Math.round(Math.sin(2 * Math.PI * 220 * i / SR) * 6000 * env);
});
const silence = (ms) => Int16Array.from({ length: SR * ms / 1000 }, () => Math.round(rnd() * 10 - 5));

function run(c, ...parts) {
  const out = [];
  for (const p of parts) for (let i = 0; i < p.length; i += 1600) out.push(...c.feed(p.subarray(i, Math.min(p.length, i + 1600))));
  const f = c.flush();
  if (f) out.push(f);
  return out;
}

test('cisza nie daje fragmentów', () => {
  assert.strictEqual(run(new Chunker({ minChunkMs: 2000, maxChunkMs: 6000 }), silence(8000)).length, 0);
});

test('cięcie na pauzach', () => {
  const ch = run(new Chunker({ minChunkMs: 2000, maxChunkMs: 10000 }), silence(1000), speech(3000), silence(1200), speech(3000), silence(800));
  assert.strictEqual(ch.length, 2);
  assert.ok(Math.abs(ch[0].startMs - 1000) <= 400, 'start ' + ch[0].startMs);
  assert.ok(Math.abs(ch[0].endMs - 4000) <= 400, 'end ' + ch[0].endMs);
  assert.ok(Math.abs(ch[1].startMs - 5200) <= 400, 'start2 ' + ch[1].startMs);
});

test('długa mowa bez dziur i nakładania', () => {
  const ch = run(new Chunker({ minChunkMs: 3000, maxChunkMs: 8000 }), speech(30000));
  assert.ok(ch.length >= 4);
  for (const c of ch) assert.ok(c.endMs - c.startMs <= 8100);
  for (let i = 1; i < ch.length; i++) assert.strictEqual(ch[i - 1].endMs, ch[i].startMs);
  const total = ch.reduce((a, c) => a + c.pcm.length, 0);
  assert.ok(Math.abs(total - SR * 30) <= SR / 25, 'samples ' + total);
});

test('czas biegnie dalej po pauzie', () => {
  const c = new Chunker({ minChunkMs: 2000, maxChunkMs: 6000 });
  const a = run(c, speech(2500));
  const b = run(c, speech(2500));
  assert.strictEqual(a.length, 1);
  assert.strictEqual(b.length, 1);
  assert.ok(b[0].startMs >= a[0].endMs - 20, `${b[0].startMs} vs ${a[0].endMs}`);
  assert.ok(Math.abs(b[0].startMs - 2500) <= 400);
});

test('przepróbkowanie 48 kHz -> 16 kHz', () => {
  const r = new Resampler(48000);
  const input = Float32Array.from({ length: 48000 }, (_, i) => Math.sin(2 * Math.PI * 200 * i / 48000) * 0.5);
  let n = 0;
  for (let i = 0; i < input.length; i += 4096) n += r.process(input.subarray(i, i + 4096)).length;
  assert.ok(Math.abs(n - 16000) <= 2, 'n=' + n);
});

test('nagłówek WAV', () => {
  const b = Buffer.from(wavBytes(new Int16Array(1600)));
  assert.strictEqual(b.toString('ascii', 0, 4), 'RIFF');
  assert.strictEqual(b.readUInt32LE(24), 16000);
  assert.strictEqual(b.length, 44 + 3200);
});

test('składanie tekstu', () => {
  const chunks = [{ startMs: 0, text: 'Dzień dobry wszystkim.' }, { startMs: 65000, text: 'Zaczynamy spotkanie.' }];
  assert.strictEqual(compose(chunks, false, false), 'Dzień dobry wszystkim. Zaczynamy spotkanie.');
  assert.strictEqual(compose(chunks, true, false), '[00:00] Dzień dobry wszystkim.\n\n[01:05] Zaczynamy spotkanie.');
  const d = [{ startMs: 3600000, text: 'Cześć. Hej. Co słychać?', segs: [
    { s: 0, e: 1000, p: 'A', t: 'Cześć.' }, { s: 1000, e: 2000, p: 'B', t: 'Hej.' }, { s: 2000, e: 3000, p: 'B', t: 'Co słychać?' }] }];
  assert.strictEqual(compose(d, true, true), '[1:00:00] Rozmówca A: Cześć.\n\n[1:00:01] Rozmówca B: Hej. Co słychać?');
});

test('usuwanie halucynacji', () => {
  assert.strictEqual(clean('Napisy stworzone przez społeczność Amara.org'), '');
  assert.strictEqual(clean('  To jest   zdanie. '), 'To jest zdanie.');
});

const { split, extractJson, normalize } = require('../summary.js');
const { buildPdf } = require('../pdf.js');

test('notatka: podział, JSON i PDF', async () => {
  const parts = split('Zdanie. '.repeat(5000));
  assert.ok(parts.length >= 4);
  assert.ok(parts.every((p) => p.length <= 9000));
  assert.strictEqual(parts.join(' ').replace(/\s+/g, ' ').length >= 'Zdanie. '.repeat(5000).trim().length - 10, true);
  const note = normalize(JSON.parse(extractJson('Oto: {"streszczenie":"Krótko.","decyzje":["A",null],"zadania":[{"zadanie":"Zrobić X","osoba":"Ola","termin":"null"},"Zadzwonić"],"pytania_otwarte":[]} koniec')));
  assert.deepStrictEqual(note.zadania, [{ zadanie: 'Zrobić X', osoba: 'Ola', termin: '' }, { zadanie: 'Zadzwonić', osoba: '', termin: '' }]);
  assert.deepStrictEqual(note.decyzje, ['A']);
  const pdf = await buildPdf({ title: 'Test', createdAt: 0, durationMs: 1000, text: 'Treść transkrypcji.', fontSize: 11.5, headerOnEachPage: true, timeZone: 'Europe/Warsaw', note });
  assert.strictEqual(pdf.slice(0, 4).toString(), '%PDF');
});
