/**
 * @OnlyCurrentDoc
 * (Limits the script's permission to the spreadsheet it's attached to. Without this line Google
 * grants access to all of the owner's spreadsheets, because the script uses SpreadsheetApp.)
 */

// NdalamoPDF: receives what the app sends (decision 020) and adds it as a row of this spreadsheet:
// - reviews ("Tell us what you think") go to the "Reviews" sheet;
// - answers to the welcome question (who uses NdalamoPDF and what for) go to the "Users" sheet.
// Each sheet is created, with headings, the first time something arrives for it.
// Setup: packaging/feedback/README.md.

const SHEETS = {
  review: { name: 'Reviews', headings: ['Received', 'Stars', 'Review', 'Version', 'Written (PC time)', 'ID'], wide: 3 },
  about: { name: 'Users', headings: ['Received', 'Who they are', 'What they use it for', 'Version', 'Answered (PC time)', 'ID'], wide: 3 }
};

function doPost(e) {
  const lock = LockService.getScriptLock();
  try {
    lock.waitLock(10000);
    const data = JSON.parse(e.postData.contents);
    const kind = data.kind === 'about' ? 'about' : 'review';
    const id = String(data.id || '').slice(0, 64);
    let row;
    if (kind === 'review') {
      const stars = Number(data.rating);
      if (!(stars >= 1 && stars <= 5)) return reply(false, 'rating must be 1 to 5');
      row = [new Date(), Math.round(stars), plain(data.review, 2000), plain(data.version, 20), plain(data.written, 25), id];
    } else {
      const uses = Array.isArray(data.uses) ? data.uses.map(u => plain(u, 60)).slice(0, 10).join(', ') : '';
      if (!data.role && !uses) return reply(false, 'no answers');
      row = [new Date(), plain(data.role, 60), uses, plain(data.version, 20), plain(data.written, 25), id];
    }
    const sheet = sheetFor(kind);

    // Something sent twice (the app didn't get the first reply) is kept once.
    const last = sheet.getLastRow();
    if (id && last > 1) {
      const from = Math.max(2, last - 499);
      const ids = sheet.getRange(from, 6, last - from + 1, 1).getValues().map(r => r[0]);
      if (ids.indexOf(id) >= 0) return reply(true);
    }
    sheet.appendRow(row);
    return reply(true);
  } catch (err) {
    return reply(false, 'not understood');
  } finally {
    lock.releaseLock();
  }
}

/** Text as typed, cut to a length, never read as a formula by the spreadsheet (=, +, -, @). */
function plain(value, max) {
  const text = String(value == null ? '' : value).slice(0, max);
  return /^[=+\-@]/.test(text) ? "'" + text : text;
}

function sheetFor(kind) {
  const spec = SHEETS[kind];
  const book = SpreadsheetApp.getActiveSpreadsheet();
  let sheet = book.getSheetByName(spec.name);
  if (!sheet) {
    sheet = book.insertSheet(spec.name);
    sheet.appendRow(spec.headings);
    sheet.setFrozenRows(1);
    sheet.getRange(1, 1, 1, spec.headings.length).setFontWeight('bold');
    sheet.setColumnWidth(spec.wide, 420);
  }
  return sheet;
}

function reply(ok, why) {
  const body = ok ? { ok: true } : { ok: false, error: why };
  return ContentService.createTextOutput(JSON.stringify(body)).setMimeType(ContentService.MimeType.JSON);
}
