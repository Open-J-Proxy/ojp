# Single HTML Page Generator

This folder contains the assets used to render the OJP e-book as one standalone HTML page.

## What is here

- `ebook-single-page.html` — the renderer page
- `vendor/` — local runtime libraries (`marked`, `DOMPurify`, `Mermaid`)

## How to use

Serve this folder with a local/static web server, then open:

- `http://localhost:8000/documents/ebook/single-html-page-generator/ebook-single-page.html`

The page dynamically fetches each chapter from `../*.md` at render time (no preloaded content fallback).

Quick local server example from repository root:

```bash
cd /home/runner/work/ojp/ojp
python -m http.server 8000
```
