# Single HTML Page Generator

This folder contains the assets used to render the OJP e-book as one standalone HTML page.

## What is here

- `ebook-single-page.html` — the renderer page
- `ebook-single-page-content.js` — bundled markdown payload for offline/file:// usage
- `vendor/` — local runtime libraries (`marked`, `DOMPurify`, `Mermaid`)

## How to use

Open this file in a browser:

- `/home/runner/work/ojp/ojp/documents/ebook/single-html-page-generator/ebook-single-page.html`

It works in two modes:

1. **Bundled mode (default)**: reads from `ebook-single-page-content.js` (works with `file://`)
2. **Fallback mode**: fetches `../*.md` files if an entry is missing from the bundle

## Regenerate bundled content

When ebook markdown files change, regenerate `ebook-single-page-content.js` with:

```bash
python - <<'PY'
import json, pathlib
root = pathlib.Path('/home/runner/work/ojp/ojp/documents/ebook')
files = [
'part1-chapter1-introduction.md','part1-chapter2-architecture.md','part1-chapter2a-smart-load-balancing.md','part1-chapter3-quickstart.md','part1-chapter3a-kubernetes-helm.md','part2-chapter4-database-drivers.md','part2-chapter5-jdbc-configuration.md','part2-chapter6-server-configuration.md','part2-chapter7-framework-integration.md','part3-chapter8-slow-query-segregation.md','part3-chapter8a-client-throttling.md','part3-chapter9-multinode-deployment.md','part3-chapter10-xa-transactions.md','part3-chapter11-security.md','part3-chapter12-pool-provider-spi.md','part3-chapter12a-query-result-caching.md','part4-chapter13-telemetry.md','part4-chapter13a-running-ojp-server-in-production.md','part4-chapter14-protocol.md','part5-chapter15-dev-setup.md','part5-chapter16-contributing-workflow.md','part5-chapter17-testing-code-quality.md','part5-chapter18-contributor-recognition.md','part6-chapter19-implementation-analysis.md','part6-chapter20-lessons-learned.md','part7-chapter21-vision-future.md','part7-chapter22-performance-engineering.md','appendix-a-command-reference.md','appendix-b-database-guides.md','appendix-c-glossary.md','appendix-d-resources.md','appendix-e-jdbc-compatibility.md','appendix-f-visual-assets.md','appendix-g-troubleshooting.md'
]
content = {f: (root / f).read_text(encoding='utf-8') for f in files}
out = root / 'single-html-page-generator' / 'ebook-single-page-content.js'
out.write_text('window.EBOOK_MARKDOWN = ' + json.dumps(content, ensure_ascii=False) + ';\n', encoding='utf-8')
PY
```
