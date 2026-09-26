# Single HTML Page Generator

This folder contains the assets used to render the OJP e-book as one standalone HTML page.

## What is here

- `ebook-single-page.html` — the renderer page
- `vendor/` — local runtime libraries (`marked`, `DOMPurify`, `Mermaid`)

## How to use

Open this file from a local/static web server (recommended) or any hosted environment:

- `/home/runner/work/ojp/ojp/documents/ebook/single-html-page-generator/ebook-single-page.html`

The page fetches each chapter from `../*.md` at render time, so it always reflects current chapter content.
