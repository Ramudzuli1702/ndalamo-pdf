# Where "Tell us what you think" sends reviews

Reviews from the app arrive as rows in a Google Sheet, through a small Apps Script attached to it
(`Code.gs`, decision 020). The app knows where to send them from the `endpoint=` line in
`src/main/resources/feedback.properties`. While that line is empty, the app hides
"Tell us what you think" and never asks.

## Setting it up (once, about 5 minutes)

1. In Google Drive, create a new blank spreadsheet, for example "Ndalamo reviews".
2. In it, open **Extensions › Apps Script**. Delete what's in the editor, paste in all of
   `Code.gs`, and save (Ctrl+S).
3. Click **Deploy › New deployment**. Next to "Select type", click the gear and choose
   **Web app**. Set:
   - Description: `Ndalamo reviews`
   - Execute as: **Me**
   - Who has access: **Anyone** (the app sends reviews without signing in)
4. Click **Deploy**, allow the access Google asks for (it's your own script writing to your own
   sheet), and copy the **Web app URL** (it ends in `/exec`).
5. Put that URL after `endpoint=` in `src/main/resources/feedback.properties` and rebuild.

The first review creates a "Reviews" sheet with the headings Received, Stars, Review, Version,
Written and ID.

## Good to know

- The URL gives no access to your Google account: it can only add a review row to this one
  spreadsheet. It can't read the sheet, reach your other files or email, or reveal your address.
- `@OnlyCurrentDoc` at the top of `Code.gs` limits the script's permission to this spreadsheet.
- Don't give anyone edit access to the spreadsheet or the script: whoever can edit the script can
  change what runs as you.
- Anyone who finds the URL could post to it. The script accepts only a 1-5 rating and short
  text, so the worst case is a junk row you can delete.
- If you change `Code.gs` later, use **Deploy › Manage deployments › Edit › New version**, so the
  URL stays the same and installed copies keep working.
- To stop collecting reviews, archive the deployment in **Manage deployments**. Installed copies
  then keep reviews waiting on the PC; nothing breaks.
