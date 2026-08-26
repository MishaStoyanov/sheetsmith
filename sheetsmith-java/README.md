# XlsxAI — AI-powered Excel Improver

Upload an `.xlsx` file, describe what you want in plain text, and get back an improved file.  
The app uses an LLM to generate an action plan (charts, formulas, sorting, formatting, …) and applies it via Apache POI.

There are two ways to work with a sheet:

- **Improve** — one instruction, a reviewable plan, then apply. This is the main flow.
- **Chat** — a side panel, available as soon as a file is uploaded, that answers questions and edits
  the sheet turn by turn.

Both work on the same document. Uploading opens a **session**, whose working copy is an append-only
chain of revisions (`rev-0.xlsx`, `rev-1.xlsx`, …); a chat turn and an improve run each read the
current revision and commit their result as the next one. So the two flows never diverge, and undo
covers both.

### The model never reads your table

Both flows are built on the same idea: the LLM is never handed the spreadsheet. It receives the
*structure* (sheet names, headers, ranges) and then has to **ask for computations** — sum this column,
find these rows, evaluate this formula. Java runs them with Apache POI and hands back only the small
result. An answer like "Widget A sold most, 1 240 units" is produced from a `MAX` the engine ran, not
from a model that read 50 000 rows.

That also means every answer is auditable: each chat reply carries the chain of steps that produced
it, written in plain language.

---

## Quick Start (Docker)

**Prerequisites:** Docker + Docker Compose. Nothing else — Postgres, Ollama and the model all come
up with the app.

```bash
cd sheetsmith-java
cp .env.example .env
docker compose up --build
# then open http://localhost:8080
```

On a first run `ollama-init` pulls `llama3.1` (~4 GB) into a Docker volume and the app waits for it
to finish, so the first request never lands on a server with no model. Later starts reuse the
volume and come up in seconds. Change `OLLAMA_MODEL` in `.env` to pull something else.

The image builds the React UI and Spring Boot serves it, so http://localhost:8080 is the whole
app — UI and API on one origin.

### Already running Ollama yourself?

Skip the container and the download. In `.env`:

```bash
OLLAMA_BASE_URL=http://host.docker.internal:11434
OLLAMA_MODEL=<whatever you have pulled>
```

then start only the two services you need:

```bash
docker compose up postgres sheetsmith-app
```

### Working on the frontend

```bash
cd sheetsmith-java && mvn spring-boot:run      # API on :8080
cd sheetsmith-react  && npm install && npm run dev   # UI on :5173, /api proxied to :8080
```

---

## Switch LLM Provider

Edit `.env` and set `SPRING_PROFILES_ACTIVE`:

| Provider | Profile | Required env var |
|---|---|---|
| Ollama (default) | `ollama` | `OLLAMA_MODEL`, `OLLAMA_BASE_URL` |
| OpenAI | `openai` | `OPENAI_API_KEY`, `OPENAI_MODEL` |
| Anthropic | `anthropic` | `ANTHROPIC_API_KEY`, `ANTHROPIC_MODEL` |

Only one provider profile should be active at a time.

---

## Manual Setup (no Docker)

**Prerequisites:** Java 21, Maven 3.9+, PostgreSQL 14+, Ollama running locally

```bash
# 1. Pull a model in Ollama
ollama pull llama3.1

# 2. Create DB
createdb xlsxai

# 3. Configure (or export env vars)
export DB_HOST=localhost DB_USERNAME=postgres DB_PASSWORD=pass

# 4. Run
cd sheetsmith-java
mvn spring-boot:run
```

---

## Environment Variables

| Variable | Default | Description |
|---|---|---|
| `SPRING_PROFILES_ACTIVE` | `ollama` | LLM provider profile |
| `OLLAMA_BASE_URL` | `http://localhost:11434` | Ollama server URL |
| `OLLAMA_MODEL` | `llama3.1` | Model name |
| `OPENAI_API_KEY` | — | OpenAI key (openai profile) |
| `OPENAI_MODEL` | — | e.g. `gpt-4o` |
| `ANTHROPIC_API_KEY` | — | Anthropic key (anthropic profile) |
| `ANTHROPIC_MODEL` | — | e.g. `claude-sonnet-4-6` |
| `DB_HOST` | `localhost` | PostgreSQL host |
| `DB_PORT` | `5432` | PostgreSQL port |
| `DB_NAME` | `xlsxai` | Database name — still the old name, so an existing install keeps its data |
| `DB_USERNAME` | `postgres` | DB user |
| `DB_PASSWORD` | `pass` | DB password |
| `SHEETSMITH_UPLOAD_DIR` | `./uploads` | Input file storage |
| `SHEETSMITH_RESULT_DIR` | `./results` | Result file storage |
| `SHEETSMITH_SESSION_DIR` | `./sessions` | Chat working copies (one directory per session) |
| `SHEETSMITH_TTL_DAYS` | `7` | Auto-delete jobs and idle chat sessions older than N days |
| `MAX_CONCURRENT_JOBS` | `1` | Parallel job limit |
| `SHEETSMITH_MAX_AUTOSIZE_CELLS` | `500000` | Cells one `AUTOSIZE_COLUMNS` step may measure |
| `SHEETSMITH_CHAT_ENABLED` | `true` | `false` removes the chat entirely — see [Running without the chat](#running-without-the-chat) |
| `SHEETSMITH_CHAT_MAX_STEPS` | `8` | Tool calls the chat may make before it must answer |
| `SHEETSMITH_CHAT_MAX_CELLS` | `300` | Cell cap for a single range read |
| `SHEETSMITH_CHAT_MAX_ROWS` | `50` | Row cap for any query result |
| `SHEETSMITH_CHAT_HISTORY` | `12` | Previous messages replayed to the model |
| `SHEETSMITH_CHAT_STREAM_TIMEOUT_MS` | `600000` | How long `/messages/stream` holds the connection open |
| `SHEETSMITH_ALLOWED_ORIGINS` | localhost:5173, localhost:8080 | Browser origins allowed to call the API |
| `SHEETSMITH_PATH_ENDPOINT_ENABLED` | `false` | Enables `POST /api/excel/improve/path` |
| `SHEETSMITH_PATH_ENDPOINT_ROOTS` | — | Directories that endpoint may read and write |

---

## Running without the chat

For a deployment where the chat is not wanted and the privacy promise has to be checkable rather
than trusted:

```bash
SHEETSMITH_CHAT_ENABLED=false
```

The instance becomes the improve flow alone, and the only thing that can reach the model is the
sheet's **structure**: sheet names, column headers, ranges, and the text of formulas already in the
file. No cell from a data row is sent, by any path.

That is enforced by removing the parts that could, not by not calling them:

| Gone | Why it could send data |
|---|---|
| `POST /api/chat/sessions/{id}/messages` and `/messages/stream` | the chat turn itself |
| `ChatAgentService`, `ChatToolRegistry` | run the tools and talk to the model |
| `READ_RANGE`, `AGGREGATE`, `FIND_ROWS`, `DESCRIBE_COLUMN`, `EVAL_FORMULA` | the tools that read cells |
| `POST /api/excel/suggest` | inspects real values to ground its suggestions |
| the label beside an existing formula | read out of a cell, so not structure |

None of those beans exist in the application context — `ChatDisabledTest` asserts their absence, and
`SchemaExtractorServiceTest` asserts that the payload handed to the planner contains no value from a
data row. The rest of `/api/chat/sessions` stays: despite the path it is the shared document
workspace — upload, revisions, hand-edits, undo — and the improve flow is built on it.

The UI reads `GET /api/capabilities` on load and hides what the server has not got, so nothing is
offered that would fail.

The cost is small and worth stating: a follow-up improve round has to tell two totals apart by their
formulas rather than by the label beside them, and "What would you improve?" is unavailable, since
it works by measuring the data.

---

## Security model

This is a self-hosted tool with **no authentication**: it is meant to run on your own machine or
inside a trusted network. Two defaults exist to keep that honest.

**Browser origins are allowlisted.** In the normal setup this never comes up — the app serves the UI
and the API from the same origin, and `npm run dev` proxies `/api`, so nothing is cross-origin. It
matters the moment someone hosts the UI separately: without auth, any website you have open could
otherwise drive your instance — upload and download spreadsheets, delete history, or overwrite the
LLM settings, which hold your cloud API keys. Only the origins in `SHEETSMITH_ALLOWED_ORIGINS` are
accepted. Serving the UI from another host? Add that origin.

**The server-side path endpoint is off.** `POST /api/excel/improve/path` reads and writes paths on
the server, which is useful for scripting and dangerous when exposed. It is disabled unless
`SHEETSMITH_PATH_ENDPOINT_ENABLED=true`, and when enabled both paths must resolve — symlinks followed —
inside one of `SHEETSMITH_PATH_ENDPOINT_ROOTS`. Enabling it without roots refuses to start.

If you put this on a network anyone else can reach, put an authenticating reverse proxy in front of
it. Nothing here is a substitute for that.

---

## API Reference

The UI flow is plan → review → apply, and it runs against a session (open one with
`POST /api/chat/sessions`, see [Chat API](#chat-api) — the same session backs both flows).

### Plan against a session
```
POST /api/excel/plan
Content-Type: application/json

{ "sessionId": "9f2c…", "instruction": "Add a bar chart for columns A-C and bold the header row" }
```
Response:
```json
{
  "planToken": "1a4e…",
  "steps": [
    { "index": 0, "type": "CREATE_CHART", "properties": { "range": "A1:C20", "chartType": "BAR" },
      "description": "Create a bar chart from A1:C20" }
  ]
}
```
The plan is built from the session's **current revision**; nothing is written yet. `planToken` is
single-use and expires when the app restarts.

### Ask what to improve

```
POST /api/excel/suggest
Content-Type: application/json

{ "sessionId": "9f2c…" }
```
Same response as `/plan` — a `planToken` and reviewable steps — for a user with nothing to type yet.
The sheet is inspected first by a read-only agent pass that runs the chat's query tools over the real
data, so the suggestions name actual ranges and findings rather than restating the column headers.
That pass cannot change anything: a mutating tool is refused mid-turn, not after the fact.

### Apply a plan
```
POST /api/excel/apply
Content-Type: application/json

{ "planToken": "1a4e…", "steps": [ … possibly edited or filtered steps … ] }
```
Response: `{ "jobId": 42 }` (202 Accepted — poll the job below)

The job reads the session's current revision and commits its result as the **next** revision, so the
chat sees it immediately and `POST /api/chat/sessions/{id}/revert` undoes it like any turn.

### Submit a job directly (scripting)
```
POST /api/excel/improve
Content-Type: multipart/form-data

file        = <xlsx file>
instruction = "Add a bar chart for columns A-C and bold the header row"
```
Response: `{ "jobId": 42 }`

This entry point and the one below are for automation: they pass files around and own no session, so
their results are not part of any revision chain.

### Submit by file path
```
POST /api/excel/improve/path
Content-Type: application/json

{ "inputPath": "/data/sales.xlsx", "outputPath": "/data/sales_out.xlsx", "instruction": "..." }
```

### Re-narrate edited steps
```
POST /api/excel/describe
Content-Type: application/json

{ "steps": [ { "index": 0, "type": "SORT_DATA", "properties": { "range": "A2:D99", "columnIndex": 2 } } ] }
```
Returns the same steps with a `description` filled in — `"Sort A2:D99 by column C, lowest first"`.
The review cards call this after the user edits a range, so a card never describes a range the step
no longer targets.

### Poll status
```
GET /api/history/{id}
```
Statuses: `PROCESSING` → `COMPLETED` | `PARTIAL` | `FAILED`

The detail view carries `appliedActions`: `[{ type, description, success, errorMessage }]`, where
`description` is the same plain-language rendering the plan cards and the chat use.

### Download result
```
GET /api/history/{id}/download
```

### List history
```
GET /api/history?page=0&size=20&sort=createdAt,desc
```

### Delete job
```
DELETE /api/history/{id}
```
Deletes the record. Its input and result files go too — unless they are revisions of a session, in
which case they stay: they belong to that session's undo history, and one of them is very likely the
sheet the user is looking at.

---

## Chat API

A session owns a private working copy of the uploaded file, kept as an append-only chain of
revisions (`rev-0.xlsx`, `rev-1.xlsx`, …). Nothing touches the uploaded file, and any step can be
undone. Despite living under `/api/chat`, the session is the workspace for **both** flows — improve
jobs commit revisions into the same chain — so `revert` undoes an improve run as readily as a chat
turn, and `GET /{id}/file` is always the current sheet.

### Open a session
```
POST /api/chat/sessions
Content-Type: multipart/form-data

file = <xlsx file>
```
Response:
```json
{
  "sessionId": "…",
  "filename": "sales.xlsx",
  "revision": 0,
  "sheets": ["Sales"],
  "charts": [
    {
      "sheetName": "Sales", "sheetIndex": 0, "chartIndex": 0,
      "type": "bar", "title": "Revenue by product",
      "axes": ["category axis", "value axis"],
      "series": [
        { "name": "Revenue",
          "categoriesRef": "Sales!$A$2:$A$4",
          "valuesRef": "Sales!$B$2:$B$4" }
      ]
    }
  ]
}
```

`charts` is every chart actually in the current revision — one that arrived inside the upload as
much as one `CREATE_CHART` just drew. The browser parses the workbook with SheetJS, which cannot
see embedded charts at all, so this is the only way the preview knows a chart exists, what kind it
is, and which cells it plots. `type` is `bar` | `pie` | `line` | `unknown`, and the series ranges
are A1 formulas the frontend resolves against the sheet it already parsed. `GET
/api/chat/sessions/{id}` returns the same shape for the current revision, so a chart added by an
improve run or a chat turn — or taken away by an undo — shows up through the refetch the UI already
does. A chart POI cannot fully read is left out rather than guessed at.

### Send a message
```
POST /api/chat/sessions/{sessionId}/messages
Content-Type: application/json

{ "text": "which product sold most?" }
```
Response:
```json
{
  "message": {
    "id": 12,
    "role": "ASSISTANT",
    "content": "Widget A sold most — 1240 units.",
    "steps": [
      { "order": 0, "tool": "EVAL_FORMULA", "text": "Evaluated =MAX(C2:C20)",
        "resultPreview": "1240", "success": true, "mutating": false }
    ],
    "revisionAfter": null
  },
  "mutated": false,
  "revision": 0
}
```
`mutated: true` means the working copy changed — re-fetch the file. `revisionAfter` on a message is
what you pass (minus one) to undo that turn.

### Send a message, streamed

A turn is a chain of tool calls and can run for a minute against a local model. Same request body,
same final result — but the steps arrive as they happen instead of all at once at the end.

```
POST /api/chat/sessions/{sessionId}/messages/stream
Content-Type: application/json
Accept: text/event-stream

{ "text": "sort by revenue and total column C" }
```

Three named events:

```
event:step
data:{"order":0,"tool":"SORT_DATA","text":"Sorted A2:D20 by column C, highest first",
      "resultPreview":"applied","success":true,"error":null,"mutating":true}

event:done
data:{ "message": { … }, "mutated": true, "revision": 4 }

event:error
data:{"message":"Chat failed: model unreachable"}
```

- `step` — one per tool call, the moment it finishes, failures included. `order` is chronological;
  the chain in the final message may file the self-check one place earlier, since it cannot report
  an outcome until the repair calls it triggered have run. `args` is filled in only on the final
  message.
- `done` — the complete `ChatTurnDto`, identical to what `POST /messages` returns.
- `error` — the turn threw; the stream then closes.

The connection always closes after `done` or `error`. `EventSource` cannot be used to consume this
(it cannot POST) — read the response body and split the frames, as `sheetsmith-react/src/chatApi.js`
does. `POST /messages` is unchanged and remains the right call for scripts.

### Other endpoints
```
GET    /api/chat/sessions/{id}            session state (same body as opening one, current revision)
GET    /api/chat/sessions/{id}/messages   full history with step chains
GET    /api/chat/sessions/{id}/file       current working copy (.xlsx)
POST   /api/chat/sessions/{id}/edits      manual grid edits → one revision (see below)
POST   /api/chat/sessions/{id}/revert     { "revision": 3 } — undo, append-only
DELETE /api/chat/sessions/{id}
```

### Manual edits

Cells the user types into the preview grid are committed the same way everything else is — as one
revision, undoable, and visible to both flows:

```
POST /api/chat/sessions/{id}/edits
{ "cells": [ { "sheetIndex": 0, "row": 3, "column": 2, "value": "42" } ],
  "sheetRenames": { "0": "Q3" } }
→ { "revision": 4 }
```

A value that parses as a number becomes a numeric cell, anything else text, and a blank value clears
the cell — replacing any formula it held. The UI flushes pending edits before anything that reads the
sheet server-side (analyse, follow-up, a chat message, export), so the assistant never answers about
a sheet the user can see but the server cannot.

---

## Supported Actions

Actions **change** the sheet. They are shared: the improve flow and the chat run the same handlers.

| Action | Description |
|---|---|
| `FORMAT_CELLS` | Background, font colour, bold |
| `CREATE_CHART` | Bar and pie charts |
| `ADD_SHEET` | Add a new worksheet |
| `ADD_FORMULA` | Insert an Excel formula with a label |
| `SORT_DATA` | Sort a range by column |
| `FILTER_DATA` | Apply auto-filter |
| `CONDITIONAL_FORMATTING` | Highlight cells by rule |
| `MERGE_CELLS` | Merge a cell range |
| `CLEAR_CELLS` | Wipe value, formula and formatting |
| `RENAME_SHEET` | Rename a worksheet |
| `RENAME_COLUMN` | Rename a column header |
| `RENAME_CHART_TITLE` | Retitle an existing chart |
| `RENAME_CHART_AXIS` | Label a chart's x/y axis |
| `SET_CELL_VALUE` | Write a literal value into a cell or a small range |
| `AUTOSIZE_COLUMNS` | Widen columns to fit their contents |
| `FREEZE_PANES` | Keep header rows and left columns in view while scrolling |
| `NUMBER_FORMAT` | Currency, percent, thousands, dates — how numbers read |
| `SET_BORDERS` | Lines around or between cells |
| `ALIGN_CELLS` | Where a value sits in its cell, and whether it wraps |
| `INSERT_ROWS` / `DELETE_ROWS` | Make room or close a gap, rows below moving with it |
| `INSERT_COLUMNS` / `DELETE_COLUMNS` | The same sideways, by column letter |
| `FILL_FORMULA` | One formula down a column, references moving per row |
| `ADD_TOTALS_ROW` | A whole totals row under the data, in one step |
| `REMOVE_DUPLICATES` | Keep the first of each repeated row |
| `DELETE_SHEET` | Remove a whole sheet (never the last one) |
| `UNMERGE_CELLS` | Split merged blocks back apart |
| `DATA_VALIDATION` | Constrain what may be typed — dropdowns, ranges |
| `CREATE_TABLE` | A real Excel table, not a range that looks like one |
| `COLOR_SCALE` | Shade a range by value — low to high, no threshold needed |
| `GROUP_ROWS` | Fold rows into an outline under the total that summarises them |
| `PAGE_SETUP` | Orientation, fit-to-page, print area, repeating headings |
| `HYPERLINK` | Make a cell — or a whole column of addresses — clickable |
| `COMMENT` | Pin a note to a cell, or take one off |
| `PROTECT_SHEET` | Read-only except the cells you name |
| `LOOKUP_FROM_SHEET` | Pull a column across from another sheet, matched by key |
| `GROUP_BY` | One row per distinct value, with the numbers beside it totalled |
| `SPARKLINES` | A whole chart inside one cell, for the shape of a row |
| `DATA_BARS` | A bar inside each cell, in proportion to its value |
| `TRANSFORM_COLUMN` | Rewrite every value in a column by a named rule |

### Writing values (`SET_CELL_VALUE`)

The only action that can put a value into an **empty** cell — "write Q1 2026 in A1". It writes one
literal into one cell, or the same literal across a small range, and leaves the cell's existing
formatting alone.

Its one surprising rule protects your data: a *quoted* number is only stored as a number when it
renders back to exactly what was sent, so `"007"`, `"1.50"` and `"42.0"` stay text — a part number
with leading zeros survives as a part number. Dates are never guessed, either: they need
`valueType: "date"` and ISO form (`2026-01-31`), because a misread `01/02/2026` is silent corruption.

### Styling (`FORMAT_CELLS`, `NUMBER_FORMAT`, `SET_BORDERS`, `ALIGN_CELLS`)

Four actions share one rule: each **edits** a cell's existing style instead of replacing it. So a
header can be coloured, number-formatted, bordered and centred by four steps in any order, and none
of them undoes another. (Before this, colouring a column silently wiped the number format it had.)

`NUMBER_FORMAT` changes how a number *reads* — `1234.5` shown as `$1,234.50` — while leaving it a
number the sheet can still add up. It takes a name (`currency`, `percent`, `thousands`, `date`,
`text`, …) or a literal Excel pattern like `#,##0.00`, and the pattern is checked before it is
written, because Excel refuses to open a file with a malformed one. A value stored as *text* ignores
every number format there is, so the step counts those cells and tells you, rather than reporting a
success you cannot see.

`SET_BORDERS` names sides of the **range**, not of every cell: over a five-row block `bottom` is one
line under the block. Sides combine — `outline,bottom` boxes a header and rules it off in one step.

`ALIGN_CELLS` also returns any fixed row height in the range to automatic when you turn wrapping on,
because Excel clips wrapped text in a row whose height was pinned by hand instead of growing it.

### Inserting and deleting (`INSERT_ROWS`, `DELETE_ROWS`, `INSERT_COLUMNS`, `DELETE_COLUMNS`)

The only actions that move cells other than the ones they were pointed at, which makes their real
risk a formula somewhere else in the workbook. Formulas are rewritten for you — on the sheet being
changed **and on every other sheet that referenced it**, so a total on a summary sheet keeps
totalling — and merged regions move with their cells.

Two things do not follow, and both are reported rather than hidden:

- A formula pointing *into* deleted rows or columns becomes an error. The step names those cells in
  its result ("2 formulas now show an error (Summary!B4, …)"), which is also what the chat reads
  before deciding what to do next.
- **A chart is not repointed.** Its ranges live outside the formula table, so a chart drawn over
  cells you then move keeps naming the old ones. Redraw it with `CREATE_CHART`.

Inserts arrive empty and unstyled, deliberately: Excel's own insert copies the row above, which in a
plan means a blank data row silently inheriting the header's fill. Deleting past the end of the
sheet removes what is actually there and says how much, but a deletion that names no target at all
is refused — that is the one mistake here you cannot undo from inside the step.

### Whole-table steps (`FILL_FORMULA`, `ADD_TOTALS_ROW`, `REMOVE_DUPLICATES`)

Three actions that each replace a plan's worth of smaller ones — and the reason is the review card
as much as the token count: "Fill D2:D500 with =B2\*C2, adjusted for each row" is one idea a person
can approve, where five hundred `ADD_FORMULA` steps are not.

`FILL_FORMULA` is Excel's fill handle. The top cell of the range is the source, and every cell below
gets the formula with its **relative** references moved — `B2*C2` becomes `B3*C3` in row 3, while
`$F$1` stays put. That is done by parsing the formula to tokens and shifting them the way Excel does
when it copies a cell, not by rewriting text, so `SUM(B2:B4)` moves as a range and a `B2` inside a
quoted string stays a string.

`ADD_TOTALS_ROW` writes the label and every total in one step, in bold, under the range. It totals
only columns that actually hold numbers and names the ones it skipped: a `SUM` over a column of
names is `0`, which is not an error — just a confident lie sitting under the data.

`REMOVE_DUPLICATES` keeps the **first** of each repeated row and removes the rest, taking whole rows
so nothing is left misaligned against the columns beside it. Rows are compared by what they
*display* — a formula by its result, text case-insensitively, so `ACME` and `Acme` are the same
customer — and `columns` narrows that to the ones that define identity.

### Closing the asymmetries (`DELETE_SHEET`, `UNMERGE_CELLS`)

`ADD_SHEET` had no delete and `MERGE_CELLS` had no unmerge — gaps a demo user hits in five minutes.

`DELETE_SHEET` is the most destructive action in the engine, so it is the one that guesses least:
every other action falls back to the first sheet when told nothing, which is a convenience there and
a way to lose a workbook's front page here, so a name is required. The last sheet cannot go — Excel
refuses to open a workbook with none. Formulas on other sheets that read from it are **named before
it goes**, because they will show `#REF!` once it has.

`UNMERGE_CELLS` splits every merge its range touches, or all of them when given no range. It is the
repair step for a sheet that arrived merged: `SET_CELL_VALUE` cannot write into the cells a merge
swallows and `AUTOSIZE_COLUMNS` ignores merged regions when measuring, so "unmerge first" is often
the fix. Each value stays in its region's top-left cell, which is where it already lived.

### Making a cleanup last (`DATA_VALIDATION`, `CREATE_TABLE`)

The only two actions that change a sheet's **future** rather than its contents.

`DATA_VALIDATION` puts a dropdown on a column, or bounds a number, date or text length. It is what
makes a cleanup stick: tidying a status column achieves nothing if the next person types "Compleet"
into it. It constrains what is typed *from now on* — Excel never checks what is already there, and
the step says so rather than letting you assume otherwise. `strict: false` warns instead of refusing,
which is what a sheet that already breaks its own new rule needs.

One limit is Excel's, not ours: an explicit list of options is stored as a single 255-character
string, so a longer list has to live in cells and be pointed at with `sourceRange`. Asking for one
too long is refused with that fix, rather than producing a file Excel offers to repair.

`CREATE_TABLE` makes a real table — banded rows, filter arrows, and a name, so a formula can say
`Sales[Amount]` instead of `B2:B500`, and so the table grows as rows are added. Excel is strict about
one thing here: every column needs a heading and no two may match. Blanks are filled and repeats
numbered rather than writing a file that opens with a repair prompt, and the step reports whatever it
had to change.

### Column rules (`TRANSFORM_COLUMN`)

`FORMAT_CELLS` changes how cells *look*; `SET_CELL_VALUE` writes *one* value; `TRANSFORM_COLUMN`
changes what a whole column *says*. The model picks
a rule by name and the values never leave the server, so a 35 000-row column costs no more prompt
than a 3-row one. Values a rule cannot convert are left exactly as they were and reported back —
"34,857 values changed, 143 left as they were".

| Rule | Extra keys | What it does |
|---|---|---|
| `PHONE_US` | — | Any US spelling → `+1 (555) 123-4567` |
| `TRIM` | — | Strips and collapses whitespace, in-cell line breaks included |
| `UPPER` / `LOWER` | — | Case, e.g. for email columns |
| `TITLE_CASE` | — | `JOANIE CASPER` → `Joanie Casper`, keeping `O'Reilly` and `Jean-Luc` |
| `DIGITS_ONLY` | — | Keeps only the digits |
| `REPLACE` | `find`, `replace` | Literal find-and-replace |
| `REGEX_REPLACE` | `pattern`, `replacement` | Escape hatch; prefer a named rule |
| `SPLIT_TAKE` | `separator`, `index` | Keeps one piece, e.g. the street line before a `\n` |
| `PAD_LEFT` | `length`, `fill` | Puts back leading zeros Excel dropped |
| `TO_NUMBER` | — | Text like `$1,234.56` becomes a real number |

Add a rule by dropping a `ColumnTransform` bean in `services/excel/transform/` — it documents itself
into both system prompts.

## Query Tools (chat only)

Queries **read** the sheet and return a small result to the model. This is how the chat answers
questions without the model ever seeing the data.

| Tool | Description |
|---|---|
| `READ_RANGE` | Read a bounded range of cells |
| `AGGREGATE` | Sum / avg / min / max / count / median, optionally grouped |
| `FIND_ROWS` | Filter, sort and return matching rows |
| `DESCRIBE_COLUMN` | Type, blanks, distinct count, range, samples |
| `EVAL_FORMULA` | Evaluate any Excel formula and return its value |

All actions and query tools support `sheetIndex` / `sheetName` for multi-sheet files.

---

## Release

Push a tag to trigger a GitHub Actions build that publishes a JAR to GitHub Releases:

```bash
git tag v1.0.0
git push origin v1.0.0
```
