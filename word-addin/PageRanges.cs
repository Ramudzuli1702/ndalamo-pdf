using System;
using System.Collections.Generic;
using System.Linq;

namespace Ndalamo.WordAddIn
{
    /// <summary>
    /// Reads page lists such as "3, 5-7" (same rules as the PDF tools in the Ndalamo app):
    /// numbers and ranges separated by commas, counted from 1.
    /// </summary>
    public static class PageRanges
    {
        /// <summary>
        /// The pages listed, highest first (the order to delete them in, so the numbers of pages
        /// still to delete don't move). Throws FormatException with a message for the person.
        /// </summary>
        public static List<int> Parse(string text, int pageCount)
        {
            var pages = new SortedSet<int>();
            if (string.IsNullOrWhiteSpace(text)) throw new FormatException("Type the pages to delete, for example 3, 5-7.");
            foreach (string raw in text.Split(new[] { ',', ';' }, StringSplitOptions.RemoveEmptyEntries))
            {
                string part = raw.Trim().Replace('–', '-').Replace('—', '-');
                if (part.Length == 0) continue;
                int dash = part.IndexOf('-');
                int first, last;
                if (dash < 0)
                {
                    first = last = Number(part, raw);
                }
                else
                {
                    first = Number(part.Substring(0, dash), raw);
                    last = Number(part.Substring(dash + 1), raw);
                    if (last < first) throw new FormatException("\"" + raw.Trim() + "\" runs backwards. Write the lower page first, for example 5-7.");
                }
                if (first < 1 || last > pageCount)
                {
                    throw new FormatException("\"" + raw.Trim() + "\" is outside the document, which has "
                                              + pageCount + (pageCount == 1 ? " page." : " pages."));
                }
                for (int p = first; p <= last; p++) pages.Add(p);
            }
            if (pages.Count == 0) throw new FormatException("Type the pages to delete, for example 3, 5-7.");
            return pages.Reverse().ToList();
        }

        private static int Number(string text, string part)
        {
            if (int.TryParse(text.Trim(), out int n)) return n;
            throw new FormatException("\"" + part.Trim() + "\" isn't a page number or range. Use numbers like 3 or 5-7.");
        }
    }
}
