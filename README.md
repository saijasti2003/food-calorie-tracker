# Food Calorie Tracker (Android)

Take a photo of your food → get calories, protein, carbs and fat (estimated by Gemini) → saved as a row in your Google Sheet.

- `app/` – Android app (Java, no extra libraries)
- `apps-script/` – Google Apps Script backend (`Code.gs`, `Index.html`) that lives inside your sheet
- Every push to `main` builds the APK automatically. Download it from **Releases**.

## Setup
1. In your sheet: Extensions → Apps Script. Paste `apps-script/Code.gs` and add an HTML file `Index` with `apps-script/Index.html`.
2. Project Settings → Script properties: add `CLAUDE_API_KEY` (console.anthropic.com) and/or `GEMINI_API_KEY` (aistudio.google.com). Claude is used first when its key is set; Gemini is the backup and optionally `APP_TOKEN` (any password).
3. Deploy → New deployment → Web app → Execute as **Me**, Who has access **Anyone**. Copy the `/exec` URL.
4. Install the APK, open it, paste the URL (and APP_TOKEN) in Settings.
