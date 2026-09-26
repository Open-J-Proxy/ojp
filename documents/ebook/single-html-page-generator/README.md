# Single HTML Page Generator

This folder contains the assets used to render the OJP e-book as one standalone HTML page.

## What is here

- `ebook-single-page.html` — the renderer page
- `vendor/` — local runtime libraries (`marked`, `DOMPurify`, `Mermaid`)

## How to use

Preferred option: serve this folder with a local/static web server, then open:

- `http://localhost:8000/documents/ebook/single-html-page-generator/ebook-single-page.html`

The page dynamically fetches each chapter from `../*.md` at render time (no preloaded content fallback).

Quick local server example from repository root:

```bash
cd /home/runner/work/ojp/ojp
python -m http.server 8000
```

## Direct `file://` open

If you open `ebook-single-page.html` directly (without a web server), some browsers block `fetch` for local files.

In this case:
1. Click **Select ebook folder** in the status banner.
2. Choose the `documents/ebook` folder.
3. The page loads markdown files directly from your selected folder (still dynamic, no bundled fallback file).
