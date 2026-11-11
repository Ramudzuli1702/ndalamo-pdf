/**
 * @OnlyCurrentDoc
 * (Limits the script's spreadsheet permission to the spreadsheet it's attached to. Without this
 * line Google grants access to all of the owner's spreadsheets, because the script uses SpreadsheetApp.)
 */

// Receives what NdalamoPDF and the portfolio site send (decision 020) and adds each as a row:
// - reviews ("Tell us what you think" in the app) go to the "Reviews" sheet;
// - answers to the app's welcome question (who uses it and what for) go to the "Users" sheet;
// - messages from the portfolio's contact form go to the "Messages" sheet, and the owner gets an
//   email for each (Reply goes straight to the sender).
// Each sheet is created, with headings, the first time something arrives for it.
// The portfolio also asks for the reviews ticked "Show on site" (doGet with ?reviews=1).
// Setup: packaging/feedback/README.md.

const SHEETS = {
  review: { name: 'Reviews', headings: ['Received', 'Stars', 'Review', 'Version', 'Written (PC time)', 'ID', 'Show on site'], wide: 3 },
  about: { name: 'Users', headings: ['Received', 'Who they are', 'What they use it for', 'Version', 'Answered (PC time)', 'ID'], wide: 3 },
  contact: { name: 'Messages', headings: ['Received', 'Name', 'Email', 'About', 'Message', 'From page'], wide: 5 }
};

function doPost(e) {
  const lock = LockService.getScriptLock();
  try {
    lock.waitLock(10000);
    const data = JSON.parse(e.postData.contents);
    const kind = SHEETS[data.kind] ? data.kind : 'review';
    const id = String(data.id || '').slice(0, 64);
    let row;
    if (kind === 'review') {
      const stars = Number(data.rating);
      if (!(stars >= 1 && stars <= 5)) return reply(false, 'rating must be 1 to 5');
      row = [new Date(), Math.round(stars), plain(data.review, 2000), plain(data.version, 20), plain(data.written, 25), id, false];
    } else if (kind === 'about') {
      const uses = Array.isArray(data.uses) ? data.uses.map(u => plain(u, 60)).slice(0, 10).join(', ') : '';
      if (!data.role && !uses) return reply(false, 'no answers');
      row = [new Date(), plain(data.role, 60), uses, plain(data.version, 20), plain(data.written, 25), id];
    } else {
      const name = plain(data.name, 100), email = String(data.email || '').trim().slice(0, 150), message = plain(data.message, 3000);
      if (!name || !message || !/^[^@\s]+@[^@\s]+\.[^@\s]+$/.test(email)) return reply(false, 'name, email and message are needed');
      row = [new Date(), name, plain(email, 150), plain(data.topic, 60), message, plain(data.page, 200)];
      tellOwner(name, email, String(data.topic || ''), String(data.message || '').slice(0, 3000));
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
    if (kind === 'review') sheet.getRange(sheet.getLastRow(), 7).insertCheckboxes();
    return reply(true);
  } catch (err) {
    return reply(false, 'not understood');
  } finally {
    lock.releaseLock();
  }
}

/** The reviews ticked "Show on site", newest first, and the average of all reviews. */
function doGet(e) {
  if (!e || !e.parameter || !e.parameter.reviews) return reply(false, 'nothing here');
  const sheet = SpreadsheetApp.getActiveSpreadsheet().getSheetByName(SHEETS.review.name);
  if (!sheet || sheet.getLastRow() < 2) return json({ reviews: [], count: 0, average: 0 });
  const rows = sheet.getRange(2, 1, sheet.getLastRow() - 1, 7).getValues();
  const stars = rows.map(r => Number(r[1])).filter(n => n >= 1 && n <= 5);
  const shown = rows.filter(r => r[6] === true && Number(r[1]) >= 1)
    .reverse()
    .map(r => ({ stars: Math.round(Number(r[1])), review: String(r[2] || '').replace(/^'/, ''),
                 date: Utilities.formatDate(new Date(r[0]), 'Africa/Johannesburg', 'MMMM yyyy') }));
  const average = stars.length ? stars.reduce((a, b) => a + b, 0) / stars.length : 0;
  return json({ reviews: shown, count: stars.length, average: Math.round(average * 10) / 10 });
}

/** An email to the spreadsheet's owner for each portfolio message; Reply goes to the sender. */
function tellOwner(name, email, topic, message) {
  try {
    MailApp.sendEmail({
      to: Session.getEffectiveUser().getEmail(),
      replyTo: email,
      subject: 'Portfolio message from ' + name + (topic ? ' (' + topic + ')' : ''),
      body: message + '\n\n' + name + ' <' + email + '>\nSent from your portfolio. Reply to this email to answer.'
    });
  } catch (err) {
    // The message is still saved in the sheet.
  }
}

/** Text as typed, cut to a length, never read as a formula by the spreadsheet (=, +, -, @). */
function plain(value, max) {
  const text = String(value == null ? '' : value).trim().slice(0, max);
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
  } else if (sheet.getRange(1, spec.headings.length).getValue() === '') {
    // A sheet made by an earlier version: add the newer headings (e.g. "Show on site").
    sheet.getRange(1, 1, 1, spec.headings.length).setValues([spec.headings]).setFontWeight('bold');
  }
  return sheet;
}

function reply(ok, why) {
  return json(ok ? { ok: true } : { ok: false, error: why });
}

function json(body) {
  return ContentService.createTextOutput(JSON.stringify(body)).setMimeType(ContentService.MimeType.JSON);
}
