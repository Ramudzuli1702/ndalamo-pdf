/**
 * @OnlyCurrentDoc
 * (Limits the script's permission to the spreadsheet it's attached to. Without this line Google
 * grants access to all of the owner's spreadsheets, because the script uses SpreadsheetApp.)
 */

// Ndalamo reviews: receives "Tell us what you think" from the app and adds each review as a row
// of the "Reviews" sheet in the spreadsheet this script is attached to (decision 020).
// Setup: packaging/feedback/README.md.

const SHEET_NAME = 'Reviews';
const HEADINGS = ['Received', 'Stars', 'Review', 'Version', 'Written (PC time)', 'ID'];

function doPost(e) {
  const lock = LockService.getScriptLock();
  try {
    lock.waitLock(10000);
    const data = JSON.parse(e.postData.contents);
    const stars = Number(data.rating);
    if (!(stars >= 1 && stars <= 5)) return reply(false, 'rating must be 1 to 5');
    const id = String(data.id || '').slice(0, 64);
    const sheet = reviewsSheet();

    // A review sent twice (the app didn't get the first reply) is kept once.
    const last = sheet.getLastRow();
    if (id && last > 1) {
      const from = Math.max(2, last - 499);
      const ids = sheet.getRange(from, 6, last - from + 1, 1).getValues().map(row => row[0]);
      if (ids.indexOf(id) >= 0) return reply(true);
    }

    sheet.appendRow([new Date(), Math.round(stars), plain(data.review, 2000), plain(data.version, 20),
                     plain(data.written, 25), id]);
    return reply(true);
  } catch (err) {
    return reply(false, 'not a review');
  } finally {
    lock.releaseLock();
  }
}

/** Text as typed, cut to a length, never read as a formula by the spreadsheet (=, +, -, @). */
function plain(value, max) {
  const text = String(value == null ? '' : value).slice(0, max);
  return /^[=+\-@]/.test(text) ? "'" + text : text;
}

function reviewsSheet() {
  const book = SpreadsheetApp.getActiveSpreadsheet();
  let sheet = book.getSheetByName(SHEET_NAME);
  if (!sheet) {
    sheet = book.insertSheet(SHEET_NAME);
    sheet.appendRow(HEADINGS);
    sheet.setFrozenRows(1);
    sheet.getRange(1, 1, 1, HEADINGS.length).setFontWeight('bold');
    sheet.setColumnWidth(3, 480);
  }
  return sheet;
}

function reply(ok, why) {
  const body = ok ? { ok: true } : { ok: false, error: why };
  return ContentService.createTextOutput(JSON.stringify(body)).setMimeType(ContentService.MimeType.JSON);
}
