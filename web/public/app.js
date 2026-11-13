/* LiveTranscribe – aplikacja w przeglądarce (PWA). */
(() => {
  'use strict';
  const { Chunker, FileSplitter, toInt16, Resampler, wavBytes, formatTime, compose, clean, buildPrompt } = window.LTCore;

  // ======================================================================
  // Ikony
  // ======================================================================
  const P = {
    mic: 'M12 14a3 3 0 0 0 3-3V5a3 3 0 0 0-6 0v6a3 3 0 0 0 3 3zm5.3-3c0 3-2.5 5.1-5.3 5.1S6.7 14 6.7 11H5c0 3.4 2.7 6.2 6 6.7V21h2v-3.3c3.3-.5 6-3.3 6-6.7h-1.7z',
    stop: 'M6 6h12v12H6z',
    pause: 'M6 19h4V5H6v14zm8-14v14h4V5h-4z',
    play: 'M8 5v14l11-7z',
    back: 'M20 11H7.8l5.6-5.6L12 4l-8 8 8 8 1.4-1.4L7.8 13H20v-2z',
    more: 'M12 8a2 2 0 1 0 0-4 2 2 0 0 0 0 4zm0 2a2 2 0 1 0 0 4 2 2 0 0 0 0-4zm0 6a2 2 0 1 0 0 4 2 2 0 0 0 0-4z',
    edit: 'M3 17.2V21h3.8l11-11-3.8-3.8-11 11zM20.7 7a1 1 0 0 0 0-1.4l-2.3-2.3a1 1 0 0 0-1.4 0l-1.8 1.8 3.8 3.8L20.7 7z',
    close: 'M19 6.4 17.6 5 12 10.6 6.4 5 5 6.4 10.6 12 5 17.6 6.4 19 12 13.4 17.6 19 19 17.6 13.4 12z',
    home: 'M10 20v-6h4v6h5v-8h3L12 3 2 12h3v8z',
    list: 'M3 13h2v-2H3v2zm0 4h2v-2H3v2zm0-8h2V7H3v2zm4 4h14v-2H7v2zm0 4h14v-2H7v2zM7 7v2h14V7H7z',
    gear: 'M19.1 12.9a7 7 0 0 0 0-1.8l2-1.6-2-3.4-2.4 1a7 7 0 0 0-1.6-.9L14.8 3h-4l-.4 2.6a7 7 0 0 0-1.6.9l-2.4-1-2 3.4 2 1.6a7 7 0 0 0 0 1.8l-2 1.6 2 3.4 2.4-1c.5.4 1 .7 1.6.9l.4 2.6h4l.4-2.6c.6-.2 1.1-.5 1.6-.9l2.4 1 2-3.4-2-1.6zM12.8 15.5a3.5 3.5 0 1 1 0-7 3.5 3.5 0 0 1 0 7z',
    download: 'M5 20h14v-2H5v2zM19 9h-4V3H9v6H5l7 7 7-7z',
    file: 'M14 2H6c-1.1 0-2 .9-2 2v16c0 1.1.89 2 1.99 2H18c1.1 0 2-.9 2-2V8l-6-6zm2 9h-3v3.75c0 1.24-1.01 2.25-2.25 2.25S8.5 15.99 8.5 14.75s1.01-2.25 2.25-2.25c.46 0 .89.14 1.25.38V9h4v2zm-3-2V3.5L18.5 9H13z',
  };
  const icon = (n) => `<svg viewBox="0 0 24 24" aria-hidden="true"><path d="${P[n]}"/></svg>`;

  // ======================================================================
  // Narzędzia
  // ======================================================================
  const $ = (sel, el = document) => el.querySelector(sel);
  const esc = (s) => String(s == null ? '' : s).replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
  const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
  const fmtDur = (ms) => formatTime(ms || 0);
  const fmtDate = (ts) => new Intl.DateTimeFormat('pl-PL', { day: '2-digit', month: '2-digit', year: 'numeric', hour: '2-digit', minute: '2-digit' }).format(new Date(ts));
  const fmtBytes = (b) => (b >= 1e9 ? (b / 1073741824).toFixed(1).replace('.', ',') + ' GB' : Math.round(b / 1048576) + ' MB');
  const statusLabel = (s) => ({ RECORDING: 'Nagrywanie', PAUSED: 'Pauza', PROCESSING: 'Przetwarzanie', IMPORTING: 'Wczytywanie' }[s] || 'Gotowa');
  // Nazwy plików bez polskich znaków – część przeglądarek i aplikacji źle je obsługuje.
  const safeName = (t) => (String(t || '').trim().replace(/[łŁ]/g, (c) => (c === 'ł' ? 'l' : 'L')).normalize('NFD').replace(/[\u0300-\u036f]/g, '').replace(/[\\/:*?"<>|\u0000-\u001f]/g, ' ').replace(/\s+/g, '_').replace(/^[_.]+|[_.]+$/g, '').slice(0, 80) || 'Transkrypcja');

  // iPhone/iPad (iPadOS przedstawia się jak Mac z ekranem dotykowym) i Safari – wszystkie przeglądarki na iOS to WebKit.
  const IS_IOS = /iphone|ipad|ipod/i.test(navigator.userAgent) || (navigator.platform === 'MacIntel' && navigator.maxTouchPoints > 1);
  const IS_SAFARI = IS_IOS || (/safari/i.test(navigator.userAgent) && !/chrome|chromium|crios|fxios|edg|android/i.test(navigator.userAgent));

  const bus = new EventTarget();
  const emit = (type, detail) => bus.dispatchEvent(new CustomEvent(type, { detail }));

  let toastTimer = 0;
  function toast(msg, ms = 3200) {
    const t = $('#toast');
    t.textContent = msg;
    t.classList.add('show');
    clearTimeout(toastTimer);
    toastTimer = setTimeout(() => t.classList.remove('show'), ms);
  }

  // ======================================================================
  // Ustawienia (pamięć telefonu)
  // ======================================================================
  const DEFAULTS = {
    language: 'pl', provider: 'groq', quality: 'balanced', autosave: true, timestamps: false, speakers: false,
    pdfFontSize: 'medium', pdfHeader: true, theme: 'system', keepAudio: false, keepAwake: IS_IOS,
    glossary: '', consentShown: false,
  };
  function loadSettings() {
    try { return { ...DEFAULTS, ...JSON.parse(localStorage.getItem('lt.settings') || '{}') }; } catch (_) { return { ...DEFAULTS }; }
  }
  let settings = loadSettings();
  function setSetting(k, v) {
    settings = { ...settings, [k]: v };
    try { localStorage.setItem('lt.settings', JSON.stringify(settings)); } catch (_) { /* brak miejsca */ }
    if (k === 'theme') applyTheme();
    emit('settings');
    queue.resume();
  }
  const secret = {
    get: (k) => { try { return localStorage.getItem('lt.secret.' + k) || ''; } catch (_) { return ''; } },
    set: (k, v) => { try { if (v) localStorage.setItem('lt.secret.' + k, v); else localStorage.removeItem('lt.secret.' + k); } catch (_) { /* */ } },
  };
  // Gemini dostaje dłuższe fragmenty: więcej kontekstu do rozpoznania osób i mniej zapytań (limit darmowego planu).
  const chunkLimits = () => (settings.provider === 'gemini'
    ? ({ fast: [15000, 30000], accurate: [45000, 90000] }[settings.quality] || [30000, 60000])
    : ({ fast: [6000, 15000], accurate: [20000, 45000] }[settings.quality] || [10000, 25000]));
  const providerHost = () => ({ openai: 'api.openai.com', gemini: 'generativelanguage.googleapis.com' }[settings.provider] || 'api.groq.com');
  const diarizationAvailable = () => settings.provider === 'openai' || settings.provider === 'gemini';
  const providerName = (p) => ({ openai: 'OpenAI', gemini: 'Google Gemini' }[p] || 'Groq');
  /** Koniec poprzedniego fragmentu z oznaczeniami osób – Gemini zachowuje dzięki temu te same litery. */
  const speakerContext = (c) => {
    if (!c) return '';
    const segs = (c.segs || []).filter((s) => s.t);
    const t = segs.some((s) => s.p) ? segs.map((s) => (s.p ? s.p + ': ' : '') + s.t).join('\n') : c.text || '';
    return t.slice(-500);
  };

  function applyTheme() {
    if (settings.theme === 'system') document.documentElement.removeAttribute('data-theme');
    else document.documentElement.setAttribute('data-theme', settings.theme);
    const dark = settings.theme === 'dark' || (settings.theme === 'system' && matchMedia('(prefers-color-scheme: dark)').matches);
    $('meta[name="theme-color"]').setAttribute('content', dark ? '#0F1115' : '#2456E6');
  }

  // Konfiguracja serwera (czy wymaga hasła, czy ma własny klucz)
  let serverConfig = { passwordRequired: false, serverKeys: { groq: false, openai: false, gemini: false }, loaded: false };
  async function loadServerConfig() {
    try {
      const r = await fetch('/api/config', { cache: 'no-store' });
      if (r.ok) serverConfig = { ...(await r.json()), loaded: true };
      try { localStorage.setItem('lt.serverConfig', JSON.stringify(serverConfig)); } catch (_) { /* */ }
    } catch (_) {
      try { serverConfig = { ...serverConfig, ...JSON.parse(localStorage.getItem('lt.serverConfig') || '{}') }; } catch (__) { /* */ }
    }
    emit('settings');
  }
  const hasKey = () => !!secret.get('key.' + settings.provider) || !!(serverConfig.serverKeys && serverConfig.serverKeys[settings.provider]);
  function authHeaders() {
    const h = {};
    const key = secret.get('key.' + settings.provider);
    if (key) h['x-api-key'] = key;
    const pw = secret.get('password');
    if (pw) h['x-app-password'] = pw;
    return h;
  }

  // ======================================================================
  // Baza danych w telefonie (IndexedDB)
  // ======================================================================
  const dbp = new Promise((resolve, reject) => {
    const r = indexedDB.open('livetranscribe', 1);
    r.onupgradeneeded = () => {
      const db = r.result;
      db.createObjectStore('sessions', { keyPath: 'id', autoIncrement: true });
      const c = db.createObjectStore('chunks', { keyPath: 'id', autoIncrement: true });
      c.createIndex('session', 'sessionId');
      c.createIndex('status', 'status');
      db.createObjectStore('kv');
    };
    r.onsuccess = () => resolve(r.result);
    r.onerror = () => reject(r.error);
  });
  const rq = (r) => new Promise((res, rej) => { r.onsuccess = () => res(r.result); r.onerror = () => rej(r.error); });
  async function os(name, mode = 'readonly') { return (await dbp).transaction(name, mode).objectStore(name); }

  const db = {
    async addSession(s) { return rq((await os('sessions', 'readwrite')).add(s)); },
    async session(id) { return rq((await os('sessions')).get(Number(id))); },
    async sessions() { return (await rq((await os('sessions')).getAll())).sort((a, b) => b.createdAt - a.createdAt); },
    async patchSession(id, patch) {
      const st = await os('sessions', 'readwrite');
      const s = await rq(st.get(Number(id)));
      if (!s) return null;
      Object.assign(s, patch);
      await rq(st.put(s));
      emit('data', { sessionId: s.id });
      return s;
    },
    async addChunk(c) { const id = await rq((await os('chunks', 'readwrite')).add(c)); emit('data', { sessionId: c.sessionId }); return id; },
    async putChunk(c) { await rq((await os('chunks', 'readwrite')).put(c)); emit('data', { sessionId: c.sessionId }); },
    async chunks(sid) { return (await rq((await os('chunks')).index('session').getAll(Number(sid)))).sort((a, b) => a.idx - b.idx); },
    async pending() {
      const list = await rq((await os('chunks')).index('status').getAll('PENDING'));
      return list.sort((a, b) => a.sessionId - b.sessionId || a.idx - b.idx);
    },
    async deleteSession(id) {
      id = Number(id);
      const tx = (await dbp).transaction(['sessions', 'chunks', 'kv'], 'readwrite');
      tx.objectStore('sessions').delete(id);
      tx.objectStore('kv').delete('pdf:' + id);
      const idx = tx.objectStore('chunks').index('session');
      await new Promise((res, rej) => {
        const cur = idx.openKeyCursor(IDBKeyRange.only(id));
        cur.onsuccess = () => { const c = cur.result; if (c) { tx.objectStore('chunks').delete(c.primaryKey); c.continue(); } else res(); };
        cur.onerror = () => rej(cur.error);
      });
      await new Promise((res) => { tx.oncomplete = res; tx.onerror = res; });
      emit('data', { sessionId: id });
    },
    async clearAll() {
      const tx = (await dbp).transaction(['sessions', 'chunks', 'kv'], 'readwrite');
      tx.objectStore('sessions').clear();
      tx.objectStore('chunks').clear();
      tx.objectStore('kv').clear();
      await new Promise((res) => { tx.oncomplete = res; tx.onerror = res; });
      emit('data', {});
    },
    async kvGet(k) { return rq((await os('kv')).get(k)); },
    async kvSet(k, v) { return rq((await os('kv', 'readwrite')).put(v, k)); },
    async kvDel(k) { return rq((await os('kv', 'readwrite')).delete(k)); },
  };

  function composeChunks(chunks, ts, sp) {
    return compose(chunks.filter((c) => c.status === 'DONE').map((c) => ({ startMs: c.startMs, text: c.text, segs: c.segs || [] })), ts, sp);
  }

  // ======================================================================
  // Kolejka transkrypcji: każdy fragment czeka w telefonie, aż się uda
  // ======================================================================
  const queue = {
    running: false,
    problem: null,
    wake: null,
    setProblem(p) { if (this.problem !== p) { this.problem = p; emit('queue'); } },
    resume() { this.setProblem(null); if (this.wake) this.wake(); this.kick(); },
    kick() { if (!this.running) this.loop(); },
    waitFor(ms) {
      return new Promise((res) => {
        const t = setTimeout(done, ms);
        function done() { clearTimeout(t); queue.wake = null; res(); }
        this.wake = done;
      });
    },
    async loop() {
      this.running = true;
      let backoff = 2000;
      try {
        for (;;) {
          const list = await db.pending();
          if (!list.length) break;
          const chunk = list[0];
          const session = await db.session(chunk.sessionId);
          if (!session || !chunk.audio) {
            await db.putChunk({ ...chunk, status: 'FAILED', error: 'Brak nagrania', audio: null });
            continue;
          }
          if (!navigator.onLine) {
            this.setProblem('Brak internetu – nagranie czeka w telefonie i dokończy się samo');
            await this.waitFor(30000);
            continue;
          }
          if (!hasKey()) {
            this.setProblem('Brak klucza API – dodaj go w Ustawieniach. Nagranie czeka w telefonie.');
            await this.waitFor(3600000);
            continue;
          }
          const chunks = await db.chunks(chunk.sessionId);
          const prev = chunks.filter((c) => c.idx < chunk.idx && c.status === 'DONE' && c.text).pop();
          const q = new URLSearchParams({
            provider: settings.provider,
            quality: settings.quality,
            language: settings.language === 'auto' ? '' : settings.language,
            speakers: settings.speakers && diarizationAvailable() ? '1' : '0',
            prompt: buildPrompt(settings.glossary, prev ? prev.text : ''),
          });
          if (settings.provider === 'gemini') {
            q.set('context', speakerContext(prev));
            q.set('glossary', String(settings.glossary || '').slice(0, 300));
          }
          let res;
          try {
            res = await fetch('/api/transcribe?' + q, { method: 'POST', headers: { 'Content-Type': 'audio/wav', ...authHeaders() }, body: chunk.audio });
          } catch (e) {
            this.setProblem('Brak połączenia z serwerem – ponawiam…');
            await this.waitFor(backoff);
            backoff = Math.min(backoff * 2, 30000);
            continue;
          }
          let data = {};
          try { data = await res.json(); } catch (_) { /* */ }
          if (res.ok) {
            const text = clean(data.text);
            await db.putChunk({
              ...chunk, status: 'DONE', text, segs: text ? data.segments || [] : [], error: null,
              audio: settings.keepAudio ? chunk.audio : null,
            });
            await afterChunk(chunk.sessionId, text);
            this.setProblem(null);
            backoff = 2000;
            continue;
          }
          if (res.status === 401 || res.status === 403) {
            this.setProblem((data.error || 'Odmowa dostępu') + ' Nagranie czeka w telefonie.');
            await this.waitFor(3600000);
            continue;
          }
          if (res.status === 429 || res.status >= 500) {
            this.setProblem(data.error || 'Serwer chwilowo niedostępny – ponawiam…');
            await this.waitFor(backoff);
            backoff = Math.min(backoff * 2, 30000);
            continue;
          }
          const attempts = (chunk.attempts || 0) + 1;
          await db.putChunk({ ...chunk, attempts, error: data.error || 'Błąd ' + res.status, status: attempts >= 3 ? 'FAILED' : 'PENDING' });
          if (attempts >= 3) await closeIfDone(chunk.sessionId);
          else await sleep(1500);
        }
      } catch (e) {
        console.error(e);
        this.setProblem('Błąd kolejki: ' + e.message);
      } finally {
        this.running = false;
      }
    },
  };

  async function afterChunk(sid, text) {
    const s = await db.session(sid);
    if (!s) return;
    // Tekst był już poprawiany ręcznie – dopisz nowy fragment na końcu, nie ruszaj poprawek.
    if (s.editedText != null && text) {
      await db.patchSession(sid, { editedText: s.editedText.trim() ? s.editedText.trimEnd() + '\n\n' + text : text });
      await db.kvDel('pdf:' + sid);
    }
    await closeIfDone(sid);
  }

  async function closeIfDone(sid) {
    const s = await db.session(sid);
    if (!s || s.status !== 'PROCESSING') return;
    const pend = (await db.chunks(sid)).some((c) => c.status === 'PENDING');
    if (!pend) await db.patchSession(sid, { status: 'DONE' });
  }

  window.addEventListener('online', () => queue.resume());

  // ======================================================================
  // Nagrywanie
  // ======================================================================
  const rec = {
    status: 'idle', // idle | starting | recording | paused | finishing
    sessionId: 0,
    lastSessionId: 0,
    error: null,
    elapsedMs: 0,
    level: 0,
    levels: [],
    chunker: null,
    stream: null,
    ctx: null,
    node: null,
    resampler: null,
    nextIdx: 0,
    tailTimer: 0,
    wakeLock: null,
    saving: Promise.resolve(),

    set(patch) { Object.assign(this, patch); emit('rec'); },

    async start() {
      if (this.status !== 'idle') return;
      this.set({ status: 'starting', error: null, wakeLockFailed: false });
      try {
        await this.openMic();
      } catch (e) {
        this.set({ status: 'idle', error: micError(e) });
        return;
      }
      try { if (navigator.storage && navigator.storage.persist) navigator.storage.persist(); } catch (_) { /* */ }
      const now = Date.now();
      const [minMs, maxMs] = chunkLimits();
      const id = await db.addSession({
        title: 'Transkrypcja ' + fmtDate(now), createdAt: now, durationMs: 0, status: 'RECORDING',
        provider: settings.provider, editedText: null,
      });
      this.chunker = new Chunker({ minChunkMs: minMs, maxChunkMs: maxMs });
      this.nextIdx = 0;
      this.set({ status: 'recording', sessionId: id, lastSessionId: id, elapsedMs: 0, levels: [] });
      this.startTail();
      this.applyWakeLock();
      emit('data', { sessionId: id });
    },

    async openMic() {
      if (!navigator.mediaDevices || !navigator.mediaDevices.getUserMedia) throw new Error('unsupported');
      const stream = await navigator.mediaDevices.getUserMedia({
        // Bez tłumienia szumów i echa: ta obróbka zniekształca mowę w hałasie (metro, ulica), a Whisper lepiej
        // radzi sobie z surowym dźwiękiem z szumem. Automatyczne wzmocnienie zostaje – wyrównuje głośność.
        audio: { channelCount: 1, echoCancellation: false, noiseSuppression: false, autoGainControl: true },
      });
      const AC = window.AudioContext || window.webkitAudioContext;
      let ctx;
      // Safari źle łączy mikrofon z kontekstem o innej częstotliwości niż sprzętowa – tam przepróbkowujemy sami.
      if (IS_SAFARI) ctx = new AC();
      else try { ctx = new AC({ sampleRate: 16000, latencyHint: 'playback' }); } catch (_) { ctx = new AC(); }
      const src = ctx.createMediaStreamSource(stream);
      const sink = ctx.createGain();
      sink.gain.value = 0;
      sink.connect(ctx.destination);
      this.resampler = new Resampler(ctx.sampleRate);
      let node;
      if (ctx.audioWorklet) {
        await ctx.audioWorklet.addModule('/worklet.js');
        node = new AudioWorkletNode(ctx, 'lt-capture');
        node.port.onmessage = (e) => this.onAudio(e.data);
      } else {
        node = ctx.createScriptProcessor(4096, 1, 1);
        node.onaudioprocess = (e) => this.onAudio(new Float32Array(e.inputBuffer.getChannelData(0)));
      }
      src.connect(node);
      node.connect(sink);
      // Safari po rozmowie telefonicznej lub Siri przechodzi w stan „interrupted”.
      ctx.onstatechange = () => { if ((ctx.state === 'suspended' || ctx.state === 'interrupted') && this.status === 'recording') ctx.resume().catch(() => {}); };
      if (ctx.state === 'suspended') await ctx.resume();
      stream.getAudioTracks().forEach((t) => {
        t.onended = () => { if (this.status === 'recording') this.micLost(); };
      });
      this.stream = stream;
      this.ctx = ctx;
      this.node = node;
    },

    closeMic() {
      try { if (this.node) { this.node.port && (this.node.port.onmessage = null); this.node.disconnect(); } } catch (_) { /* */ }
      try { if (this.stream) this.stream.getTracks().forEach((t) => t.stop()); } catch (_) { /* */ }
      try { if (this.ctx) this.ctx.close(); } catch (_) { /* */ }
      this.node = null; this.stream = null; this.ctx = null;
    },

    onAudio(f32) {
      if (this.status !== 'recording' || !this.chunker) return;
      const pcm = this.resampler.process(f32);
      if (!pcm.length) return;
      let sum = 0;
      for (let i = 0; i < pcm.length; i++) sum += pcm[i] * pcm[i];
      const rms = Math.sqrt(sum / pcm.length);
      const lvl = rms < 1 ? 0 : Math.max(0, Math.min(1, (20 * Math.log10(rms / 32768) + 60) / 60));
      this.levels.push(lvl);
      if (this.levels.length > 60) this.levels.shift();
      const chunks = this.chunker.feed(pcm);
      for (const c of chunks) this.saveChunk(c);
      this.elapsedMs = Math.floor(this.chunker.totalSamples / 16);
      emit('tick');
    },

    saveChunk(c) {
      const sid = this.sessionId;
      const idx = this.nextIdx++;
      const audio = new Blob([wavBytes(c.pcm)], { type: 'audio/wav' });
      this.saving = this.saving.then(async () => {
        await db.addChunk({ sessionId: sid, idx, startMs: c.startMs, endMs: c.endMs, status: 'PENDING', text: '', segs: [], attempts: 0, audio });
        await db.kvDel('tail');
        queue.kick();
      }).catch((e) => { console.error(e); toast('Błąd zapisu nagrania: ' + e.message); });
    },

    /** Co 4 s zapisuje trwający fragment – po nagłym zamknięciu strony nic nie ginie. */
    startTail() {
      clearInterval(this.tailTimer);
      this.tailTimer = setInterval(() => {
        if (this.status !== 'recording' || !this.chunker) return;
        const sid = this.sessionId;
        const snap = this.chunker.snapshot();
        const ms = this.elapsedMs;
        this.saving = this.saving.then(async () => {
          if (snap) await db.kvSet('tail', { sessionId: sid, pcm: snap.pcm, startMs: snap.startMs });
          await db.patchSession(sid, { durationMs: ms });
        }).catch(() => {});
      }, 4000);
    },

    async flush() {
      const c = this.chunker && this.chunker.flush();
      if (c) this.saveChunk(c);
      await this.saving;
      await db.kvDel('tail');
    },

    async pause() {
      if (this.status !== 'recording') return;
      this.set({ status: 'paused' });
      this.closeMic();
      await this.flush();
      await db.patchSession(this.sessionId, { status: 'PAUSED', durationMs: this.elapsedMs });
      this.releaseWakeLock();
    },

    async resume() {
      if (this.status !== 'paused') return;
      this.set({ error: null });
      try {
        await this.openMic();
      } catch (e) {
        this.set({ error: micError(e) });
        return;
      }
      await db.patchSession(this.sessionId, { status: 'RECORDING' });
      this.set({ status: 'recording' });
      this.applyWakeLock();
    },

    async micLost() {
      await this.pause();
      this.set({ error: 'Mikrofon został wyłączony przez system lub inną aplikację. Dotknij WZNÓW, aby kontynuować.' });
    },

    async finish({ discard = false } = {}) {
      if (this.status !== 'recording' && this.status !== 'paused') return null;
      const sid = this.sessionId;
      this.set({ status: 'finishing' });
      clearInterval(this.tailTimer);
      this.closeMic();
      await this.flush();
      this.releaseWakeLock();
      if (discard) {
        await db.deleteSession(sid);
        this.set({ status: 'idle', sessionId: 0, lastSessionId: 0, chunker: null });
        return null;
      }
      const pend = (await db.chunks(sid)).some((c) => c.status === 'PENDING');
      await db.patchSession(sid, { status: pend ? 'PROCESSING' : 'DONE', durationMs: this.elapsedMs });
      // Ostatni fragment mógł zostać przetworzony w trakcie kończenia – wtedy nikt inny nie zamknie dokumentu.
      await closeIfDone(sid);
      this.set({ status: 'idle', sessionId: 0, chunker: null });
      queue.kick();
      return sid;
    },

    async applyWakeLock() {
      if (!settings.keepAwake || this.status !== 'recording') return;
      let ok = false;
      if ('wakeLock' in navigator) {
        try { this.wakeLock = await navigator.wakeLock.request('screen'); ok = true; } catch (_) { /* */ }
      }
      if (!ok && document.visibilityState === 'visible') this.set({ wakeLockFailed: true });
    },
    releaseWakeLock() {
      try { if (this.wakeLock) this.wakeLock.release(); } catch (_) { /* */ }
      this.wakeLock = null;
    },
  };

  function micError(e) {
    const n = e && e.name;
    if (n === 'NotAllowedError' || n === 'SecurityError') {
      return 'Brak dostępu do mikrofonu. Dotknij ikony kłódki obok adresu strony → Uprawnienia → Mikrofon → Zezwól, i spróbuj ponownie.';
    }
    if (n === 'NotFoundError') return 'Nie znaleziono mikrofonu.';
    if (n === 'NotReadableError') return 'Mikrofon jest zajęty przez inną aplikację (np. rozmowę).';
    if (e && e.message === 'unsupported') return 'Ta przeglądarka nie obsługuje nagrywania. Użyj Chrome.';
    return 'Nie udało się włączyć mikrofonu: ' + ((e && e.message) || n || 'nieznany błąd');
  }

  // Ile nagrania przepadło, gdy aplikacja była w tle (iOS wyłącza wtedy mikrofon stronom internetowym).
  let hiddenAt = 0;
  let elapsedAtHide = 0;
  document.addEventListener('visibilitychange', () => {
    if (document.visibilityState === 'hidden') {
      hiddenAt = rec.status === 'recording' ? Date.now() : 0;
      elapsedAtHide = rec.elapsedMs;
      return;
    }
    if (hiddenAt && rec.status === 'recording') {
      const away = Date.now() - hiddenAt;
      const startElapsed = elapsedAtHide;
      hiddenAt = 0;
      setTimeout(() => {
        if (rec.status !== 'recording') return;
        const lost = away - (rec.elapsedMs - startElapsed);
        if (lost > 3000) {
          rec.set({ error: `Telefon wyłączył mikrofon na ok. ${Math.round(lost / 1000)} s, gdy aplikacja była w tle lub ekran był zablokowany – ta część rozmowy nie została nagrana. Nagrywanie trwa dalej.` + (IS_IOS ? ' Na iPhonie trzymaj aplikację otwartą przy włączonym ekranie.' : '') });
        }
      }, 1500);
    }
    if (document.visibilityState === 'visible') {
      if (rec.ctx && (rec.ctx.state === 'suspended' || rec.ctx.state === 'interrupted') && rec.status === 'recording') rec.ctx.resume().catch(() => {});
      if (rec.status === 'recording' && settings.keepAwake && !rec.wakeLock) rec.applyWakeLock();
      queue.kick();
    }
  });
  window.addEventListener('beforeunload', (e) => {
    if (rec.status === 'recording' || rec.status === 'paused' || importJobs.size) { e.preventDefault(); e.returnValue = ''; }
  });

  /** Po ponownym otwarciu: zamknij przerwane sesje i odzyskaj ostatni fragment. */
  async function recover() {
    const tail = await db.kvGet('tail');
    for (const s of await db.sessions()) {
      // Strona zamknięta w trakcie wczytywania pliku: zostaje to, co zdążyło się zapisać.
      if (s.status === 'IMPORTING') {
        const chunks = await db.chunks(s.id);
        await db.patchSession(s.id, {
          status: 'PROCESSING',
          durationMs: Math.max(s.durationMs || 0, chunks.reduce((m, c) => Math.max(m, c.endMs), 0)),
          importError: 'Wczytywanie pliku zostało przerwane (strona została zamknięta). Tekst może obejmować tylko część nagrania – wczytaj plik ponownie, aby przetworzyć całość.',
        });
        await closeIfDone(s.id);
      }
    }
    for (const s of await db.sessions()) {
      if (s.status !== 'RECORDING' && s.status !== 'PAUSED') continue;
      const chunks = await db.chunks(s.id);
      let dur = Math.max(s.durationMs || 0, chunks.reduce((m, c) => Math.max(m, c.endMs), 0));
      if (tail && tail.sessionId === s.id && tail.pcm && tail.pcm.length >= 8000) {
        const endMs = tail.startMs + Math.floor(tail.pcm.length / 16);
        await db.addChunk({
          sessionId: s.id, idx: chunks.length ? chunks[chunks.length - 1].idx + 1 : 0, startMs: tail.startMs, endMs,
          status: 'PENDING', text: '', segs: [], attempts: 0, audio: new Blob([wavBytes(tail.pcm)], { type: 'audio/wav' }),
        });
        dur = Math.max(dur, endMs);
      }
      await db.patchSession(s.id, { status: 'PROCESSING', durationMs: dur });
      await closeIfDone(s.id);
    }
    if (tail) await db.kvDel('tail');
    for (const s of await db.sessions()) if (s.status === 'PROCESSING') await closeIfDone(s.id);
  }

  // ======================================================================
  // Wczytywanie pliku nagrania (np. z dyktafonu)
  // ======================================================================
  const importJobs = new Map(); // id dokumentu -> { progress: 0..1 }
  const MAX_IMPORT_BYTES = 400 * 1024 * 1024;
  class SessionGone extends Error {}

  /** Okno „proszę czekać” bez przycisków. Zwraca funkcję, która je zamyka. */
  function busyDlg(text) {
    const d = document.createElement('dialog');
    d.innerHTML = `<div class="body"><div class="pending" style="margin:0"><span class="spin"></span>${esc(text)}</div></div>`;
    d.addEventListener('cancel', (e) => e.preventDefault());
    document.body.appendChild(d);
    d.showModal();
    return () => { try { d.close(); } catch (_) { /* */ } d.remove(); };
  }

  /** Czas trwania nagrania z nagłówka pliku (bez dekodowania) albo null, gdy przeglądarka go nie zna. */
  function mediaDuration(file) {
    return new Promise((resolve) => {
      let url = '';
      const a = document.createElement('audio');
      const done = (v) => { clearTimeout(t); a.removeAttribute('src'); try { a.load(); } catch (_) { /* */ } if (url) URL.revokeObjectURL(url); resolve(v); };
      const t = setTimeout(() => done(null), 6000);
      a.preload = 'metadata';
      a.onloadedmetadata = () => done(Number.isFinite(a.duration) && a.duration > 0 ? a.duration : null);
      a.onerror = () => done(null);
      try { url = URL.createObjectURL(file); a.src = url; } catch (_) { done(null); }
    });
  }

  /** Dekoduje plik – przeglądarka od razu zamienia go na 16 kHz (mniej pamięci). */
  async function decodeFile(arrayBuffer) {
    const Offline = window.OfflineAudioContext || window.webkitOfflineAudioContext;
    let ctx = null;
    try { ctx = new Offline(1, 1, 16000); } catch (_) { /* starsza przeglądarka */ }
    if (!ctx) ctx = new (window.AudioContext || window.webkitAudioContext)();
    try {
      return await new Promise((resolve, reject) => {
        const p = ctx.decodeAudioData(arrayBuffer, resolve, reject);
        if (p && p.then) p.then(resolve, reject);
      });
    } finally {
      try { if (typeof ctx.close === 'function') ctx.close(); } catch (_) { /* */ }
    }
  }

  /** Odczytuje plik, zakłada dokument i zaczyna dzielenie na fragmenty. Zwraca id dokumentu. */
  async function importFile(file) {
    if (file.size > MAX_IMPORT_BYTES) {
      throw new Error(`Plik jest za duży (${fmtBytes(file.size)}). W przeglądarce można wczytać do 400 MB – dłuższe nagrania wczytaj w aplikacji na Androida.`);
    }
    const seconds = await mediaDuration(file);
    const maxH = IS_IOS ? 2 : 3;
    if (seconds && seconds > maxH * 3600) {
      throw new Error(`Nagranie trwa ${formatTime(seconds * 1000)} – w przeglądarce${IS_IOS ? ' na iPhonie' : ''} można wczytać naraz do ${maxH} godzin (więcej nie zmieści się w pamięci). Podziel je na części i wczytaj osobno${IS_IOS ? '' : ' albo użyj aplikacji na Androida'}.`);
    }
    let audio;
    const close = busyDlg('Odczytuję plik nagrania… Przy długich nagraniach może to potrwać do minuty.');
    try {
      audio = await decodeFile(await (file.arrayBuffer ? file.arrayBuffer() : new Response(file).arrayBuffer()));
    } catch (e) {
      throw new Error('Nie udało się odczytać pliku. To nie jest nagranie, plik jest uszkodzony albo ta przeglądarka nie obsługuje jego formatu (spróbuj MP3, M4A lub WAV). Bardzo długie nagrania mogą nie zmieścić się w pamięci – wtedy użyj aplikacji na Androida.');
    } finally {
      close();
    }
    if (!audio.length) throw new Error('Plik nie zawiera dźwięku.');
    try { if (navigator.storage && navigator.storage.persist) navigator.storage.persist(); } catch (_) { /* */ }
    const now = Date.now();
    const title = file.name.replace(/\.[^.]+$/, '').replace(/_/g, ' ').trim().slice(0, 80) || 'Nagranie ' + fmtDate(now);
    const id = await db.addSession({
      title, createdAt: now, durationMs: Math.round(audio.duration * 1000), status: 'IMPORTING', provider: settings.provider, editedText: null,
    });
    importJobs.set(id, { progress: 0 });
    emit('data', { sessionId: id });
    runImport(id, audio);
    return id;
  }

  /** Dzieli zdekodowany dźwięk na fragmenty w pauzach i oddaje je do kolejki transkrypcji. */
  async function runImport(id, audio) {
    let error = null;
    let lastEmit = 0;
    try {
      const sr = audio.sampleRate;
      const chans = [];
      for (let c = 0; c < audio.numberOfChannels; c++) chans.push(audio.getChannelData(c));
      const resampler = sr === 16000 ? null : new Resampler(sr);
      const splitter = new FileSplitter();
      const block = sr * 5;
      const mono = new Float32Array(block);
      let idx = 0;
      const save = async (c) => {
        if (!(await db.session(id))) throw new SessionGone();
        await db.addChunk({
          sessionId: id, idx: idx++, startMs: c.startMs, endMs: c.endMs, status: 'PENDING', text: '', segs: [], attempts: 0,
          audio: new Blob([wavBytes(c.pcm)], { type: 'audio/wav' }),
        });
        queue.kick();
      };
      for (let off = 0; off < audio.length; off += block) {
        const n = Math.min(block, audio.length - off);
        for (let i = 0; i < n; i++) {
          let v = 0;
          for (const ch of chans) v += ch[off + i];
          mono[i] = v / chans.length;
        }
        const pcm = resampler ? resampler.process(mono.subarray(0, n)) : toInt16(mono.subarray(0, n));
        for (const c of splitter.feed(pcm)) await save(c);
        importJobs.set(id, { progress: (off + n) / audio.length });
        const now = Date.now();
        if (now - lastEmit > 400) { lastEmit = now; emit('import', { sessionId: id }); }
        if ((off / block) % 12 === 11) await sleep(0); // pozwól stronie odświeżyć widok
      }
      const last = splitter.flush();
      if (last) await save(last);
    } catch (e) {
      if (e instanceof SessionGone) { importJobs.delete(id); emit('import', { sessionId: id }); return; }
      console.error(e);
      error = 'Wczytywanie pliku przerwane: ' + (e.message || 'nieznany błąd') + '. Tekst może obejmować tylko część nagrania.';
    }
    importJobs.delete(id);
    if (!(await db.session(id))) return;
    await db.patchSession(id, { status: 'PROCESSING', importError: error });
    await closeIfDone(id);
    emit('import', { sessionId: id });
    queue.kick();
  }

  /** Wybrany plik: przypomnienie o RODO, sprawdzenie klucza, potem wczytanie. */
  async function startImport(file) {
    if (!settings.consentShown) {
      const ok = await dialog({
        title: 'Zanim wczytasz nagranie',
        body: `<p>Upewnij się, że osoby nagrane w pliku wiedzą o nagraniu i jego zamianie na tekst – tego wymagają przepisy (RODO) i dobre obyczaje.</p>
          <p>Fragmenty nagrania są wysyłane szyfrowanym połączeniem do ${esc(providerHost())} (serwery mogą być poza UE) i podlegają regulaminowi tej usługi. Nagrania i teksty przechowuje tylko Twój telefon.</p>`,
        buttons: [{ label: 'Anuluj', value: false }, { label: 'Rozumiem', value: true }],
      });
      if (!ok) return;
      setSetting('consentShown', true);
    }
    if (!hasKey()) {
      const v = await dialog({
        title: 'Brak klucza API',
        body: '<p>Do zamiany mowy na tekst potrzebny jest klucz API (np. darmowy z Groq). Możesz dodać go teraz albo wczytać plik mimo to – nagranie zostanie zachowane w telefonie, a tekst pojawi się po dodaniu klucza.</p>',
        buttons: [{ label: 'Wczytaj mimo to', value: 'go' }, { label: 'Ustawienia', value: 'set' }],
      });
      if (v === 'set') { go('#/settings'); return; }
      if (v !== 'go') return;
    }
    try {
      const id = await importFile(file);
      go('#/doc/' + id);
    } catch (e) {
      toast(e.message || 'Nie udało się wczytać pliku', 7000);
    }
  }

  // ======================================================================
  // PDF: generowanie, pobieranie, udostępnianie
  // ======================================================================
  async function generatePdf(sid, ts, sp, withNote = true) {
    const s = await db.session(sid);
    const text = s.editedText != null ? s.editedText : composeChunks(await db.chunks(sid), ts, sp);
    const fontSize = { small: 10, large: 13.5 }[settings.pdfFontSize] || 11.5;
    const r = await fetch('/api/pdf', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        title: s.title, createdAt: s.createdAt, durationMs: s.durationMs, text, fontSize,
        headerOnEachPage: settings.pdfHeader, timeZone: Intl.DateTimeFormat().resolvedOptions().timeZone,
        note: withNote && s.note ? s.note : null,
      }),
    });
    if (!r.ok) throw new Error(r.status >= 500 ? 'błąd serwera' : 'kod ' + r.status);
    const blob = await r.blob();
    const pdf = { blob, name: safeName(s.title) + '.pdf', at: Date.now() };
    await db.kvSet('pdf:' + sid, pdf);
    emit('data', { sessionId: sid });
    return pdf;
  }

  function downloadBlob(blob, name) {
    const url = URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = url;
    a.download = name;
    document.body.appendChild(a);
    a.click();
    a.remove();
    setTimeout(() => URL.revokeObjectURL(url), 60000);
  }

  async function sharePdf(pdf, title) {
    const file = new File([pdf.blob], pdf.name, { type: 'application/pdf' });
    if (navigator.canShare && navigator.canShare({ files: [file] })) {
      try {
        await navigator.share({ files: [file], title, text: title });
        return true;
      } catch (e) {
        if (e && e.name === 'AbortError') return true;
      }
    }
    downloadBlob(pdf.blob, pdf.name);
    toast('Ta przeglądarka nie pozwala udostępnić pliku bezpośrednio – PDF został pobrany. Wyślij go z folderu Pobrane.', 6000);
    return false;
  }

  function openPdf(pdf) {
    const url = URL.createObjectURL(pdf.blob);
    const w = window.open(url, '_blank');
    if (!w) {
      if (IS_IOS) saveFiles([new File([pdf.blob], pdf.name, { type: 'application/pdf' })]);
      else downloadBlob(pdf.blob, pdf.name);
    }
    setTimeout(() => URL.revokeObjectURL(url), 120000);
  }

  /**
   * Zapis plików w telefonie. Na iPhonie link do pobrania w aplikacji z ekranu początkowego potrafi otworzyć plik
   * bez możliwości powrotu – dlatego otwieramy arkusz udostępniania z opcją „Zachowaj w Plikach”.
   */
  async function saveFiles(files) {
    if (IS_IOS && navigator.canShare && navigator.canShare({ files })) {
      try {
        await navigator.share({ files });
        return 'share';
      } catch (e) {
        if (e && e.name === 'AbortError') return 'cancel';
      }
    }
    files.forEach((f, i) => setTimeout(() => downloadBlob(f, f.name), i * 400));
    return 'download';
  }

  // ======================================================================
  // Notatka AI ze spotkania
  // ======================================================================
  const noteJobs = new Map(); // id dokumentu -> opis etapu

  async function createNote(sid) {
    if (noteJobs.has(sid)) return;
    noteJobs.set(sid, 'Przygotowuję…');
    emit('note', { sessionId: sid });
    try {
      const s = await db.session(sid);
      const text = s.editedText != null ? s.editedText : composeChunks(await db.chunks(sid), false, true);
      const r = await fetch('/api/summary?provider=' + settings.provider, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json', ...authHeaders() },
        body: JSON.stringify({ title: s.title, text }),
      });
      if (!r.ok) { const d = await r.json().catch(() => ({})); throw new Error(d.error || 'Błąd serwera ' + r.status); }
      const reader = r.body.getReader();
      const dec = new TextDecoder();
      let buf = '';
      let note = null;
      for (;;) {
        const { value, done } = await reader.read();
        if (done) break;
        buf += dec.decode(value, { stream: true });
        let i;
        while ((i = buf.indexOf('\n')) >= 0) {
          const line = buf.slice(0, i).trim();
          buf = buf.slice(i + 1);
          if (!line) continue;
          const m = JSON.parse(line);
          if (m.progress) { noteJobs.set(sid, m.progress); emit('note', { sessionId: sid }); }
          if (m.error) throw new Error(m.error);
          if (m.note) note = m.note;
        }
      }
      if (!note) throw new Error('Połączenie zostało przerwane – spróbuj ponownie.');
      await db.patchSession(sid, { note });
      await db.kvDel('pdf:' + sid);
      toast('Notatka gotowa');
    } catch (e) {
      toast(navigator.onLine ? (e.message || 'Nie udało się przygotować notatki') : 'Do notatki AI potrzebny jest internet.', 6000);
    } finally {
      noteJobs.delete(sid);
      emit('note', { sessionId: sid });
    }
  }

  const taskLine = (t) => { const x = [t.osoba, t.termin].filter(Boolean); return x.length ? `${t.zadanie} — ${x.join(', ')}` : t.zadanie; };
  const noteText = (n) => [
    n.streszczenie ? 'Podsumowanie\n' + n.streszczenie : '',
    n.decyzje.length ? 'Decyzje\n' + n.decyzje.map((d) => '• ' + d).join('\n') : '',
    n.zadania.length ? 'Zadania\n' + n.zadania.map((t) => '• ' + taskLine(t)).join('\n') : '',
    n.pytania_otwarte.length ? 'Otwarte kwestie\n' + n.pytania_otwarte.map((d) => '• ' + d).join('\n') : '',
  ].filter(Boolean).join('\n\n');

  function noteCard(note, step) {
    const list = (title, items) => (items.length ? `<h4 style="margin:12px 0 4px">${title}</h4><ul style="margin:0;padding-left:20px">${items.map((i) => `<li>${esc(i)}</li>`).join('')}</ul>` : '');
    let body;
    if (step) body = `<div class="pending" style="margin:4px 0 0"><span class="spin"></span>${esc(step)}</div>`;
    else if (!note) {
      body = `<p class="small muted">Sztuczna inteligencja przygotuje podsumowanie, listę decyzji i zadań (kto, co, do kiedy). Notatka trafi też na początek PDF.</p>
        <button class="btn block" data-act="note" style="margin-top:10px">UTWÓRZ NOTATKĘ AI</button>`;
    } else {
      body = (note.streszczenie ? `<p>${esc(note.streszczenie)}</p>` : '') + list('Decyzje', note.decyzje) +
        list('Zadania', note.zadania.map(taskLine)) + list('Otwarte kwestie', note.pytania_otwarte) +
        `<p class="small muted" style="margin-top:10px">Przygotowane przez AI – sprawdź przed wysłaniem.</p>
        <div style="display:flex;flex-wrap:wrap"><button class="btn text" data-act="note-copy">Kopiuj</button><button class="btn text" data-act="note">Utwórz ponownie</button><button class="btn text danger-text" data-act="note-del">Usuń</button></div>`;
    }
    return `<div class="card" style="background:color-mix(in srgb, var(--primary-soft) 55%, var(--surface))"><h3 style="margin:0 0 6px">Notatka ze spotkania</h3>${body}</div>
      <div class="section" style="margin-top:6px">Transkrypcja</div>`;
  }

  // ======================================================================
  // Instalacja aplikacji
  // ======================================================================
  let installEvent = null;
  const isStandalone = () => matchMedia('(display-mode: standalone)').matches || navigator.standalone === true;
  const isIOS = () => IS_IOS;
  window.addEventListener('beforeinstallprompt', (e) => { e.preventDefault(); installEvent = e; emit('install'); });
  window.addEventListener('appinstalled', () => { installEvent = null; toast('Aplikacja zainstalowana – znajdziesz ją na ekranie głównym.'); emit('install'); });

  async function doInstall() {
    if (installEvent) {
      installEvent.prompt();
      try { await installEvent.userChoice; } catch (_) { /* */ }
      installEvent = null;
      emit('install');
      return;
    }
    await dialog({
      title: 'Zainstaluj aplikację',
      body: isIOS()
        ? '<ol class="steps"><li>Otwórz tę stronę w <b>Safari</b>.</li><li>Dotknij <b>Udostępnij</b> (kwadrat ze strzałką; w nowszym iOS najpierw <b>⋯</b> obok adresu).</li><li>Wybierz <b>Do ekranu początkowego</b> i dotknij <b>Dodaj</b>.</li></ol><p class="small">Uruchamiaj aplikację z ikony – wtedy iPhone nie usuwa jej danych.</p>'
        : '<ol class="steps"><li>Dotknij menu <b>⋮</b> w prawym górnym rogu Chrome.</li><li>Wybierz <b>Zainstaluj aplikację</b> albo <b>Dodaj do ekranu głównego</b>.</li><li>Potwierdź <b>Zainstaluj</b>.</li></ol><p class="small">Ikona LiveTranscribe pojawi się na ekranie telefonu.</p>',
      buttons: [{ label: 'OK', value: true }],
    });
  }

  function installCard() {
    if (isStandalone()) return '';
    return `<div class="card install">
      <div class="logo">${icon('download')}</div>
      <div class="txt"><b>Zainstaluj na telefonie</b><div class="small muted">Ikona na ekranie głównym, pełny ekran, działa też bez internetu.</div></div>
      <button class="btn tonal" data-act="install">Zainstaluj</button>
    </div>`;
  }

  // ======================================================================
  // Okna dialogowe
  // ======================================================================
  function dialog({ title, body = '', buttons = [], onOpen, className = '' }) {
    return new Promise((resolve) => {
      const d = document.createElement('dialog');
      if (className) d.className = className;
      d.innerHTML = (title ? `<h3>${esc(title)}</h3>` : '') + `<div class="body">${body}</div>` +
        (buttons.length ? `<div class="foot">${buttons.map((b, i) => `<button class="btn ${b.cls || 'text'}" data-i="${i}" ${b.disabled ? 'disabled' : ''}>${esc(b.label)}</button>`).join('')}</div>` : '');
      document.body.appendChild(d);
      let value = null;
      d.addEventListener('click', (e) => {
        if (e.target === d) { d.close(); return; }
        const b = e.target.closest('[data-i]');
        if (b && b.closest('.foot')) {
          const def = buttons[Number(b.dataset.i)];
          value = typeof def.value === 'function' ? def.value(d) : def.value;
          if (value === undefined) return;
          d.close();
        }
      });
      d.addEventListener('close', () => { d.remove(); resolve(value); });
      d.showModal();
      if (onOpen) onOpen(d, (v) => { value = v; d.close(); });
    });
  }
  const confirmDlg = (title, text, ok, danger) => dialog({
    title, body: `<p>${esc(text)}</p>`,
    buttons: [{ label: 'Anuluj', value: false }, { label: ok, value: true, cls: danger ? 'text danger-text' : 'text' }],
  });
  const promptDlg = (title, initial, ok, suffix) => dialog({
    title,
    body: `<div style="display:flex;align-items:center;gap:8px"><input type="text" id="dlg-in" value="${esc(initial)}" autocomplete="off">${suffix ? `<span>${esc(suffix)}</span>` : ''}</div>`,
    buttons: [{ label: 'Anuluj', value: null }, { label: ok, value: (d) => ($('#dlg-in', d).value.trim() || undefined) }],
    onOpen: (d, done) => {
      const i = $('#dlg-in', d);
      i.focus(); i.select();
      i.addEventListener('keydown', (e) => { if (e.key === 'Enter' && i.value.trim()) done(i.value.trim()); });
    },
  });
  function menuDlg(items) {
    return dialog({
      className: 'menu',
      body: items.map((it, i) => `<button data-m="${i}" class="${it.danger ? 'danger' : ''}" ${it.disabled ? 'disabled' : ''}>${esc(it.label)}</button>`).join(''),
      onOpen: (d, done) => d.addEventListener('click', (e) => { const b = e.target.closest('[data-m]'); if (b) done(items[Number(b.dataset.m)].value); }),
    });
  }

  // ======================================================================
  // Widoki
  // ======================================================================
  const app = $('#app');
  let cleanup = [];
  const on = (type, fn) => { bus.addEventListener(type, fn); cleanup.push(() => bus.removeEventListener(type, fn)); };

  function route() {
    const h = location.hash.replace(/^#\/?/, '');
    const [path, qs] = h.split('?');
    const parts = path.split('/').filter(Boolean);
    return { name: parts[0] || 'home', arg: parts[1], q: new URLSearchParams(qs || '') };
  }
  const go = (hash) => { if (location.hash === hash) render(); else location.hash = hash; };

  function nav(cur) {
    const it = (r, ic, l) => `<a href="#/${r === 'home' ? '' : r}" class="${cur === r ? 'on' : ''}">${icon(ic)}<span>${l}</span></a>`;
    return `<nav class="bottom"><div class="inner">${it('home', 'home', 'Start')}${it('history', 'list', 'Historia')}${it('settings', 'gear', 'Ustawienia')}</div></nav>`;
  }

  function sessionRow(s, withMenu) {
    return `<div class="session" data-open="${s.id}">
      <div class="main"><div class="title">${esc(s.title)}</div>
        <div class="meta"><span>${fmtDate(s.createdAt)} · ${fmtDur(s.durationMs)}</span><span class="chip ${s.status === 'DONE' ? 'done' : ''}">${statusLabel(s.status)}</span></div>
      </div>
      ${withMenu ? `<button class="iconbtn" data-menu="${s.id}" aria-label="Więcej">${icon('more')}</button>` : ''}
    </div>`;
  }

  function render() {
    cleanup.forEach((f) => f());
    cleanup = [];
    app.onclick = null; app.onchange = null; app.oninput = null;
    const r = route();
    const view = { home: viewHome, rec: viewRec, doc: viewDoc, history: viewHistory, settings: viewSettings }[r.name] || viewHome;
    view(r);
    window.scrollTo(0, 0);
  }
  window.addEventListener('hashchange', render);

  // ---------- Start ----------
  async function viewHome() {
    const draw = async () => {
      const sessions = await db.sessions();
      let free = '';
      try { const e = await navigator.storage.estimate(); free = fmtBytes(Math.max(0, (e.quota || 0) - (e.usage || 0))); } catch (_) { free = '—'; }
      const active = rec.status !== 'idle';
      app.innerHTML = `
        <header class="top"><div class="brand"><div class="logo">${icon('mic')}</div>LiveTranscribe</div>
          <a class="iconbtn" href="#/settings" aria-label="Ustawienia" style="color:inherit">${icon('gear')}</a></header>
        <main class="with-nav">
          <h2>Zamień mowę na tekst</h2>
          <p class="muted">Spotkania, wykłady i rozmowy – tekst pojawia się na żywo, a gotowy PDF wyślesz jednym dotknięciem.</p>
          <button class="btn hero block" data-act="start">${icon('mic')}${active ? 'WRÓĆ DO TRANSKRYPCJI' : 'ROZPOCZNIJ TRANSKRYPCJĘ'}</button>
          <button class="btn outline block" data-act="import" style="margin-top:10px">${icon('file')}WCZYTAJ NAGRANIE Z PLIKU</button>
          <input type="file" id="importfile" accept="audio/*,video/*,.mp3,.m4a,.wav,.ogg,.oga,.opus,.flac,.aac,.webm,.mp4,.mov,.caf,.aif,.aiff,.qta" hidden>
          <p class="small muted" style="margin:4px 0 0">${IS_IOS
            ? 'Np. z Dyktafonu: w nagraniu dotknij ⋯ → Zachowaj w Plikach, a potem wybierz je tutaj. Działa z M4A, MP3, WAV, AAC, FLAC i filmami. Plik zostaje w telefonie – do transkrypcji trafiają tylko jego fragmenty.'
            : 'Np. z dyktafonu: MP3, M4A, WAV, OGG, FLAC albo film MP4. Plik zostaje w telefonie – do transkrypcji trafiają tylko jego fragmenty.'}</p>
          ${installCard()}
          <div class="card soft">
            <p>${hasKey() ? `Tryb chmurowy: dźwięk jest wysyłany szyfrowanym połączeniem do ${esc(providerHost())}` : '<b>Brak klucza API</b> – dodaj go w Ustawieniach, aby zamieniać mowę na tekst.'}</p>
            <p class="small muted" style="margin-top:4px">Wolne miejsce na dane aplikacji: ${free}</p>
          </div>
          <div style="display:flex;align-items:center;margin-top:6px"><h3 style="flex:1">Ostatnie transkrypcje</h3>${sessions.length > 5 ? '<a class="btn text" href="#/history">Wszystkie</a>' : ''}</div>
          ${sessions.length ? sessions.slice(0, 5).map((s) => sessionRow(s, false)).join('') : '<p class="muted">Nie masz jeszcze żadnych transkrypcji. Dotknij dużego przycisku, aby zacząć.</p>'}
        </main>${nav('home')}`;
    };
    await draw();
    app.onclick = async (e) => {
      const t = e.target.closest('[data-act],[data-open]');
      if (!t) return;
      if (t.dataset.act === 'start') go(rec.status !== 'idle' ? '#/rec' : '#/rec?auto=1');
      // Okno wyboru pliku musi otworzyć się od razu po dotknięciu (wymóg przeglądarek).
      else if (t.dataset.act === 'import') $('#importfile').click();
      else if (t.dataset.act === 'install') doInstall();
      else if (t.dataset.open) go('#/doc/' + t.dataset.open);
    };
    app.onchange = (e) => {
      if (e.target.id !== 'importfile') return;
      const f = e.target.files && e.target.files[0];
      e.target.value = '';
      if (f) startImport(f);
    };
    on('data', draw);
    on('install', draw);
    on('settings', draw);
  }

  // ---------- Transkrypcja na żywo ----------
  async function viewRec(r) {
    app.innerHTML = `
      <header class="top"><a class="iconbtn" href="#/" aria-label="Wróć" style="color:inherit">${icon('back')}</a><h1>Transkrypcja</h1></header>
      <main class="rec">
        <div class="status"><span class="dot" id="dot"></span><span id="stat"></span></div>
        <p class="small muted" style="text-align:center;margin-top:-6px" id="mode"></p>
        <div class="timer" id="timer">00:00</div>
        <canvas class="level" id="lvl"></canvas>
        <div id="banners" style="display:flex;flex-direction:column;gap:8px"></div>
        <div class="livetext" id="live"></div>
        <div id="after"></div>
        <div class="bigctl"><button class="round" id="big" aria-label="Start">${icon('mic')}</button><span class="lbl" id="biglbl">START</span></div>
        <div class="row">
          <button class="btn tonal" id="pause">${icon('pause')}PAUZA</button>
          <button class="btn tonal" id="resume">${icon('play')}WZNÓW</button>
          <button class="btn" id="end">ZAKOŃCZ</button>
        </div>
        <div class="set"><div class="lbl"><b>Nie wygaszaj ekranu</b><small>${IS_IOS ? 'Na iPhonie konieczne: przy zablokowanym ekranie lub w innej aplikacji iOS wyłącza mikrofon stronom internetowym.' : 'Najpewniejsze przy długich spotkaniach. Wyłączone – nagrywanie zwykle trwa też przy zablokowanym telefonie.'}</small></div>
          <label class="switch"><input type="checkbox" id="awake" ${settings.keepAwake ? 'checked' : ''}><span></span></label></div>
      </main>`;

    const els = { dot: $('#dot'), stat: $('#stat'), mode: $('#mode'), timer: $('#timer'), live: $('#live'), banners: $('#banners'), after: $('#after'), big: $('#big'), biglbl: $('#biglbl'), pause: $('#pause'), resume: $('#resume'), end: $('#end') };
    let lastText = null;

    async function drawText() {
      const sid = rec.sessionId || rec.lastSessionId;
      const chunks = sid ? await db.chunks(sid) : [];
      const text = composeChunks(chunks, settings.timestamps, settings.speakers && diarizationAvailable());
      const pending = chunks.filter((c) => c.status === 'PENDING').length;
      const key = text + '|' + pending + '|' + rec.status;
      if (key === lastText) return;
      lastText = key;
      const atBottom = els.live.scrollHeight - els.live.scrollTop - els.live.clientHeight < 40;
      const placeholder = rec.status === 'idle' && !sid ? 'Tu pojawi się tekst transkrypcji.' : 'Słucham… Tekst pojawi się po pierwszych zdaniach.';
      els.live.innerHTML = (text ? esc(text) : `<span class="muted">${placeholder}</span>`) +
        (pending ? `<div class="pending"><span class="spin"></span>${pending === 1 ? 'Przetwarzam fragment…' : 'W kolejce: ' + pending + ' fragmenty'}</div>` : '');
      if (atBottom || rec.status === 'recording') els.live.scrollTop = els.live.scrollHeight;
    }

    function drawState() {
      const st = rec.status;
      const live = st === 'recording';
      els.dot.className = 'dot' + (live ? ' live' : st === 'paused' ? ' paused' : '');
      els.stat.textContent = {
        recording: queue.problem ? 'Nagrywanie – tekst czeka' : 'Nagrywanie i transkrypcja',
        paused: 'Pauza', finishing: 'Kończenie…', starting: 'Włączanie mikrofonu…',
      }[st] || (rec.lastSessionId ? 'Transkrypcja zakończona' : 'Gotowy do nagrywania');
      els.mode.textContent = 'Dźwięk jest wysyłany szyfrowanym połączeniem do ' + providerHost();
      const recOrPause = st === 'recording' || st === 'paused';
      els.big.className = 'round' + (recOrPause ? ' stop' : '');
      els.big.innerHTML = icon(recOrPause ? 'stop' : 'mic');
      els.big.disabled = st === 'finishing' || st === 'starting';
      els.biglbl.textContent = st === 'finishing' || st === 'starting' ? 'CZEKAJ…' : recOrPause ? 'STOP' : 'START';
      els.pause.disabled = st !== 'recording';
      els.resume.disabled = st !== 'paused';
      els.end.disabled = !recOrPause;
      let b = '';
      if (rec.error) b += `<div class="banner error"><span>${esc(rec.error)}</span></div>`;
      if (IS_IOS && st === 'idle' && !rec.lastSessionId) {
        b += '<div class="banner"><span>iPhone: nagrywaj z aplikacją na ekranie i nie blokuj telefonu. Długie spotkanie najpewniej nagrasz <b>Dyktafonem</b>, a potem wczytasz plik: Start → WCZYTAJ NAGRANIE Z PLIKU.</span></div>';
      }
      if (rec.wakeLockFailed && settings.keepAwake && (st === 'recording' || st === 'paused')) {
        b += '<div class="banner"><span>Ten telefon nie pozwala stronie blokować wygaszania ekranu. Na czas nagrania ustaw: Ustawienia → Ekran i jasność → Automatyczna blokada → Nigdy.</span></div>';
      }
      if (queue.problem && (st !== 'idle' || rec.lastSessionId)) b += `<div class="banner error"><span>${esc(queue.problem)}</span><button class="btn text" data-act="retry">Ponów</button></div>`;
      els.banners.innerHTML = b;
      els.after.innerHTML = st === 'idle' && rec.lastSessionId
        ? `<div class="row"><button class="btn" data-act="pdf">GENERUJ PDF</button><button class="btn outline" data-act="doc">OTWÓRZ DOKUMENT</button></div>` : '';
      els.timer.textContent = fmtDur(rec.elapsedMs);
      drawText();
    }

    // Wizualizacja poziomu dźwięku
    const cv = $('#lvl');
    const g = cv.getContext('2d');
    let raf = 0;
    function drawLevel() {
      const dpr = window.devicePixelRatio || 1;
      const w = cv.clientWidth; const h = cv.clientHeight;
      if (cv.width !== w * dpr) { cv.width = w * dpr; cv.height = h * dpr; }
      g.setTransform(dpr, 0, 0, dpr, 0, 0);
      g.clearRect(0, 0, w, h);
      const bars = 44; const slot = w / bars; const bw = slot * 0.6;
      const lv = rec.levels; const off = bars - lv.length;
      g.fillStyle = rec.status === 'recording' ? getComputedStyle(document.documentElement).getPropertyValue('--primary') : getComputedStyle(document.documentElement).getPropertyValue('--line');
      for (let i = 0; i < bars; i++) {
        const v = rec.status === 'recording' && i >= off ? lv[i - off] : 0;
        const bh = Math.min(h, h * (0.06 + 0.94 * v));
        const x = i * slot + (slot - bw) / 2; const y = (h - bh) / 2;
        g.beginPath();
        if (g.roundRect) g.roundRect(x, y, bw, bh, bw / 2); else g.rect(x, y, bw, bh);
        g.fill();
      }
      raf = requestAnimationFrame(drawLevel);
    }
    raf = requestAnimationFrame(drawLevel);
    cleanup.push(() => cancelAnimationFrame(raf));

    async function tryStart() {
      rec.set({ error: null });
      if (!settings.consentShown) {
        const ok = await dialog({
          title: 'Zanim zaczniesz nagrywać',
          body: `<p>Uprzedź uczestników rozmowy, że ją nagrywasz i zamieniasz na tekst – tego wymagają przepisy (RODO) i dobre obyczaje.</p>
            <p>Fragmenty nagrania są wysyłane szyfrowanym połączeniem do ${esc(providerHost())} (serwery mogą być poza UE) i podlegają regulaminowi tej usługi. Nagrania i teksty przechowuje tylko Twój telefon.</p>`,
          buttons: [{ label: 'Anuluj', value: false }, { label: 'Rozumiem', value: true }],
        });
        if (!ok) return undefined;
        setSetting('consentShown', true);
      }
      if (!hasKey()) {
        const v = await dialog({
          title: 'Brak klucza API',
          body: '<p>Do zamiany mowy na tekst potrzebny jest klucz API (np. darmowy z Groq). Możesz dodać go teraz albo nagrywać mimo to – nagranie zostanie zachowane w telefonie, a tekst pojawi się po dodaniu klucza.</p>',
          buttons: [{ label: 'Nagrywaj mimo to', value: 'rec' }, { label: 'Ustawienia', value: 'set' }],
        });
        if (v === 'set') return go('#/settings');
        if (v !== 'rec') return undefined;
      }
      await rec.start();
      return undefined;
    }

    async function askFinish(openDoc) {
      const ok = await dialog({
        title: 'Zakończyć transkrypcję?',
        body: `<p>${settings.autosave ? 'Nagrywanie zostanie zatrzymane, a dokument zapisany w historii.' : 'Automatyczne zapisywanie jest wyłączone. Zapisać ten dokument w historii?'}</p>`,
        buttons: [
          ...(settings.autosave ? [] : [{ label: 'Odrzuć', value: 'discard' }]),
          { label: 'Anuluj', value: null },
          { label: settings.autosave ? 'Zakończ' : 'Zakończ i zapisz', value: 'save' },
        ],
      });
      if (!ok) return;
      const sid = await rec.finish({ discard: ok === 'discard' });
      if (sid && openDoc) go('#/doc/' + sid);
    }

    els.big.onclick = () => (rec.status === 'recording' || rec.status === 'paused' ? askFinish(false) : rec.status === 'idle' ? tryStart() : null);
    els.pause.onclick = () => rec.pause();
    els.resume.onclick = () => rec.resume();
    els.end.onclick = () => askFinish(true);
    $('#awake').onchange = (e) => {
      setSetting('keepAwake', e.target.checked);
      if (e.target.checked) rec.applyWakeLock(); else rec.releaseWakeLock();
    };
    app.onclick = (e) => {
      const t = e.target.closest('[data-act]');
      if (!t) return;
      if (t.dataset.act === 'retry') queue.resume();
      if (t.dataset.act === 'pdf') go('#/doc/' + rec.lastSessionId + '?pdf=1');
      if (t.dataset.act === 'doc') go('#/doc/' + rec.lastSessionId);
    };

    on('rec', drawState);
    on('queue', drawState);
    on('data', drawText);
    on('tick', () => { els.timer.textContent = fmtDur(rec.elapsedMs); });
    drawState();
    if (r.q.get('auto') === '1' && rec.status === 'idle') {
      history.replaceState(null, '', '#/rec');
      tryStart();
    }
  }

  // ---------- Dokument ----------
  async function viewDoc(r) {
    const id = Number(r.arg);
    let editing = r.q.get('edit') === '1';
    let draft = null;
    let generating = false;
    const autoPdf = r.q.get('pdf') === '1';
    if (autoPdf || editing) history.replaceState(null, '', '#/doc/' + id);

    async function draw() {
      const s = await db.session(id);
      if (!s) { app.innerHTML = `<header class="top"><a class="iconbtn" href="#/history" style="color:inherit">${icon('back')}</a><h1>Dokument</h1></header><main><p class="empty">Ten dokument nie istnieje.</p></main>`; return; }
      const chunks = await db.chunks(id);
      const live = rec.sessionId === id;
      const importing = s.status === 'IMPORTING';
      const job = importJobs.get(id);
      if (live || importing) editing = false;
      const pending = chunks.filter((c) => c.status === 'PENDING').length;
      const failed = chunks.filter((c) => c.status === 'FAILED').length;
      const text = s.editedText != null ? s.editedText : composeChunks(chunks, settings.timestamps, settings.speakers && diarizationAvailable());
      const pdf = await db.kvGet('pdf:' + id);
      if (editing && draft == null) draft = text;

      if (editing) {
        app.innerHTML = `
          <header class="top"><button class="iconbtn" data-act="cancel" aria-label="Anuluj">${icon('close')}</button><h1>Edycja tekstu</h1>
            <button class="btn text" data-act="save">ZAPISZ</button></header>
          <main><textarea class="editor" id="ed" spellcheck="true" lang="pl">${esc(draft)}</textarea></main>`;
        $('#ed').addEventListener('input', (e) => { draft = e.target.value; });
        return;
      }
      app.innerHTML = `
        <header class="top"><a class="iconbtn" href="#/history" aria-label="Wróć" style="color:inherit">${icon('back')}</a>
          <h1>${esc(s.title)}</h1>
          <button class="iconbtn" data-act="edit" aria-label="Edytuj tekst" ${live || importing ? 'disabled' : ''}>${icon('edit')}</button>
          <button class="iconbtn" data-act="menu" aria-label="Więcej">${icon('more')}</button></header>
        <main class="with-actions">
          <div class="session" style="box-shadow:none;background:none;padding:0;cursor:default"><div class="main"><div class="meta" style="margin:0">
            <span>${fmtDate(s.createdAt)} · ${fmtDur(s.durationMs)}</span><span class="chip ${s.status === 'DONE' ? 'done' : ''}">${statusLabel(s.status)}</span></div></div></div>
          ${live ? '<div class="banner"><span>Ten dokument jest właśnie nagrywany. Edycja i PDF będą dostępne po zakończeniu.</span></div>' : ''}
          ${importing ? `<div class="banner"><span>Wczytuję plik nagrania${job ? ' – ' + Math.floor(job.progress * 100) + '%' : '…'}. Nie zamykaj tej strony do końca wczytywania – tekst pojawia się na bieżąco.</span></div>
            <div class="progress det"><i style="width:${job ? Math.floor(job.progress * 100) : 0}%"></i></div>` : ''}
          ${s.importError ? `<div class="banner error"><span>${esc(s.importError)}</span><button class="btn text" data-act="importok">OK</button></div>` : ''}
          ${pending && !live && (!importing || queue.problem) ? `<div class="banner ${queue.problem ? 'error' : ''}"><span>${queue.problem ? esc(queue.problem) + ' (w kolejce: ' + pending + ')' : 'Kończę transkrypcję – pozostało fragmentów: ' + pending}</span>${queue.problem ? '<button class="btn text" data-act="retry">Ponów</button>' : ''}</div>${queue.problem ? '' : '<div class="progress"><i></i></div>'}` : ''}
          ${failed ? `<div class="banner error"><span>Nie udało się przetworzyć fragmentów: ${failed}</span><button class="btn text" data-act="retryfailed">Ponów</button></div>` : ''}
          ${!live && !importing && (s.note || noteJobs.has(id) || (s.status === 'DONE' && text.trim())) ? noteCard(s.note, noteJobs.get(id)) : ''}
          <div class="doctext">${text.trim() ? esc(text) : `<span class="muted">${pending || s.status !== 'DONE' ? 'Tekst pojawi się po przetworzeniu nagrania.' : 'Ten dokument nie zawiera tekstu (w nagraniu nie wykryto mowy).'}</span>`}</div>
        </main>
        <div class="actions"><div class="inner">
          ${!pdf ? `<button class="btn block" data-act="gen" ${live || importing || generating ? 'disabled' : ''}>${generating ? '<span class="spin"></span>Tworzę PDF…' : 'GENERUJ PDF'}</button>`
            : `<p class="small muted" style="white-space:nowrap;overflow:hidden;text-overflow:ellipsis">PDF gotowy: ${esc(pdf.name)}</p>
               <button class="btn block" data-act="download">${icon('download')}POBIERZ PDF</button>
               <div class="row"><button class="btn tonal" data-act="share">UDOSTĘPNIJ PDF</button><button class="btn tonal" data-act="open">OTWÓRZ PDF</button></div>
               <button class="btn text" data-act="gen" ${generating ? 'disabled' : ''}>${generating ? 'Tworzę PDF…' : 'Generuj PDF ponownie'}</button>`}
        </div></div>`;
    }

    async function genFlow() {
      const s = await db.session(id);
      const chunks = await db.chunks(id);
      const hasSpk = chunks.some((c) => (c.segs || []).some((x) => x.p));
      const pending = chunks.filter((c) => c.status === 'PENDING').length;
      const edited = s.editedText != null;
      const res = await dialog({
        title: 'Generuj PDF',
        body: (edited ? '<p>Dokument zostanie utworzony z tekstu po Twoich poprawkach.</p>'
          : `<div class="set"><div class="lbl"><b>Znaczniki czasu</b></div><label class="switch"><input type="checkbox" id="o-ts" ${settings.timestamps ? 'checked' : ''}><span></span></label></div>
             <div class="set"><div class="lbl"><b>Rozmówcy</b>${hasSpk ? '' : '<small>Brak danych o rozmówcach w tym nagraniu</small>'}</div><label class="switch"><input type="checkbox" id="o-sp" ${hasSpk && settings.speakers ? 'checked' : ''} ${hasSpk ? '' : 'disabled'}><span></span></label></div>`) +
          (s.note ? `<div class="set"><div class="lbl"><b>Notatka AI na początku</b></div><label class="switch"><input type="checkbox" id="o-note" checked><span></span></label></div>` : '') +
          (pending ? `<p class="small" style="color:var(--danger)">Uwaga: ${pending} fragmentów czeka jeszcze na transkrypcję i nie trafi do tego PDF.</p>` : ''),
        buttons: [{ label: 'Anuluj', value: null }, {
          label: 'Generuj',
          value: (d) => ({ ts: edited ? false : $('#o-ts', d).checked, sp: edited ? false : $('#o-sp', d).checked, note: $('#o-note', d) ? $('#o-note', d).checked : false }),
        }],
      });
      if (!res) return;
      generating = true;
      await draw();
      try {
        await generatePdf(id, res.ts, res.sp, res.note);
        toast('PDF gotowy');
      } catch (e) {
        toast(navigator.onLine ? 'Nie udało się utworzyć PDF (' + e.message + '). Spróbuj ponownie.' : 'Do utworzenia PDF potrzebny jest internet.', 5000);
      } finally {
        generating = false;
        draw();
      }
    }

    app.onclick = async (e) => {
      const t = e.target.closest('[data-act]');
      if (!t) return;
      const act = t.dataset.act;
      const s = await db.session(id);
      if (!s) return;
      if (act === 'edit') { editing = true; draft = null; draw(); }
      else if (act === 'cancel') { editing = false; draft = null; draw(); }
      else if (act === 'save') {
        await db.patchSession(id, { editedText: draft });
        await db.kvDel('pdf:' + id);
        editing = false; draft = null;
        toast('Zapisano zmiany');
        draw();
      } else if (act === 'gen') genFlow();
      else if (act === 'note') createNote(id);
      else if (act === 'note-copy') {
        try { await navigator.clipboard.writeText(noteText(s.note)); toast('Skopiowano notatkę'); } catch (_) { toast('Nie udało się skopiować'); }
      } else if (act === 'note-del') {
        if (await confirmDlg('Usunąć notatkę?', 'Transkrypcja zostanie bez zmian.', 'Usuń', true)) { await db.patchSession(id, { note: null }); await db.kvDel('pdf:' + id); }
      }
      else if (act === 'retry') queue.resume();
      else if (act === 'importok') await db.patchSession(id, { importError: null });
      else if (act === 'retryfailed') {
        for (const c of await db.chunks(id)) if (c.status === 'FAILED' && c.audio) await db.putChunk({ ...c, status: 'PENDING', attempts: 0, error: null });
        if (s.status === 'DONE') await db.patchSession(id, { status: 'PROCESSING' });
        queue.resume();
      } else if (act === 'download') {
        const pdf = await db.kvGet('pdf:' + id);
        const name = await promptDlg('Nazwa pliku', pdf.name.replace(/\.pdf$/i, ''), 'Pobierz', '.pdf');
        if (!name) return;
        const how = await saveFiles([new File([pdf.blob], safeName(name) + '.pdf', { type: 'application/pdf' })]);
        if (how === 'download') toast((IS_IOS ? 'Zapisano w aplikacji Pliki → Pobrane: ' : 'Zapisano w folderze Pobrane (Download): ') + safeName(name) + '.pdf', 6000);
      } else if (act === 'share') sharePdf(await db.kvGet('pdf:' + id), s.title);
      else if (act === 'open') openPdf(await db.kvGet('pdf:' + id));
      else if (act === 'menu') {
        const chunks = await db.chunks(id);
        const audio = chunks.filter((c) => c.audio && c.status === 'DONE');
        const v = await menuDlg([
          { label: 'Zmień nazwę', value: 'rename' },
          ...(s.editedText != null && chunks.some((c) => c.status === 'DONE') ? [{ label: 'Przywróć tekst sprzed edycji', value: 'restore' }] : []),
          { label: 'Kopiuj tekst', value: 'copy' },
          ...(audio.length ? [{ label: 'Pobierz nagranie audio', value: 'audio' }] : []),
          { label: 'Usuń dokument', value: 'delete', danger: true, disabled: rec.sessionId === id },
        ]);
        if (v === 'rename') {
          const name = await promptDlg('Zmień nazwę', s.title, 'Zapisz');
          if (name) { await db.patchSession(id, { title: name }); await db.kvDel('pdf:' + id); draw(); }
        } else if (v === 'restore') {
          await db.patchSession(id, { editedText: null }); await db.kvDel('pdf:' + id); draw();
        } else if (v === 'copy') {
          const text = s.editedText != null ? s.editedText : composeChunks(chunks, settings.timestamps, settings.speakers && diarizationAvailable());
          try { await navigator.clipboard.writeText(text); toast('Skopiowano tekst'); } catch (_) { toast('Nie udało się skopiować'); }
        } else if (v === 'audio') {
          await saveFiles(audio.map((c) => new File([c.audio], safeName(s.title) + '_' + String(c.idx + 1).padStart(3, '0') + '.wav', { type: 'audio/wav' })));
        } else if (v === 'delete') {
          if (await confirmDlg('Usunąć dokument?', `„${s.title}” zostanie trwale usunięty z aplikacji. Pliki PDF pobrane wcześniej pozostaną w telefonie.`, 'Usuń', true)) {
            await db.deleteSession(id);
            go('#/history');
          }
        }
      }
    };

    on('data', (e) => { if (!editing && (!e.detail || !e.detail.sessionId || e.detail.sessionId === id)) draw(); });
    on('queue', () => { if (!editing) draw(); });
    on('note', (e) => { if (!editing && e.detail.sessionId === id) draw(); });
    on('rec', () => { if (!editing) draw(); });
    on('import', (e) => { if (!editing && e.detail.sessionId === id) draw(); });
    await draw();
    const s0 = await db.session(id);
    if (autoPdf && rec.sessionId !== id && s0 && s0.status !== 'IMPORTING') genFlow();
  }

  // ---------- Historia ----------
  async function viewHistory() {
    async function draw() {
      const sessions = await db.sessions();
      app.innerHTML = `<header class="top"><h1>Historia</h1></header>
        <main class="with-nav">${sessions.length ? sessions.map((s) => sessionRow(s, true)).join('') : '<p class="empty">Historia jest pusta.</p>'}</main>${nav('history')}`;
    }
    app.onclick = async (e) => {
      const m = e.target.closest('[data-menu]');
      if (m) {
        e.stopPropagation();
        const id = Number(m.dataset.menu);
        const s = await db.session(id);
        const live = rec.sessionId === id || (s && s.status === 'IMPORTING');
        const v = await menuDlg([
          { label: 'Otwórz', value: 'open' },
          { label: 'Zmień nazwę', value: 'rename' },
          { label: 'Edytuj tekst', value: 'edit', disabled: live },
          { label: 'Generuj PDF', value: 'pdf', disabled: live },
          { label: 'Usuń', value: 'delete', danger: true, disabled: rec.sessionId === id },
        ]);
        if (v === 'open') go('#/doc/' + id);
        if (v === 'edit') go('#/doc/' + id + '?edit=1');
        if (v === 'pdf') go('#/doc/' + id + '?pdf=1');
        if (v === 'rename') {
          const name = await promptDlg('Zmień nazwę', s.title, 'Zapisz');
          if (name) { await db.patchSession(id, { title: name }); await db.kvDel('pdf:' + id); }
        }
        if (v === 'delete' && await confirmDlg('Usunąć dokument?', `„${s.title}” zostanie trwale usunięty z aplikacji.`, 'Usuń', true)) await db.deleteSession(id);
        return;
      }
      const o = e.target.closest('[data-open]');
      if (o) go('#/doc/' + o.dataset.open);
    };
    on('data', draw);
    await draw();
  }

  // ---------- Ustawienia ----------
  async function viewSettings() {
    const sel = (k, opts, disabled) => `<select data-set="${k}" ${disabled ? 'disabled' : ''}>${opts.map(([v, l]) => `<option value="${v}" ${settings[k] === v ? 'selected' : ''}>${esc(l)}</option>`).join('')}</select>`;
    const tog = (k, title, sub, disabled, checked = settings[k]) => `<div class="set"><div class="lbl"><b>${esc(title)}</b>${sub ? `<small>${esc(sub)}</small>` : ''}</div>
      <label class="switch"><input type="checkbox" data-tog="${k}" ${checked ? 'checked' : ''} ${disabled ? 'disabled' : ''}><span></span></label></div>`;

    function draw() {
      const busy = rec.status !== 'idle';
      const prov = settings.provider;
      const serverKey = serverConfig.serverKeys && serverConfig.serverKeys[prov];
      app.innerHTML = `<header class="top"><h1>Ustawienia</h1></header>
      <main class="with-nav">
        ${installCard()}
        <div class="section">Transkrypcja</div>
        <div class="field"><label>Język transkrypcji</label>${sel('language', [['pl', 'Polski'], ['auto', 'Wykrywaj automatycznie'], ['en', 'Angielski'], ['de', 'Niemiecki'], ['uk', 'Ukraiński'], ['fr', 'Francuski'], ['es', 'Hiszpański']])}</div>
        <div class="field"><label>Silnik transkrypcji</label>${sel('provider', [['groq', 'Groq – Whisper large-v3 (ma darmowy plan)'], ['gemini', 'Google Gemini – darmowy, rozpoznaje rozmówców'], ['openai', 'OpenAI – płatny, rozpoznaje rozmówców']], busy)}
          ${prov === 'gemini' ? '<small class="muted">Gemini zapisuje też wtrącenia i oznacza, kto mówi. Tekst pojawia się co 30–60 s. Darmowy plan ma dzienny limit (obecnie ok. 500 fragmentów, czyli kilka godzin nagrań – sprawdzisz go na aistudio.google.com/rate-limit). Google może wykorzystywać przesłane w nim nagrania do ulepszania swoich usług.</small>' : ''}
          <small class="muted">Dźwięk jest wysyłany szyfrowanym połączeniem (HTTPS) przez serwer aplikacji do ${esc(providerHost())}. Serwer niczego nie zapisuje.</small></div>
        <div class="field"><label>Jakość rozpoznawania</label>${sel('quality', [['fast', 'Szybka – tekst pojawia się najszybciej'], ['balanced', 'Zrównoważona'], ['accurate', 'Dokładna – dłuższe fragmenty, tekst z opóźnieniem']], busy)}</div>

        <div class="section">Klucz API</div>
        ${serverKey ? '<p class="small muted">Serwer ma już skonfigurowany klucz – nie musisz nic wpisywać. Możesz podać własny, aby go zastąpić.</p>' : ''}
        <div class="field"><label for="key">Klucz API ${providerName(prov)}</label>
          <div style="display:flex;gap:8px"><input type="password" id="key" autocomplete="off" placeholder="${{ openai: 'sk-…', gemini: 'AIza…' }[prov] || 'gsk_…'}" value="${esc(secret.get('key.' + prov))}">
          <button class="btn outline" id="showkey" style="min-height:50px">Pokaż</button></div>
          <small class="muted">${{ openai: 'Klucz utworzysz na platform.openai.com → API keys.', gemini: 'Darmowy klucz: aistudio.google.com → Get API key → Create API key (wystarczy konto Google).' }[prov] || 'Darmowy klucz: console.groq.com → API Keys → Create API Key.'} Klucz jest zapisany tylko w tym telefonie.</small></div>
        <button class="btn" id="savekey">Zapisz i sprawdź klucz</button>
        ${serverConfig.passwordRequired ? `<div class="field"><label for="pw">Hasło dostępu do serwera</label><input type="password" id="pw" autocomplete="current-password" value="${esc(secret.get('password'))}"><small class="muted">Ustawione przez właściciela aplikacji, chroni jego klucz API.</small></div><button class="btn tonal" id="savepw">Zapisz hasło</button>` : ''}

        <div class="section">Słownik nazw</div>
        <div class="field"><label for="gloss">Nazwiska, firmy, produkty, skróty</label>
          <textarea id="gloss" rows="3" style="width:100%;border-radius:12px;border:1.5px solid var(--line);background:var(--surface);padding:10px 12px" placeholder="np. Kowalczyk, Allegro, KPI, Jira">${esc(settings.glossary)}</textarea>
          <small class="muted">Oddziel przecinkami. Model częściej zapisze te słowa poprawnie.</small></div>

        <div class="section">Tekst</div>
        <div class="card" style="padding:4px 16px">
          ${tog('autosave', 'Automatyczne zapisywanie', 'Każda transkrypcja trafia do historii bez pytania')}
          ${tog('timestamps', 'Znaczniki czasu', 'Np. [12:05] przed każdym fragmentem')}
          ${tog('speakers', 'Rozpoznawanie rozmówców', diarizationAvailable() ? (prov === 'gemini' ? 'Etykiety „Rozmówca A/B”; Gemini stara się zachować te same litery w kolejnych fragmentach' : 'Etykiety „Rozmówca A/B” są nadawane osobno w każdym fragmencie nagrania') : 'Dostępne z silnikiem Google Gemini (darmowy) lub OpenAI', !diarizationAvailable() || busy, settings.speakers && diarizationAvailable())}
        </div>

        <div class="section">Dokument PDF</div>
        <div class="field"><label>Rozmiar czcionki</label>${sel('pdfFontSize', [['small', 'Mała'], ['medium', 'Średnia'], ['large', 'Duża']])}</div>
        <div class="card" style="padding:4px 16px">${tog('pdfHeader', 'Nagłówek na każdej stronie', 'Tytuł dokumentu u góry kolejnych stron')}</div>

        <div class="section">Wygląd</div>
        <div class="field"><label>Motyw</label>${sel('theme', [['system', 'Jak w systemie'], ['light', 'Jasny'], ['dark', 'Ciemny']])}</div>

        <div class="section">Prywatność i dane</div>
        <div class="card" style="padding:4px 16px">${tog('keepAudio', 'Zachowuj nagrania audio', 'Wyłączone: dźwięk jest usuwany z telefonu zaraz po zamianie na tekst')}</div>
        <p class="small muted">Nagrania, teksty i klucz API są przechowywane wyłącznie w pamięci tej przeglądarki na Twoim telefonie. Serwer tylko przekazuje fragmenty nagrania do usługi transkrypcji i niczego nie zapisuje.</p>
        <button class="btn danger-outline" id="delhist" ${busy ? 'disabled' : ''}>Usuń historię transkrypcji</button>
        <button class="btn danger-outline" id="delall" ${busy ? 'disabled' : ''}>Usuń wszystkie dane aplikacji</button>

        <div class="section">Aplikacja na Androida</div>
        <p class="small muted">Wolisz klasyczną aplikację? Pobierz plik APK (wymaga zgody na instalację z nieznanych źródeł).</p>
        <a class="btn outline" href="/apk">${icon('download')}Pobierz APK na Androida</a>
        <p class="small muted" style="text-align:center;margin-top:12px">LiveTranscribe Web 1.3</p>
      </main>${nav('settings')}`;
    }
    draw();

    app.oninput = (e) => {
      if (e.target.id === 'gloss') {
        settings = { ...settings, glossary: e.target.value };
        try { localStorage.setItem('lt.settings', JSON.stringify(settings)); } catch (_) { /* */ }
      }
    };
    app.onchange = (e) => {
      const s = e.target.closest('[data-set]');
      if (s) { setSetting(s.dataset.set, s.value); draw(); return; }
      const t = e.target.closest('[data-tog]');
      if (t) { setSetting(t.dataset.tog, t.checked); }
    };
    app.onclick = async (e) => {
      const id = e.target.closest('button,a') && e.target.closest('button,a').id;
      if (e.target.closest('[data-act="install"]')) return doInstall();
      if (id === 'showkey') { const k = $('#key'); k.type = k.type === 'password' ? 'text' : 'password'; e.target.textContent = k.type === 'password' ? 'Pokaż' : 'Ukryj'; }
      if (id === 'savekey') {
        const key = $('#key').value.trim();
        secret.set('key.' + settings.provider, key);
        if (!key && !(serverConfig.serverKeys && serverConfig.serverKeys[settings.provider])) { toast('Usunięto klucz'); return; }
        toast('Sprawdzam klucz…');
        try {
          const r = await fetch('/api/check?provider=' + settings.provider, { method: 'POST', headers: { 'Content-Type': 'application/json', ...authHeaders() }, body: '{}' });
          const d = await r.json().catch(() => ({}));
          toast(r.ok ? 'Klucz zapisany i działa' : 'Klucz zapisany. ' + (d.error || 'Nie udało się go sprawdzić.'), 5000);
        } catch (_) { toast('Klucz zapisany. Brak połączenia – sprawdzę go przy pierwszym nagraniu.', 5000); }
        queue.resume();
      }
      if (id === 'savepw') { secret.set('password', $('#pw').value.trim()); toast('Hasło zapisane'); queue.resume(); }
      if (id === 'delhist' && await confirmDlg('Usunąć całą historię?', 'Wszystkie transkrypcje, nagrania i PDF-y w aplikacji zostaną trwale usunięte. Pobrane wcześniej pliki pozostaną w telefonie.', 'Usuń', true)) {
        await db.clearAll(); toast('Historia usunięta');
      }
      if (id === 'delall' && await confirmDlg('Usunąć wszystkie dane?', 'Znikną transkrypcje, nagrania, klucz API, hasło i ustawienia. Tej operacji nie można cofnąć.', 'Usuń wszystko', true)) {
        await db.clearAll();
        try { Object.keys(localStorage).filter((k) => k.startsWith('lt.')).forEach((k) => localStorage.removeItem(k)); } catch (_) { /* */ }
        settings = loadSettings();
        applyTheme();
        toast('Wszystkie dane usunięte');
        draw();
      }
    };
    on('settings', draw);
    on('install', draw);
    on('rec', draw);
  }

  // ======================================================================
  // Start aplikacji
  // ======================================================================
  applyTheme();
  matchMedia('(prefers-color-scheme: dark)').addEventListener('change', applyTheme);
  if ('serviceWorker' in navigator) navigator.serviceWorker.register('/sw.js').catch(() => {});
  (async () => {
    try { await recover(); } catch (e) { console.error(e); }
    render();
    toast('Miłego dnia maluszku\u00a0❤️', 4000); // powitanie przy każdym otwarciu aplikacji
    await loadServerConfig();
    queue.kick();
  })();

  // Dostęp do testów automatycznych
  window.__lt = { db, rec, queue, settings: () => settings, setSetting };
})();
