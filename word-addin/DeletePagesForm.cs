using System;
using System.Drawing;
using System.Windows.Forms;

namespace Ndalamo.WordAddIn
{
    /// <summary>
    /// The "Delete Pages..." dialog: one box for a page list such as "3, 5-7". The list is checked
    /// here, so mistakes are shown before anything is deleted.
    /// </summary>
    public sealed class DeletePagesForm : Form
    {
        private readonly TextBox pagesBox = new TextBox();
        private readonly Label problem = new Label();
        private readonly int pageCount;

        public DeletePagesForm(int pageCount)
        {
            this.pageCount = pageCount;
            Text = "Delete Pages";
            Font = new Font("Segoe UI", 9.75f);
            FormBorderStyle = FormBorderStyle.FixedDialog;
            MaximizeBox = MinimizeBox = false;
            ShowInTaskbar = false;
            StartPosition = FormStartPosition.CenterParent;
            AutoScaleMode = AutoScaleMode.Font;
            ClientSize = new Size(380, 168);

            var label = new Label
            {
                Text = "Pages to delete (for example 3, 5-7). This document has "
                       + pageCount + (pageCount == 1 ? " page." : " pages."),
                Location = new Point(16, 14), Size = new Size(348, 40)
            };
            pagesBox.Location = new Point(16, 58);
            pagesBox.Size = new Size(348, 26);
            pagesBox.AccessibleName = "Pages to delete";
            problem.Location = new Point(16, 88);
            problem.Size = new Size(348, 36);
            problem.ForeColor = Color.FromArgb(0xB4, 0x23, 0x18);

            var delete = new Button { Text = "Delete", Location = new Point(196, 128), Size = new Size(80, 30) };
            var cancel = new Button { Text = "Cancel", Location = new Point(284, 128), Size = new Size(80, 30), DialogResult = DialogResult.Cancel };
            delete.Click += (sender, e) => Check();
            AcceptButton = delete;
            CancelButton = cancel;
            Controls.AddRange(new Control[] { label, pagesBox, problem, delete, cancel });
        }

        public string PagesText => pagesBox.Text;

        private void Check()
        {
            try
            {
                if (PageRanges.Parse(pagesBox.Text, pageCount).Count >= pageCount)
                {
                    problem.Text = "That's every page. Leave at least one page.";
                    return;
                }
                DialogResult = DialogResult.OK;
            }
            catch (FormatException e)
            {
                problem.Text = e.Message;
                pagesBox.Focus();
            }
        }
    }
}
