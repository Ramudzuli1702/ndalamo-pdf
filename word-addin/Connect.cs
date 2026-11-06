using System;
using System.Drawing;
using System.Drawing.Drawing2D;
using System.IO;
using System.Runtime.InteropServices;
using System.Windows.Forms;
using Microsoft.Office.Core;
using Word = Microsoft.Office.Interop.Word;

namespace Ndalamo.WordAddIn
{
    /// <summary>
    /// Word's interface for COM add-ins (from Office's extensibility.dll), declared here so the
    /// add-in needs no extra assembly. The GUID and member order must match the original.
    /// </summary>
    [ComImport, Guid("B65AD801-ABAF-11D0-BB8B-00A0C90F2744"), InterfaceType(ComInterfaceType.InterfaceIsDual)]
    public interface IDTExtensibility2
    {
        [DispId(1)] void OnConnection([MarshalAs(UnmanagedType.IDispatch)] object application, int connectMode,
            [MarshalAs(UnmanagedType.IDispatch)] object addInInst, ref Array custom);
        [DispId(2)] void OnDisconnection(int removeMode, ref Array custom);
        [DispId(3)] void OnAddInsUpdate(ref Array custom);
        [DispId(4)] void OnStartupComplete(ref Array custom);
        [DispId(5)] void OnBeginShutdown(ref Array custom);
    }

    /// <summary>
    /// The add-in itself: Word creates it at start-up (registered by the Ndalamo app, Settings →
    /// Word add-in), asks it for the ribbon, and calls the On… methods when a button is clicked.
    /// The work is done by <see cref="PageDeleter"/>.
    /// </summary>
    [ComVisible(true)]
    [Guid(ClassId)]
    [ProgId(ProgramId)]
    [ClassInterface(ClassInterfaceType.AutoDispatch)]
    public class Connect : IDTExtensibility2, IRibbonExtensibility
    {
        // Fixed for good: the Java app writes these into the registry when installing.
        public const string ClassId = "6E1F3C52-8D0B-4C7A-9E43-2B5D7A1C9F08";
        public const string ProgramId = "Ndalamo.WordAddIn";

        private Word.Application word;

        /// <summary>True once Word has accepted the ribbon (it calls onLoad only for valid ribbon XML).</summary>
        public bool RibbonLoaded { get; private set; }

        public void OnConnection(object application, int connectMode, object addInInst, ref Array custom)
        {
            word = (Word.Application)application;
            // Lets scripts reach this object through Word's COMAddIns (used to test the add-in in Word).
            ((COMAddIn)addInInst).Object = this;
        }

        public void OnDisconnection(int removeMode, ref Array custom)
        {
            word = null;
        }

        public void OnAddInsUpdate(ref Array custom) { }
        public void OnStartupComplete(ref Array custom) { }
        public void OnBeginShutdown(ref Array custom) { }

        public string GetCustomUI(string ribbonId)
        {
            using (Stream stream = typeof(Connect).Assembly.GetManifestResourceStream("Ndalamo.WordAddIn.Ribbon.xml"))
            using (var reader = new StreamReader(stream))
            {
                return reader.ReadToEnd();
            }
        }

        // ---- Ribbon callbacks (named in Ribbon.xml) ----

        public void OnRibbonLoad(IRibbonUI ribbon)
        {
            RibbonLoaded = true;
        }

        public void OnDeletePage(IRibbonControl control)
        {
            Run(() => DeletePage());
        }

        public void OnDeletePages(IRibbonControl control)
        {
            Run(() =>
            {
                Word.Document doc = word.ActiveDocument;
                int pages = PageDeleter.PageCount(doc);
                using (var form = new DeletePagesForm(pages))
                {
                    if (form.ShowDialog(new WordWindow(word)) == DialogResult.OK) DeletePages(form.PagesText);
                }
            });
        }

        public object GetImage(IRibbonControl control)
        {
            return PictureConverter.ToPicture(DrawIcon(control.Id == "NdalamoDeletePage" ? 32 : 16));
        }

        // ---- The actions, also callable from scripts (they return what happened, without showing a message) ----

        /// <summary>Deletes the page the cursor is on. Returns the message for the person, or "" when it went fine.</summary>
        public string DeletePage(bool askAboutTables = true)
        {
            return new PageDeleter(word, askAboutTables).DeleteCurrentPage();
        }

        /// <summary>Deletes pages such as "3, 5-7". Returns the message for the person, or "" when it went fine.</summary>
        public string DeletePages(string pages, bool askAboutTables = true)
        {
            return new PageDeleter(word, askAboutTables).DeletePages(pages);
        }

        /// <summary>Runs a button's action, showing any message it returns, so no error ever escapes into Word.</summary>
        private void Run(Func<string> action)
        {
            string message;
            try
            {
                if (word.Documents.Count == 0) message = "Open a document first, then click Delete Page again.";
                else message = action();
            }
            catch (COMException e)
            {
                message = "Word couldn't delete the page (" + e.Message.Trim() + "). Your document is unchanged; "
                          + "if it is protected or read-only, turn that off and try again.";
            }
            catch (Exception e)
            {
                message = "Something went wrong while deleting the page (" + e.Message + "). "
                          + "If part of the document changed, press Ctrl+Z to bring it back.";
            }
            if (!string.IsNullOrEmpty(message))
            {
                MessageBox.Show(new WordWindow(word), message, "Ndalamo", MessageBoxButtons.OK, MessageBoxIcon.Information);
            }
        }

        private void Run(Action action)
        {
            Run(() => { action(); return ""; });
        }

        /// <summary>The button picture: a page with a red cross, drawn here so no image files are needed.</summary>
        private static Bitmap DrawIcon(int size)
        {
            var bitmap = new Bitmap(size, size);
            float s = size / 32f;
            using (Graphics g = Graphics.FromImage(bitmap))
            using (var outline = new Pen(Color.FromArgb(0x3A, 0x3F, 0x4A), 2f * s))
            using (var cross = new Pen(Color.FromArgb(0xD1, 0x34, 0x38), 3f * s))
            {
                g.SmoothingMode = SmoothingMode.AntiAlias;
                g.Clear(Color.Transparent);
                outline.LineJoin = LineJoin.Round;
                var page = new[]
                {
                    new PointF(6 * s, 3 * s), new PointF(19 * s, 3 * s), new PointF(25 * s, 9 * s),
                    new PointF(25 * s, 29 * s), new PointF(6 * s, 29 * s)
                };
                g.FillPolygon(Brushes.White, page);
                g.DrawPolygon(outline, page);
                g.DrawLines(outline, new[] { new PointF(19 * s, 3 * s), new PointF(19 * s, 9 * s), new PointF(25 * s, 9 * s) });
                cross.StartCap = cross.EndCap = LineCap.Round;
                g.DrawLine(cross, 17 * s, 17 * s, 29 * s, 29 * s);
                g.DrawLine(cross, 29 * s, 17 * s, 17 * s, 29 * s);
            }
            return bitmap;
        }

        /// <summary>Word's window, so messages and the dialog open on top of Word rather than behind it.</summary>
        private sealed class WordWindow : IWin32Window
        {
            public WordWindow(Word.Application word)
            {
                try { Handle = new IntPtr(word.ActiveWindow.Hwnd); }
                catch (COMException) { Handle = IntPtr.Zero; }
            }

            public IntPtr Handle { get; }
        }

        /// <summary>The ribbon wants an OLE picture; this protected helper of AxHost makes one from a bitmap.</summary>
        private sealed class PictureConverter : AxHost
        {
            private PictureConverter() : base(string.Empty) { }

            public static object ToPicture(Image image) => GetIPictureDispFromPicture(image);
        }
    }
}
