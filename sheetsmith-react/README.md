# SheetSmith — the UI

The React front end of [SheetSmith](../README.md): upload a spreadsheet, read the plan the model
proposed, edit or drop any step, apply it — and a chat panel beside it that answers questions about
the sheet and edits it turn by turn.

It is one screen. The grid, the plan cards and the chat all work on the same document, and every one
of them is driven by the backend rather than by anything kept here.

## Run

The app calls its API at a **relative** `/api/...` path, so it always talks to whatever origin
serves it. There is no host to configure in the source.

**In Docker (what users do):** `cd sheetsmith-java && docker compose up`, then open
<http://localhost:8080>. The image builds this UI and Spring Boot serves it — UI and API, one origin.

**In dev (what contributors do):**

```bash
cd sheetsmith-java && mvn spring-boot:run        # backend on :8080
cd sheetsmith-react && npm install && npm run dev # UI on :5173, /api proxied to :8080
```

The Vite dev server proxies `/api` to `http://localhost:8080`; point it elsewhere with
`VITE_API_PROXY_TARGET=http://host:port npm run dev`.

`npm run build` writes the bundle straight into `../sheetsmith-java/src/main/resources/static/`,
where Spring Boot picks it up — generated output, git-ignored, never committed. Run it once and
`mvn spring-boot:run` alone serves the real UI on :8080 too.

## Tests and linting

```bash
npm test          # Vitest, once
npm run test:watch
npm run lint      # ESLint, including the react-hooks rules
npm run format    # Prettier
```

There is no CI in this repository, on purpose — run both before you open a pull request.

The tests concentrate where a mistake is silent rather than loud: the A1 range parsing in
`parseSheet.js`, which decides whether a chart is drawn from the right cells; the hand-written SSE
reader in `chatApi.js`, whose fallback to a plain POST is deliberately quiet; and the plan cards,
which are the only review step between a model's idea and someone's spreadsheet.

Two lint warnings are left standing rather than suppressed, and are meant to stay visible. Three
`react-hooks` errors are suppressed line by line, each with a comment saying what the proper fix
would be — all three are in code written before those rules existed.

## What's inside

| File | Purpose |
|------|---------|
| `src/App.jsx` | The page: upload, instruction, plan review, apply, undo, and the session everything else shares. |
| `src/SheetGrid.jsx` | The spreadsheet preview — tabs per sheet, editable cells, and any charts the file holds. |
| `src/SuggestionsPanel.jsx` / `SuggestionCard.jsx` | The plan: one card per step, each editable or dismissable before anything runs. |
| `src/ChatPanel.jsx` | The chat, including the "how I got this" chain of steps under every answer. |
| `src/ChartPreview.jsx` | Draws bar, line and pie charts, either from the file's own definition or from the sheet's data. |
| `src/parseSheet.js` | Reads the workbook in the browser (SheetJS): cells, styles, formulas, column widths, A1 ranges. |
| `src/api.js` / `chatApi.js` / `settingsApi.js` | Every call to the backend, and nothing else. |
| `src/theme.js` | Light and dark token maps, applied as CSS custom properties. |

## Things worth knowing before changing it

- **Styling is inline plus CSS variables.** No framework. The token names in `theme.js` are the
  contract between the components.
- **State lives in `App.jsx`.** The session id, the parsed workbook, the plan and the pending manual
  edits are all held there and passed down, because all three panels act on one document.
- **The sentence on a plan card comes from the backend.** `describe()` in the engine writes it, so
  the same wording appears in the chat's step chain and in job history. Only the icon and the
  category are chosen here — and an action this file has never heard of still has to read as
  something a person recognises, never as a raw `COLOR_SCALE`.
- **Charts are resolved all-or-nothing.** If a range names a sheet that has been renamed away, the
  grid falls back to a chart synthesised from the sheet's own data **with a caption saying so**. A
  plausible chart of the wrong cells is worse than no chart.
- **The chat streams over SSE that this code parses by hand**, because `EventSource` cannot POST. If
  the stream never starts it falls back to a plain POST silently, which is why that path is tested.
- Fonts (Instrument Sans, JetBrains Mono) load from Google Fonts via `index.html`. Self-host them
  for a fully offline build.
