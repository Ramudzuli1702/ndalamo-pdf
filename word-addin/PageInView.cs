using System;
using System.Runtime.InteropServices;
using Word = Microsoft.Office.Interop.Word;

namespace Ndalamo.WordAddIn
{
    /// <summary>
    /// Works out which page the person means by "this page". Word's status bar shows the page
    /// scrolled into view, not the page with the cursor, so after scrolling the cursor can be
    /// pages away from what's on screen. Rule: if the cursor is on screen, its page; otherwise
    /// the page in the middle of the window.
    /// </summary>
    internal static class PageInView
    {
        public static int CurrentPage(Word.Application word, Word.Range cursor)
        {
            int cursorPage = (int)cursor.Information[Word.WdInformation.wdActiveEndPageNumber];
            try
            {
                Word.Window window = word.ActiveWindow;
                // Only Print Layout shows pages; elsewhere the cursor is all there is to go on.
                if (window.View.Type != Word.WdViewType.wdPrintView) return cursorPage;
                if (!DocumentArea(new IntPtr(window.Hwnd), out Rect area)) return cursorPage;
                if (IsOnScreen(window, cursor, area)) return cursorPage;
                // The middle of the window can fall in the grey gap between pages; try a little above and below too.
                int x = (area.Left + area.Right) / 2;
                int height = area.Bottom - area.Top;
                foreach (double share in new[] { 0.5, 0.35, 0.65, 0.2, 0.8 })
                {
                    int y = area.Top + (int)(height * share);
                    if (window.RangeFromPoint(x, y) is Word.Range hit)
                    {
                        return (int)hit.Information[Word.WdInformation.wdActiveEndPageNumber];
                    }
                }
            }
            catch (COMException)
            {
                // Word couldn't say where things are on screen; fall back to the cursor.
            }
            return cursorPage;
        }

        private static bool IsOnScreen(Word.Window window, Word.Range cursor, Rect area)
        {
            try
            {
                window.GetPoint(out int left, out int top, out int width, out int height, cursor);
                return left >= area.Left && left <= area.Right && top >= area.Top && top + Math.Max(height, 1) <= area.Bottom;
            }
            catch (COMException)
            {
                return false;   // Word says it can't place a range that's scrolled out of view
            }
        }

        /// <summary>The screen rectangle of the document itself (Word's "_WwG" pane), without ribbon and rulers.</summary>
        private static bool DocumentArea(IntPtr wordWindow, out Rect area)
        {
            area = default;
            IntPtr pane = Find(Find(Find(wordWindow, "_WwF"), "_WwB"), "_WwG");
            return pane != IntPtr.Zero && GetWindowRect(pane, out area) && area.Right > area.Left && area.Bottom > area.Top;
        }

        private static IntPtr Find(IntPtr parent, string className)
        {
            return parent == IntPtr.Zero ? IntPtr.Zero : FindWindowEx(parent, IntPtr.Zero, className, null);
        }

        [StructLayout(LayoutKind.Sequential)]
        private struct Rect
        {
            public int Left, Top, Right, Bottom;
        }

        [DllImport("user32.dll", CharSet = CharSet.Unicode)]
        private static extern IntPtr FindWindowEx(IntPtr parent, IntPtr after, string className, string windowName);

        [DllImport("user32.dll")]
        [return: MarshalAs(UnmanagedType.Bool)]
        private static extern bool GetWindowRect(IntPtr window, out Rect rect);
    }
}
