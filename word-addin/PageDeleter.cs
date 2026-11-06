using System;
using System.Collections.Generic;
using System.Linq;
using System.Runtime.InteropServices;
using System.Windows.Forms;
using Word = Microsoft.Office.Interop.Word;

namespace Ndalamo.WordAddIn
{
    /// <summary>
    /// Deletes pages from the open Word document. A .docx has no fixed pages, so this asks Word
    /// where each page starts and ends as it is laid out right now, and deletes that stretch
    /// (SPEC section 7). Every action is one undo step, so a single Ctrl+Z brings it all back.
    /// Methods return a message for the person, or "" when there is nothing to say.
    /// </summary>
    public sealed class PageDeleter
    {
        private readonly Word.Application word;
        private readonly bool askAboutTables;
        private Word.Document doc;

        /// <param name="askAboutTables">false skips the "table carries on" question and goes ahead (for scripts)</param>
        public PageDeleter(Word.Application word, bool askAboutTables)
        {
            this.word = word;
            this.askAboutTables = askAboutTables;
        }

        public static int PageCount(Word.Document doc)
        {
            return doc.ComputeStatistics(Word.WdStatistic.wdStatisticPages);
        }

        public string DeleteCurrentPage()
        {
            doc = word.ActiveDocument;
            string problem = CannotEdit();
            if (problem != null) return problem;
            int total = PageCount(doc);
            if (total <= 1)
            {
                return "This is the only page, so it wasn't deleted. To empty it instead, "
                       + "select everything (Ctrl+A) and press Delete.";
            }
            Word.Range cursor = word.Selection.Range;
            cursor.Collapse(Word.WdCollapseDirection.wdCollapseStart);
            int page = PageInView.CurrentPage(word, cursor);
            if (!TablesOkay(new[] { page }, total)) return "";
            return InOneUndoStep("Delete Page", () => { Delete(page); return ""; });
        }

        public string DeletePages(string text)
        {
            doc = word.ActiveDocument;
            string problem = CannotEdit();
            if (problem != null) return problem;
            int total = PageCount(doc);
            List<int> pages;
            try
            {
                pages = PageRanges.Parse(text, total);
            }
            catch (FormatException e)
            {
                return e.Message;
            }
            if (pages.Count >= total) return "That's every page in the document, so nothing was deleted. Leave at least one page.";
            if (!TablesOkay(pages, total)) return "";
            // Highest first: deleting a page never moves the pages before it.
            return InOneUndoStep("Delete Pages", () =>
            {
                foreach (int page in pages) Delete(page);
                return "";
            });
        }

        private string CannotEdit()
        {
            if (doc.ProtectionType != Word.WdProtectionType.wdNoProtection)
            {
                return "This document is protected against changes, so no pages were deleted. "
                       + "Turn off protection (Review > Restrict Editing) and try again.";
            }
            return null;
        }

        private string InOneUndoStep(string name, Func<string> work)
        {
            Word.UndoRecord undo = word.UndoRecord;
            undo.StartCustomRecord(name);
            bool updating = word.ScreenUpdating;
            word.ScreenUpdating = false;
            try
            {
                string message = work();
                if (message == "" && doc.TrackRevisions)
                {
                    message = "Track Changes is on, so the page is marked as deleted rather than removed. "
                              + "Accept the change to remove it, or press Ctrl+Z to undo.";
                }
                return message;
            }
            finally
            {
                word.ScreenUpdating = updating;
                undo.EndCustomRecord();
            }
        }

        /// <summary>
        /// The stretch of the document on a page: from where the page starts to where the next
        /// one starts (or the end of the document for the last page).
        /// </summary>
        private Word.Range PageRange(int page, int total)
        {
            int start = page == 1 ? 0 : doc.GoTo(Word.WdGoToItem.wdGoToPage, Word.WdGoToDirection.wdGoToAbsolute, page).Start;
            int end = page < total
                ? doc.GoTo(Word.WdGoToItem.wdGoToPage, Word.WdGoToDirection.wdGoToAbsolute, page + 1).Start
                : doc.Content.End;
            return doc.Range(start, end);
        }

        private void Delete(int page)
        {
            int total = PageCount(doc);
            Word.Range range = PageRange(page, total);
            if (page == total)
            {
                DeleteLastPage(range, total);
                return;
            }
            // A section break holds the layout (margins, orientation, headers) of the section it ends.
            // If the section began on an earlier page, keep the break, or those pages would take the
            // next section's layout.
            if (EndsWithSectionBreak(range) && !StartsSection(range)) range = doc.Range(range.Start, range.End - 1);
            DeleteKeepingTables(range);
        }

        /// <summary>
        /// Word always keeps the document's final paragraph mark, so deleting the last page's text can
        /// leave an empty page behind (a page break or empty paragraph from the page before). Take
        /// those away too, until the page count drops. A page break left dangling at the very end
        /// goes as well, even when Word doesn't count a page for it.
        /// </summary>
        private void DeleteLastPage(Word.Range range, int total)
        {
            if (range.Start > 0 && doc.Sections.Count > 1 && doc.Sections.Last.Range.Start == range.Start)
            {
                // The page is a section of its own (say, a landscape page at the end). Its section break
                // must go too, or an empty page stays. Once the break is gone the section before takes
                // this section's layout and headers, so first make them the same as the section before's.
                // (Word ignores layout changes to a section once it's empty, so this has to come first.)
                Word.Section own = doc.Sections.Last;
                own.PageSetup = doc.Sections[doc.Sections.Count - 1].PageSetup;
                foreach (Word.HeaderFooter header in own.Headers) header.LinkToPrevious = true;
                foreach (Word.HeaderFooter footer in own.Footers) footer.LinkToPrevious = true;
                range = doc.Range(range.Start - 1, range.End);
            }
            DeleteKeepingTables(range);
            for (int guard = 0; guard < 8; guard++)
            {
                bool pageLeft = PageCount(doc) >= total;
                Word.Range last = doc.Paragraphs.Last.Range;
                string text = last.Text ?? "";
                if (text.Trim('\r', '\f', '\v', ' ', '\t').Length > 0) break;   // real content: leave it
                bool onlyBreaks = text.Length > 1;
                if (!pageLeft && !(onlyBreaks && text.Contains('\f'))) break;
                if (onlyBreaks)
                {
                    // Breaks or spaces before the final mark.
                    doc.Range(last.Start, last.End - 1).Delete();
                    continue;
                }
                if (last.Start == 0) break;
                string before = doc.Range(last.Start - 1, last.Start).Text;
                // Never join across a section break: that would change the layout of the section before.
                if (before == "\f") break;
                // Join the empty final paragraph to the one before. The joined paragraph keeps the final
                // mark's formatting, so put the earlier paragraph's style and spacing back.
                Word.Paragraph previous = doc.Paragraphs[doc.Paragraphs.Count - 1];
                object style = previous.get_Style();
                Word.ParagraphFormat format = previous.Format.Duplicate;
                doc.Range(last.Start - 1, last.Start).Delete();
                Word.Paragraph joined = doc.Paragraphs.Last;
                if (style != null) joined.set_Style(ref style);
                joined.Format = format;
            }
        }

        private bool EndsWithSectionBreak(Word.Range range)
        {
            if (range.End <= range.Start || doc.Range(range.End - 1, range.End).Text != "\f") return false;
            Word.Section section = doc.Range(range.End - 1, range.End - 1).Sections[1];
            return section.Range.End == range.End;
        }

        private bool StartsSection(Word.Range range)
        {
            return doc.Range(range.Start, range.Start).Sections[1].Range.Start == range.Start;
        }

        /// <summary>Tables on the page that carry on before or after it.</summary>
        private static List<Word.Table> SharedTables(Word.Range range)
        {
            var shared = new List<Word.Table>();
            foreach (Word.Table table in range.Tables)
            {
                if (table.Range.Start < range.Start || table.Range.End > range.End) shared.Add(table);
            }
            return shared;
        }

        /// <summary>Asks once if any of the pages shares a table with another page. False: don't delete.</summary>
        private bool TablesOkay(IEnumerable<int> pages, int total)
        {
            if (!askAboutTables) return true;
            List<int> sharing = pages.Where(p => SharedTables(PageRange(p, total)).Count > 0).OrderBy(p => p).ToList();
            if (sharing.Count == 0) return true;
            string which = sharing.Count == 1 ? "Page " + sharing[0] + " has" : "Pages " + string.Join(", ", sharing) + " have";
            string message = which + " part of a table that carries on to another page.\n\n"
                             + "Delete anyway? Only the table rows on the page are removed; the rest of the table stays. "
                             + "Ctrl+Z brings everything back.";
            return MessageBox.Show(message, "Ndalamo", MessageBoxButtons.YesNo, MessageBoxIcon.Question) == DialogResult.Yes;
        }

        /// <summary>
        /// Deletes the stretch, except that a table carrying on past the page loses only its rows on
        /// the page (deleting part of a table as text would empty cells and leave the rows).
        /// Works from the end backwards so earlier positions stay put.
        /// </summary>
        private void DeleteKeepingTables(Word.Range range)
        {
            List<Word.Table> shared = SharedTables(range);
            if (shared.Count == 0)
            {
                range.Delete();
                return;
            }
            int start = range.Start, end = range.End;
            Word.Table after = shared.FirstOrDefault(t => t.Range.End > end);
            Word.Table before = shared.FirstOrDefault(t => t.Range.Start < start);
            if (after != null)
            {
                int afterStart = after.Range.Start;
                DeleteRows(after, start, end);
                if (afterStart <= start) return;   // the whole page is inside the one table
                end = afterStart;
            }
            if (before != null)
            {
                int beforeEnd = before.Range.End;
                if (end > beforeEnd) doc.Range(beforeEnd, end).Delete();
                DeleteRows(before, start, beforeEnd);
            }
            else if (end > start)
            {
                doc.Range(start, end).Delete();
            }
        }

        private static void DeleteRows(Word.Table table, int from, int to)
        {
            try
            {
                for (int i = table.Rows.Count; i >= 1; i--)
                {
                    Word.Row row = table.Rows[i];
                    int rowStart = row.Range.Start;
                    if (rowStart >= from && rowStart < to) row.Delete();
                }
            }
            catch (COMException)
            {
                // Word can't reach single rows when cells are merged down across rows.
                throw new InvalidOperationException("a table on the page has cells merged across rows, so its rows "
                                                    + "couldn't be removed one by one. Delete those rows yourself");
            }
        }
    }
}
