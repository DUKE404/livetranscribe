'use strict';
/** Generowanie PDF (A4) z transkrypcji – czcionka DejaVu zapewnia polskie znaki. */
const PDFDocument = require('pdfkit');
const path = require('path');

const FONT_DIR = path.join(path.dirname(require.resolve('dejavu-fonts-ttf/package.json')), 'ttf');
const REGULAR = path.join(FONT_DIR, 'DejaVuSans.ttf');
const BOLD = path.join(FONT_DIR, 'DejaVuSans-Bold.ttf');

const INK = '#1b1b1f';
const GRAY = '#6e727d';
const LINE = '#d6dae4';
const ACCENT = '#2456e6';

const TS = /^\[\d{1,2}:\d{2}(?::\d{2})?\]\s*/;
const SPEAKER = /^((?:Rozmówca|Speaker) [^:]{1,20}:)\s*/;

function durationText(ms) {
  const t = Math.floor(ms / 1000);
  const h = Math.floor(t / 3600);
  const m = Math.floor((t % 3600) / 60);
  const s = t % 60;
  let out = '';
  if (h > 0) out += h + ' godz. ';
  if (h > 0 || m > 0) out += m + ' min ';
  return out + s + ' s';
}

function dateText(ts, timeZone) {
  try {
    return new Intl.DateTimeFormat('pl-PL', {
      day: 'numeric', month: 'long', year: 'numeric', hour: '2-digit', minute: '2-digit', timeZone,
    }).format(new Date(ts));
  } catch (_) {
    return new Date(ts).toISOString().replace('T', ' ').slice(0, 16);
  }
}

function buildPdf({ title, createdAt, durationMs, text, fontSize, headerOnEachPage, timeZone }) {
  return new Promise((resolve, reject) => {
    const M = 56;
    const doc = new PDFDocument({
      size: 'A4',
      margins: { top: 66, bottom: 66, left: M, right: M },
      bufferPages: true,
      info: { Title: title, Creator: 'LiveTranscribe', Producer: 'LiveTranscribe' },
      lang: 'pl-PL',
    });
    doc.registerFont('R', REGULAR);
    doc.registerFont('B', BOLD);
    const chunks = [];
    doc.on('data', (c) => chunks.push(c));
    doc.on('end', () => resolve(Buffer.concat(chunks)));
    doc.on('error', reject);

    const W = doc.page.width - 2 * M;
    const words = text.split(/\s+/).filter(Boolean).length;

    // Nagłówek pierwszej strony
    doc.rect(M, 66, 36, 3).fill(ACCENT);
    doc.moveDown(0.2);
    doc.y = 76;
    doc.font('B').fontSize(20).fillColor(INK).text(title || 'Transkrypcja', M, doc.y, { width: W, lineGap: 2 });
    doc.moveDown(0.5);
    doc.font('R').fontSize(10).fillColor(GRAY)
      .text('Data nagrania: ' + dateText(createdAt, timeZone), { width: W, lineGap: 3 })
      .text('Czas trwania: ' + durationText(durationMs), { width: W, lineGap: 3 })
      .text('Liczba słów: ' + words, { width: W, lineGap: 3 });
    doc.moveDown(0.9);
    doc.moveTo(M, doc.y).lineTo(M + W, doc.y).lineWidth(0.8).strokeColor(LINE).stroke();
    doc.moveDown(1.1);

    const paragraphs = text.split('\n').map((p) => p.trim()).filter(Boolean);
    if (!paragraphs.length) paragraphs.push('(Brak tekstu)');
    const gap = fontSize * 0.42;

    for (const para of paragraphs) {
      let rest = para;
      const opts = { width: W, lineGap: gap, align: 'left' };
      doc.x = M;
      const ts = rest.match(TS);
      const parts = [];
      if (ts) {
        parts.push({ font: 'B', color: GRAY, t: ts[0].trim() + ' ' });
        rest = rest.slice(ts[0].length);
      }
      const sp = rest.match(SPEAKER);
      if (sp) {
        parts.push({ font: 'B', color: INK, t: sp[1] + ' ' });
        rest = rest.slice(sp[0].length);
      }
      parts.push({ font: 'R', color: INK, t: rest });
      parts.forEach((p, i) => {
        doc.font(p.font).fontSize(fontSize).fillColor(p.color)
          .text(p.t, { ...opts, continued: i < parts.length - 1 });
      });
      doc.moveDown(0.55);
    }

    // Stopki i nagłówki kolejnych stron
    const range = doc.bufferedPageRange();
    for (let i = range.start; i < range.start + range.count; i++) {
      doc.switchToPage(i);
      const bottom = doc.page.height - 38;
      const savedBottom = doc.page.margins.bottom;
      doc.page.margins.bottom = 0;
      doc.moveTo(M, bottom - 14).lineTo(M + W, bottom - 14).lineWidth(0.8).strokeColor(LINE).stroke();
      doc.font('R').fontSize(8.5).fillColor(GRAY);
      doc.text('LiveTranscribe', M, bottom - 6, { width: W / 2, lineBreak: false });
      const label = 'Strona ' + (i + 1) + ' z ' + range.count;
      doc.text(label, M + W - doc.widthOfString(label), bottom - 6, { lineBreak: false });
      if (i > 0 && headerOnEachPage) {
        doc.text(title, M, 34, { width: W, height: 12, ellipsis: true, lineBreak: false });
        doc.moveTo(M, 50).lineTo(M + W, 50).lineWidth(0.8).strokeColor(LINE).stroke();
      }
      doc.page.margins.bottom = savedBottom;
    }
    doc.end();
  });
}

module.exports = { buildPdf, durationText };
