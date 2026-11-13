/*
 * Czysta logika (bez przeglądarki): podział dźwięku na fragmenty, WAV, składanie tekstu.
 * Ten sam plik jest ładowany w aplikacji i w testach Node.
 */
(function (root) {
  'use strict';

  const SAMPLE_RATE = 16000;

  /**
   * Dzieli ciągły strumień PCM (Int16, 16 kHz, mono) na fragmenty:
   * pomija ciszę, tnie na pauzach w mowie, a przy mowie bez przerw tnie w najcichszym
   * miejscu ostatnich sekund. Fragmenty nie nachodzą na siebie i nie mają dziur.
   */
  class Chunker {
    constructor({ sampleRate = SAMPLE_RATE, minChunkMs = 10000, maxChunkMs = 25000, silenceMs = 500, longSilenceMs = 1500 } = {}) {
      this.sr = sampleRate;
      this.minChunkMs = minChunkMs;
      this.maxChunkMs = maxChunkMs;
      this.silenceMs = silenceMs;
      this.longSilenceMs = longSilenceMs;
      this.frameLen = sampleRate / 50; // 20 ms
      this.frame = new Int16Array(this.frameLen);
      this.frameFill = 0;
      this.framesSeen = 0;
      this.buf = new Int16Array(sampleRate * 12);
      this.size = 0;
      this.energies = [];
      this.preRoll = [];
      this.inChunk = false;
      this.chunkStartSample = 0;
      this.speechFrames = 0;
      this.trailingSilent = 0;
      this.noiseFloor = 60;
      this.totalSamples = 0;
    }

    get isIdle() { return !this.inChunk; }

    /** Przesuwa zegar (np. po odzyskaniu sesji), żeby czasy fragmentów były ciągłe. */
    setOffsetSamples(n) {
      this.totalSamples = n;
      this.framesSeen = Math.floor(n / this.frameLen);
    }

    feed(samples) {
      const out = [];
      let i = 0;
      const len = samples.length;
      while (i < len) {
        const n = Math.min(this.frameLen - this.frameFill, len - i);
        this.frame.set(samples.subarray(i, i + n), this.frameFill);
        this.frameFill += n;
        i += n;
        if (this.frameFill === this.frameLen) {
          this._onFrame(out);
          this.frameFill = 0;
          this.framesSeen++;
        }
      }
      this.totalSamples += len;
      return out;
    }

    flush() {
      this.frameFill = 0;
      this.preRoll = [];
      if (!this.inChunk || this.size === 0) { this._reset(); return null; }
      const out = [];
      this._emit(this.size, out);
      this._reset();
      // Po pauzie zegar ramek musi zgadzać się z liczbą próbek.
      this.framesSeen = Math.floor(this.totalSamples / this.frameLen);
      return out[0] || null;
    }

    /** Kopia trwającego fragmentu (do zapisu awaryjnego). */
    snapshot() {
      if (!this.inChunk || this.size === 0) return null;
      return { pcm: this.buf.slice(0, this.size), startMs: Math.floor(this.chunkStartSample * 1000 / this.sr) };
    }

    _reset() {
      this.inChunk = false;
      this.size = 0;
      this.energies = [];
      this.speechFrames = 0;
      this.trailingSilent = 0;
    }

    _onFrame(out) {
      const f = this.frame;
      let sum = 0;
      for (let k = 0; k < f.length; k++) sum += f[k] * f[k];
      const rms = Math.sqrt(sum / f.length);
      const threshold = Math.max(45, this.noiseFloor * 2);
      const speech = rms > threshold;
      this.noiseFloor = rms < this.noiseFloor ? rms : this.noiseFloor + (rms - this.noiseFloor) * 0.002;
      this.noiseFloor = Math.min(1500, Math.max(10, this.noiseFloor));

      if (!this.inChunk) {
        this.preRoll.push([f.slice(), rms]);
        if (this.preRoll.length > 15) this.preRoll.shift();
        if (speech) {
          this.inChunk = true;
          this.chunkStartSample = (this.framesSeen - (this.preRoll.length - 1)) * this.frameLen;
          for (const [pf, e] of this.preRoll) this._append(pf, e);
          this.preRoll = [];
          this.speechFrames = 1;
          this.trailingSilent = 0;
        }
        return;
      }
      this._append(f, rms);
      if (speech) { this.speechFrames++; this.trailingSilent = 0; } else { this.trailingSilent++; }

      const durMs = this.size * 1000 / this.sr;
      const trailMs = this.trailingSilent * 20;
      if (durMs >= this.minChunkMs && trailMs >= this.silenceMs) this._emit(this.size, out);
      else if (trailMs >= this.longSilenceMs) this._emit(this.size, out);
      else if (durMs >= this.maxChunkMs) this._emit(this._quietestCut(), out);
    }

    _append(f, e) {
      if (this.size + this.frameLen > this.buf.length) {
        const nb = new Int16Array(this.buf.length * 2);
        nb.set(this.buf.subarray(0, this.size));
        this.buf = nb;
      }
      this.buf.set(f, this.size);
      this.size += this.frameLen;
      this.energies.push(e);
    }

    _quietestCut() {
      const n = this.energies.length;
      const from = Math.max(n - 250, Math.floor(n / 2));
      const to = n - 10;
      let best = to;
      let bestE = Infinity;
      for (let i = from; i < to; i++) {
        if (this.energies[i] < bestE) { bestE = this.energies[i]; best = i; }
      }
      return (best + 1) * this.frameLen;
    }

    _emit(cut, out) {
      const whole = cut >= this.size;
      let pcmLen = cut;
      if (whole && this.trailingSilent > 10) pcmLen = this.size - (this.trailingSilent - 10) * this.frameLen;
      if (pcmLen > 0 && (!whole || this.speechFrames >= 8)) {
        out.push({
          pcm: this.buf.slice(0, pcmLen),
          startMs: Math.floor(this.chunkStartSample * 1000 / this.sr),
          endMs: Math.floor((this.chunkStartSample + pcmLen) * 1000 / this.sr),
        });
      }
      if (whole) {
        this._reset();
      } else {
        this.buf.copyWithin(0, cut, this.size);
        this.size -= cut;
        this.energies = this.energies.slice(cut / this.frameLen);
        this.chunkStartSample += cut;
        this.speechFrames = 8;
        this.trailingSilent = 0;
      }
    }
  }

  /**
   * Dzieli wczytany plik nagrania (Int16, 16 kHz, mono) na kolejne fragmenty do transkrypcji.
   * Inaczej niż Chunker przy nagrywaniu na żywo, nic nie jest pomijane: fragmenty przylegają do siebie
   * i obejmują całe nagranie. Cięcie wypada w najcichszym miejscu (pauzie 300 ms) między minChunkMs
   * a maxChunkMs. Pomijane są tylko fragmenty bez dźwięku (cisza), ale czas płynie dalej.
   */
  class FileSplitter {
    constructor({ sampleRate = SAMPLE_RATE, minChunkMs = 60000, maxChunkMs = 120000, startMs = 0 } = {}) {
      this.sr = sampleRate;
      this.frameLen = sampleRate / 50; // 20 ms
      this.minFrames = Math.floor(minChunkMs / 20);
      this.maxSamples = Math.floor(maxChunkMs / 20) * this.frameLen;
      this.buf = new Int16Array(this.maxSamples);
      this.size = 0;
      this.startSample = Math.floor(startMs * sampleRate / 1000);
    }

    /** Czas (ms) od początku nagrania do końca przyjętych próbek. */
    get positionMs() { return Math.floor((this.startSample + this.size) * 1000 / this.sr); }

    feed(samples) {
      const out = [];
      let i = 0;
      while (i < samples.length) {
        const n = Math.min(this.maxSamples - this.size, samples.length - i);
        this.buf.set(samples.subarray(i, i + n), this.size);
        this.size += n;
        i += n;
        if (this.size === this.maxSamples) this._cut(out);
      }
      return out;
    }

    flush() {
      const out = [];
      if (this.size > 0) this._emit(this.size, out);
      return out[0] || null;
    }

    _rms(from) {
      let sum = 0;
      for (let i = from; i < from + this.frameLen; i++) sum += this.buf[i] * this.buf[i];
      return Math.sqrt(sum / this.frameLen);
    }

    _cut(out) {
      const frames = Math.floor(this.size / this.frameLen);
      const rms = new Float32Array(frames);
      for (let f = 0; f < frames; f++) rms[f] = this._rms(f * this.frameLen);
      let best = frames;
      let bestSum = Infinity;
      for (let c = Math.max(this.minFrames, 1); c < frames; c++) {
        let sum = 0;
        for (let k = Math.max(0, c - 7); k <= Math.min(frames - 1, c + 7); k++) sum += rms[k];
        if (sum < bestSum) { bestSum = sum; best = c; }
      }
      this._emit(best * this.frameLen, out);
    }

    _emit(cut, out) {
      const startMs = Math.floor(this.startSample * 1000 / this.sr);
      const endMs = Math.floor((this.startSample + cut) * 1000 / this.sr);
      if (this._hasSound(cut)) out.push({ pcm: this.buf.slice(0, cut), startMs, endMs });
      this.buf.copyWithin(0, cut, this.size);
      this.size -= cut;
      this.startSample += cut;
    }

    _hasSound(len) {
      const frames = Math.floor(len / this.frameLen);
      if (!frames) return false;
      const rms = new Float32Array(frames);
      for (let f = 0; f < frames; f++) rms[f] = this._rms(f * this.frameLen);
      const floor = Float32Array.from(rms).sort()[Math.floor(frames / 10)];
      const threshold = Math.max(20, floor * 2);
      let loud = 0;
      for (const e of rms) if (e > threshold) loud++;
      return loud >= Math.min(25, Math.max(1, Math.floor(frames / 4)));
    }
  }

  /** Float32 (-1..1) -> Int16. */
  function toInt16(f32) {
    const out = new Int16Array(f32.length);
    for (let i = 0; i < f32.length; i++) {
      const v = Math.max(-1, Math.min(1, f32[i]));
      out[i] = v < 0 ? v * 32768 : v * 32767;
    }
    return out;
  }

  /** Przepróbkowanie Float32 (np. 48 kHz) -> Int16 16 kHz, z uśrednianiem (filtr antyaliasingowy). */
  class Resampler {
    constructor(fromRate, toRate = SAMPLE_RATE) {
      this.ratio = fromRate / toRate;
      this.pos = 0;
      this.acc = 0;
      this.cnt = 0;
    }
    process(input) {
      const out = new Int16Array(Math.ceil(input.length / this.ratio) + 2);
      let o = 0;
      for (let i = 0; i < input.length; i++) {
        this.acc += input[i];
        this.cnt++;
        this.pos += 1;
        if (this.pos >= this.ratio) {
          this.pos -= this.ratio;
          const v = Math.max(-1, Math.min(1, this.acc / this.cnt));
          out[o++] = v < 0 ? v * 32768 : v * 32767;
          this.acc = 0;
          this.cnt = 0;
        }
      }
      return out.subarray(0, o);
    }
  }

  function wavBytes(pcm, sampleRate = SAMPLE_RATE) {
    const buf = new ArrayBuffer(44 + pcm.length * 2);
    const v = new DataView(buf);
    const w = (o, s) => { for (let i = 0; i < s.length; i++) v.setUint8(o + i, s.charCodeAt(i)); };
    w(0, 'RIFF'); v.setUint32(4, 36 + pcm.length * 2, true); w(8, 'WAVE');
    w(12, 'fmt '); v.setUint32(16, 16, true); v.setUint16(20, 1, true); v.setUint16(22, 1, true);
    v.setUint32(24, sampleRate, true); v.setUint32(28, sampleRate * 2, true);
    v.setUint16(32, 2, true); v.setUint16(34, 16, true);
    w(36, 'data'); v.setUint32(40, pcm.length * 2, true);
    new Int16Array(buf, 44).set(pcm);
    return buf;
  }

  function formatTime(ms) {
    const t = Math.floor(ms / 1000);
    const h = Math.floor(t / 3600);
    const m = Math.floor((t % 3600) / 60);
    const s = t % 60;
    const p = (x) => String(x).padStart(2, '0');
    return h > 0 ? `${h}:${p(m)}:${p(s)}` : `${p(m)}:${p(s)}`;
  }

  function speakerLabel(raw) {
    const r = String(raw).trim();
    if (r.length <= 2 || r.toLowerCase().startsWith('speaker')) {
      return 'Rozmówca ' + r.replace(/^speaker[_ ]?/i, '').toUpperCase();
    }
    return r;
  }

  /** chunks: [{startMs, text, segs:[{s,e,p,t}]}] */
  function compose(chunks, timestamps, speakers) {
    const items = chunks.filter((c) => c.text && c.text.trim());
    if (!items.length) return '';
    const hasSpeakers = speakers && items.some((c) => (c.segs || []).some((s) => s.p));
    if (hasSpeakers) {
      const paras = [];
      const prefix = (ms, sp) => (timestamps ? `[${formatTime(ms)}] ` : '') + (sp ? speakerLabel(sp) + ': ' : '');
      for (const c of items) {
        const segs = (c.segs || []).filter((s) => s.t && s.t.trim());
        if (!segs.some((s) => s.p)) { paras.push(prefix(c.startMs, null) + c.text.trim()); continue; }
        let sp = null; let start = 0; let sb = '';
        for (const s of segs) {
          if (s.p !== sp && sb) { paras.push(prefix(c.startMs + start, sp) + sb.trim()); sb = ''; }
          if (!sb) { sp = s.p; start = s.s; }
          sb += s.t.trim() + ' ';
        }
        if (sb) paras.push(prefix(c.startMs + start, sp) + sb.trim());
      }
      return paras.join('\n\n');
    }
    if (timestamps) return items.map((c) => `[${formatTime(c.startMs)}] ${c.text.trim()}`).join('\n\n');
    const out = [];
    let para = '';
    for (const c of items) {
      para += (para ? ' ' : '') + c.text.trim();
      if (para.length >= 600 && /[.?!…]$/.test(para)) { out.push(para); para = ''; }
    }
    if (para) out.push(para);
    return out.join('\n\n');
  }

  /** Typowe „halucynacje” modeli Whisper na ciszy lub szumie. */
  const JUNK = ['amara.org', 'napisy stworzone przez', 'napisy wykonane przez', 'subtitles by', 'zeoranger',
    'napisy: ', 'transkrypcja: ', 'thanks for watching', 'dziękuję za oglądanie', 'dziękuję za uwagę i do zobaczenia'];

  function clean(text) {
    const t = String(text || '').trim().replace(/[ \t]+/g, ' ');
    if (t.length < 90) {
      const low = t.toLowerCase();
      if (JUNK.some((j) => low.includes(j))) return '';
    }
    return t;
  }

  /** Podpowiedź dla modelu: słownik nazw + koniec poprzedniego fragmentu (Whisper czyta ok. 220 tokenów). */
  function buildPrompt(glossary, previous) {
    const terms = [...new Set(String(glossary || '').split(/[,;\n]/).map((t) => t.trim()).filter(Boolean))];
    let out = '';
    if (terms.length) out += 'Słownik: ' + terms.join(', ').slice(0, 300) + '. ';
    const prev = String(previous || '').trim();
    if (prev) out += prev.slice(-(terms.length ? 160 : 300));
    return out.trim();
  }

  const api = { SAMPLE_RATE, Chunker, FileSplitter, toInt16, Resampler, wavBytes, formatTime, compose, clean, speakerLabel, buildPrompt };
  if (typeof module !== 'undefined' && module.exports) module.exports = api;
  else root.LTCore = api;
})(typeof self !== 'undefined' ? self : this);
