# ARCHITECTURE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Commands

All commands run from the `sheetsmith-java/` directory.

```bash
# Build
mvn clean package -DskipTests

# Run tests
mvn test

# Run single test class
mvn test -Dtest=CreateTestData

# Run application (Ollama is default profile)
mvn spring-boot:run

# Run with a specific LLM profile
mvn spring-boot:run -Dspring-boot.run.profiles=anthropic
mvn spring-boot:run -Dspring-boot.run.profiles=openai
```

The UI has its own suite: `npm test` (Vitest + Testing Library, jsdom) and `npm run lint` (ESLint
with the react-hooks rules) from `sheetsmith-react/`. No CI runs either — both are run by hand.

The UI lives in the sibling `sheetsmith-react/` (Vite + React). `npm run build` there writes into
this module's `src/main/resources/static/`, which Spring Boot serves — that output is generated and
gitignored. For frontend work run `npm run dev` (port 5173, `/api` proxied to 8080) alongside
`mvn spring-boot:run`. The Docker image builds both: its context is the repository root, not this
module.

## Architecture

The app accepts a natural-language instruction + an `.xlsx` file, asks an LLM to produce a JSON action plan, then applies that plan to the file using Apache POI. Jobs are tracked in PostgreSQL.

### One document, two flows

The sheet has exactly one home: the session's revision chain (`sessions/{id}/rev-N.xlsx`,
append-only). The improve flow and the chat both read the current revision and commit their result as
the next one, so neither can leave the other behind and undo covers both. `DocumentSession` is
therefore the **shared document workspace, not a chat-only class** — that is why `JobService`
depends on `DocumentSessionService`, and why both live outside `services/chat` and
`domain/dto/chat`. They were called `ChatSession*` until the chat's name stopped being true of them.

The HTTP paths did **not** move with the class: uploads still open a workspace at
`POST /api/chat/sessions`. A URL is somebody's script, and renaming it is a decision about the
public surface rather than about honest naming inside the code.

### Request flow — the UI flow, session-backed

```
POST /api/chat/sessions               — upload opens the session, copy → sessions/{id}/rev-0.xlsx

POST /api/excel/plan  {sessionId, instruction}
  → JobService.generatePlan()
      → reads the session's CURRENT revision (no lock: revisions are immutable once written)
      → SchemaExtractorService.extract() → AiPlanningService.generatePlan()
      → parks a PendingPlan (sessionId, filename, instruction, usage) under a planToken
  ← { planToken, steps[] }              — steps carry an imperative description for the review cards

POST /api/excel/apply {planToken, steps}
  → JobService.applyPlan()
      → saves JobRecord (status=PROCESSING)
      → Thread.ofVirtual() → processSessionJob()
          → jobSemaphore.acquire()      — then SessionLockRegistry.acquire(); never the reverse
          → input  = DocumentSessionService.currentPath(session)
            result = DocumentSessionService.nextRevisionPath(session)
          → ExcelAutomationService.applyChanges()  (+ one fixPlan retry if everything failed)
          → finalizes JobRecord (COMPLETED / PARTIAL / FAILED)
          → unless FAILED: DocumentSessionService.commitExternalRevision() — the pointer moves
  ← returns { jobId }
```

`JobRecord.inputFilePath` / `resultFilePath` are those two revision files, so job history and
`GET /api/history/{id}/download` work unchanged — but the files are owned by the session (see
*History & file lifecycle*).

**What a run cost.** The planning call is paid for in `/plan`, minutes before `/apply` creates the
record it belongs to, so the cost rides along in the parked `PendingPlan` and is written the moment
the record exists. A `fixPlan` retry spends again during apply and is added, not substituted —
`recordUsage()` saves after each call rather than at the end, so a run that spent tokens and then
threw still says so. `TokenUsage` reads Spring AI's `EmptyUsage` (three zeros) as *not reported*
and stores null: a local model that bills nothing must not read as a run that cost nothing. Ollama
does report — measured 17/2/19 on a one-word prompt — so a null column means the provider stayed
silent, not that the model was local. Chat turns are outside this: they write `chat_steps`, not job
records.

**Which engine answered** rides along the same way (`LlmEngine`, V5 and V6): `provider_mode`
(`LOCAL`/`CLOUD`), `provider` (`OLLAMA`, `OPENAI`, `GEMINI`, …) and `model`, read from the settings
the call is *about* to use rather than from the settings as they stand now — a user may switch
providers between two runs, or between `/plan` and `/apply` of one run. Tokens without this cannot
be priced: the same count is a rounding error locally and a bill in the cloud, and a price list is
keyed on the model name.

The vendor is its own column rather than something read off the model name, because "gemini-3.7-flash"
implies Google only by convention and a spend-by-vendor chart built on that convention breaks
silently at the next rename. A local run records `OLLAMA` rather than null, so the column answers for
every row and the chart gets a labelled slice for local work instead of a gap.

When a `fixPlan` retry genuinely lands on another model, the run stays attributed to the model that
**planned** it and the mismatch is logged — one column cannot hold two answers, and silently
overwriting the attribution would be the wrong one.

**`llm_usage` is where spend actually lives** (V9). One row per *call*, not per run or per turn: a
repaired run makes two calls and a chat turn makes one per step, so summing rows is the whole of
"how much was spent" with no special cases. Both flows write through `UsageRecorder`, which is the
entire point — chat previously recorded nothing at all, so anybody editing more by conversation than
by the improve flow would have seen a chart showing a fraction of the truth and looking perfectly
plausible while doing it.

Three things about it. The planning call is recorded in `/plan` with no job attached, because the
money is spent there and a plan the user reads and walks away from cost exactly as much as one they
applied. What was *done* is not copied in — the steps stay in `action_results` and `chat_steps` and
the row points at the run or session, since a second copy could only drift. And recording runs in
its own transaction inside a try/catch: by then the work has happened and the money is gone, so
failing the turn over the bookkeeping would trade a lost row for a lost answer.

Chat sessions gained an owner in the same migration. A session is always opened on the request
thread, so unlike a job there is nothing to carry across a virtual thread — the caller is simply
readable at the moment the document is opened, and the turn reads it from the session afterwards.

### Request flow — the scripting entry points

`POST /api/excel/improve` (multipart) and `POST /api/excel/improve/path` are unchanged and own no
session: they save an upload to `uploads/`, write to `results/`, and pass files around. They exist
for automation, not for the UI, and share the same `runPlan()` core.

### Key abstractions

**`ActionHandler` interface** (`services/excel/ActionHandler.java`) — every Excel operation is a Spring bean implementing `getType()` (returns the action key string), `execute(XSSFWorkbook, Map<String, Object> properties)`, and `describe(properties, tense)` (a plain-language line for the UI).

`execute` returns a **detail string or null**. Null means `describe()` already said everything; a string is appended to the step's description in job history, in the chat's step chain, and in the trace the model reads next. An action that can succeed over only part of its input has to use it — `TRANSFORM_COLUMN` reports "34,857 values changed, 143 left as they were" — because without that channel a three-quarters-converted column comes back as an unqualified success.

**`ActionRegistry`** (`services/excel/ActionRegistry.java`) auto-discovers those beans and is the single lookup point — used by `ExcelAutomationService`, the chat's `ChatToolRegistry`, and `JobService` when narrating plan cards.

The count is deliberately not written down here. `ActionWiringTest` derives it by comparing the
registry against the catalog's own index, so the two check each other and no third copy can drift;
the only prose number left in the repository is the one in the root README, and CONTRIBUTING lists
bumping it as part of adding an action.

To add a new action: create a `@Component` class implementing `ActionHandler`, return the new type string from `getType()`, override `describe()`, and add the type + its keys to **both** `ActionCatalog.MUTATING_ACTIONS` and `ActionCatalog.MUTATING_ACTIONS_INDEX_LIST`. That catalog feeds both the one-shot planner and the chat agent, so the action becomes available in both flows at once.

Both tiers, not one: the chat opens every turn with the compact index and escalates to the full catalog only when it reaches for a mutating tool, so an action present in `MUTATING_ACTIONS` but missing from the index is **invisible to the chat** and will never be chosen. Mind the numbering too — `TRANSFORM_COLUMN` is numbered by hand in its own `TRANSFORM_COLUMN_TEMPLATE` and appended *after* `MUTATING_ACTIONS` by `ActionCatalogPrompt`, so a new entry at the end of that constant collides with it unless its number moves. `ActionCatalogPromptTest` asserts the composed prompt numbers 1..N consecutively, which is what catches this.

**`ColumnTransform`** (`services/excel/transform/`) — the rules `TRANSFORM_COLUMN` dispatches to, and the one action whose vocabulary grows without `ActionCatalog` being edited. A rule is a `@Component` with `getType()`, `apply(value, options)` returning `Optional<String>`, `promptSpec()` and `describe()`; `ColumnTransformRegistry` discovers it and `ActionCatalogPrompt` renders the live list into both system prompts — which is why those two callers inject `ActionCatalogPrompt` rather than reading the `ActionCatalog` constants directly.

An empty `Optional` means *this rule cannot convert this value*: the cell is left exactly as it was and counted as skipped. That distinction is the whole contract — a value the rule does not understand must survive untouched and be reported, never blanked or guessed at. Rules producing a real number rather than text (`TO_NUMBER`) say so by overriding `numeric()`.

Named rules are deliberately preferred over `REGEX_REPLACE`: the plan card is the only review step between a bad rule and 35 000 rewritten cells, and "Rewrite C2:C35001 as +1 (XXX) XXX-XXXX phone numbers" can be checked by a human where a regex cannot. Small local models also pick a name far more reliably than they author a pattern.

`describe()` must never throw — the LLM produces missing keys and wrong types, and the string it returns is user-facing. Shared formatting helpers live in `services/excel/actions/ActionDescriptions`. It is tense-aware because the same sentence serves a proposal (plan card: "Sort A2:D20 by column C") and a record (chat chain / job history: "Sorted A2:D20 by column C").

**`ActionStep`** uses `@JsonAnySetter` — the LLM JSON is flat (`{ "type": "...", "range": "...", ... }`), `type` is extracted, everything else lands in `properties: Map<String, Object>` which is passed directly to the handler.

**`AutomationRequest`** accepts `"steps"`, `"actions"`, or `"commands"` as the array key (via `@JsonAlias`) to tolerate LLM output variation.

**`AiPlanningService`** wraps Spring AI `ChatClient` with a strict system prompt (composed from `ActionCatalog`) and strips markdown/comments from the raw response before JSON parsing. `fixPlan()` re-invokes with the error summary appended, giving one automatic retry.

### Chat subsystem

The chat is a second consumer of the same engine, added so users can ask questions and make edits
turn by turn. Its defining constraint: **the LLM never receives the sheet**, only the structure plus
the result of each tool it explicitly runs.

```
POST /api/chat/sessions              — copies the upload to sessions/{id}/rev-0.xlsx
  ← DocumentSessionDto { sessionId, filename, revision, sheets, charts }   — charts: see below
POST /api/chat/sessions/{id}/messages
  → ChatAgentService.send()          — SessionLockRegistry, one workbook open for the turn
      loop (max sheetsmith.chat.max-steps):
        ChatLlmService.decide()      — one JSON object: {"tool",…} or {"answer": …}
        ChatToolRegistry.invoke()    — QueryTool (read) or ActionHandler (write)
        result appended to the trace fed back to the model
      → budget spent? one final call forcing {"answer"}
      → any mutation? DocumentSessionService.commitRevision() writes rev-N+1
  ← ChatTurnDto { message + step chain, mutated, revision }

POST /api/chat/sessions/{id}/messages/stream    — the same turn, narrated (SseEmitter)
  → ChatAgentService.send(id, text, listener)   on Thread.ofVirtual()
  ← event:step  per tool call, as it finishes    { order, tool, text, resultPreview, … }
    event:done  the complete ChatTurnDto         — byte-identical to the sync endpoint's body
    event:error { message }                      — then the emitter completes
```

**Streaming is observation, not a second code path.** `TurnListener` is a one-method callback the
agent fires as each `ToolInvocation` lands — failures and the self-heal pass included — and
`send(id, text)` passes `TurnListener.NOOP`, so the synchronous endpoint runs exactly the loop it
always ran. That matters: the two endpoints must never be able to disagree about what a turn did.
The listener's `order` is chronological, while the persisted chain files the self-check ahead of the
repair calls it triggered (their outcome is what the self-check reports), so those two orderings
differ by one position on a self-healing turn — `ChatStepDto.live()` vs `ChatStepDto.from()`.

A listener that throws is swallowed: by the time a browser drops the stream the turn is mid-edit
holding the session lock, and aborting it would leave half a change. `sheetsmith.chat.stream-timeout-ms`
(default 10 min) is deliberately far above any real turn — a timeout here kills work the user is
still waiting for.

The frontend hand-parses the SSE frames (`chatApi.js` `streamChatMessage`) because `EventSource`
cannot POST, and falls back to the plain `POST /messages` **silently** if the stream never starts.

**`DocumentSessionDto` carries the sheet's charts.** The browser parses the workbook with SheetJS, which
does not read embedded charts at all — so without this the preview could only *synthesise* a chart
from whatever numeric columns it found, and would show a made-up bar chart to a user who has a pie.
`SchemaExtractorService.extractCharts()` therefore returns structured `ChartDefinitionDto`s — type
(`bar`/`pie`/`line`/`unknown`), title, host sheet, and each series' category/values range as an A1
formula — feeding both the planner prompt (via `toPromptLine()`, the wording
`RENAME_CHART_TITLE`/`RENAME_CHART_AXIS` have always relied on) and the DTO. It is read from the
**current revision**, so `hasChart` in the UI is derived, never latched: an improve run or a chat
turn adds a chart and an undo takes it away, all through the refetch the frontend already does.
Charts come from files we did not write, so extraction is defensive — an unreadable chart is skipped,
never allowed to fail the whole schema. POI exposes no getter for a series title, only the
type-specific `CT*Ser`, which is why `seriesName()` switches on the series type.

The frontend resolves those A1 ranges against the sheets it already parsed (`parseSheet.js`
`parseA1Range` / `resolveRange` / `resolveChartDefinition`) and hands the result to `ChartPreview`
with its real type and title. Resolution is all-or-nothing: if a range names a sheet that is gone,
`SheetGrid` falls back to the synthesised `chartData` **with a caption saying so**, because a
plausible-looking chart of the wrong cells is worse than no chart.

**`ManualEditService`** — cells the user types into the preview grid go through the same chain
(`POST /api/chat/sessions/{id}/edits`), committed as one revision under the session lock. They used
to live only in the browser and were dropped by the next refresh; the UI flushes them before
anything that reads the sheet server-side.

**`QueryTool`** (`services/excel/query/`) — the read-only counterpart to `ActionHandler`: `promptSpec()`
documents itself into the chat system prompt, `execute()` returns a small `QueryResult`, `describe()`
narrates it. `READ_RANGE`, `AGGREGATE`, `FIND_ROWS`, `DESCRIBE_COLUMN`, `EVAL_FORMULA`. Adding a bean
is enough — the registry picks it up and the prompt grows a numbered entry.

`EVAL_FORMULA` writes to a scratch row and removes it again: query tools must leave the workbook
byte-identical, because the same in-memory workbook is written to disk if an action also ran.

**`ChatLlmService`** uses a hand-rolled JSON protocol rather than provider-native function calling,
because the app must behave identically against small local Ollama models. Its parser is deliberately
tolerant (fenced JSON, `action` for `tool`, args splashed at the root) — see `ChatLlmServiceParseTest`.

**Prompt budget.** Every step re-sends the whole system prompt, so the tool catalog is two-tier:
`toolCatalogPrompt(false)` (compact action index + full query specs) opens each turn, and the full
editing rules arrive only when the model first reaches for a mutating tool — at which point that step
is re-asked with the complete rules before anything touches the sheet. Most turns are questions and
never pay for the mutation catalog. That escalation point is also where the formula self-check takes
its "before" snapshot.

**The tiering is a trade, and which way it pays depends on the provider.** A cloud model bills per
token, so the compact index wins. A local one re-reads the prompt every step but caches the KV
prefix, and swapping the *system* prompt mid-turn invalidates that cache and re-processes everything
— on top of the extra round trip the restatement costs. `sheetsmith.chat.full-catalog-always` (default
false) sends the full rules from step one instead, keeping one stable prefix for the whole turn.

Whichever way that flag is set, the escalation pass still runs exactly once per editing turn:
`escalateToEditing` returns whether the prompt actually grew (and hence whether the step needs
re-asking), but it always takes the error baseline. Skipping the pass would leave `errorsBefore`
null and silently disable the self-check.

**`DocumentSessionService`** owns the working copy — for the improve flow as much as for the chat.
Revisions are append-only, so undo copies an old revision forward rather than deleting anything.
Two ways in, and nothing else works out a revision number:

- `commitRevision(session, workbook)` — the chat hands over an in-memory workbook to be written.
- `nextRevisionPath(session)` + `commitExternalRevision(session, note)` — the improve job writes the
  file itself with POI and then only moves the pointer. The note lands in the transcript, so the
  chat knows the sheet changed under it.

There is no `/sync` endpoint any more: the browser used to POST an improve result back into the
session, which made it the sync point between two server-side truths. Both writers now share one.

### LLM profiles

Three Spring profiles, each activating a different Spring AI provider:

| Profile | Provider | Key env var |
|---|---|---|
| `ollama` (default) | Ollama local | `OLLAMA_BASE_URL`, `OLLAMA_MODEL` |
| `openai` | OpenAI | `OPENAI_API_KEY`, `OPENAI_MODEL` |
| `anthropic` | Anthropic | `ANTHROPIC_API_KEY`, `ANTHROPIC_MODEL` |

Only one profile is active at a time, and each names its provider with Spring AI's own
`spring.ai.model.chat` switch. That replaced a list of auto-configuration class names to exclude,
which was silently wrong the moment those classes moved package between `1.0.0-M6` and `1.0.0`: the
excludes stopped matching, every provider's chat model tried to start, and the ones without an API
key brought the context down. The base `application.yaml` also sets every *other* model kind
(`embedding`, `image`, `moderation`, `audio.*`) to `none` — a provider starter auto-configures those
too, and each insists on a key at startup even though this app only ever uses chat.

### Storage & concurrency

- Files are written to `./sessions/{sessionId}/` (the working copies both flows use) and — for the scripting endpoints only — `./uploads` (input) and `./results` (output), configurable via `sheetsmith.storage.*` or env vars `SHEETSMITH_UPLOAD_DIR` / `SHEETSMITH_RESULT_DIR` / `SHEETSMITH_SESSION_DIR`.
- Those two are only the *starting* values. `StorageSettingsService` holds the instance's own answer in a one-row `storage_settings` table (superadmin only), and `FileStorageService` asks it on every write rather than reading a path at startup — a value held from startup would keep writing to the old folder until somebody restarted the server. `uploadDir()`/`resultDir()` fall back to the configuration when nothing has been chosen, which is the state a fresh instance is in.
- Chat budgets live under `sheetsmith.chat.*` (`max-steps`, `max-cells`, `max-rows`, `history-messages`, `repair-steps`, `full-catalog-always`) — they exist to keep a turn bounded and tool results small.
- `sheetsmith.processing.max-autosize-cells` (default 500 000, override with `SHEETSMITH_MAX_AUTOSIZE_CELLS`) caps the cells (columns × rows) one `AUTOSIZE_COLUMNS` step may measure — roughly five seconds, which on a 50 000-row sheet is about ten columns rather than the whole sheet. Past the budget the step sizes what it can afford and reports the remainder as skipped; it deliberately does **not** refuse, because the budget is per step and a model told to narrow its range just issues two steps and does the same total work. It sits under `processing` rather than `chat` because actions run in **both** flows, and a budget only the chat honoured would leave the improve flow unbounded.
- The `ollama` profile pins `num-ctx` explicitly. Left unset, Ollama sizes the KV cache for the model's full trained context and the allocation simply fails on a consumer card — it is a startup error, not a slow path. `num-predict`, `temperature` and `keep-alive` are set there for the same "local model, JSON answers" reason.
- `SessionLockRegistry` holds one lock per session and is taken by **both** writers of a revision chain: `ChatAgentService.send()` for the whole turn, and `JobService.processSessionJob()` around the whole read-modify-write (on its virtual thread, not the HTTP request). Without it a turn and a job would both derive the same "next revision" and one edit would vanish.
- **Lock order: `jobSemaphore` first, then the session lock.** A job holding a slot may wait for a session; a job holding a session must never wait for a slot, or two jobs on one session deadlock. Chat turns take the session lock only and never the semaphore.

### Turning the chat off

`sheetsmith.chat.enabled=false` (env `SHEETSMITH_CHAT_ENABLED`) makes an improve-only instance whose promise
is that nothing but sheet structure reaches the model. It is implemented as **absence**, not as
guards: `@ConditionalOnChatEnabled` (a meta-annotation over `@ConditionalOnProperty`) is on
`ChatAgentService`, `ChatToolRegistry`, `SuggestionService`, all five `QueryTool` beans and
`ChatMessageController`, so none of them is in the context. An unreachable code path is one refactor
away from being reachable again; a missing bean is not.

Three things about the shape of this, because each was a trap:

- **`/api/chat/sessions` is not the chat.** It is the shared document workspace the improve flow
  uploads into and reads revisions from (`DocumentSessionService`), so it must keep working. Only the two
  model-facing endpoints were split out into `ChatMessageController`; the rest stayed in
  `ChatController`.
- **`POST /api/excel/suggest` is chat machinery living in the improve controller.** It calls
  `ChatAgentService.inspect()`, which runs query tools over real values. `ExcelController` therefore
  injects `ObjectProvider<SuggestionService>` and answers with a clear error when it is absent —
  without that, the whole controller would fail to start.
- **`SchemaExtractorService.adjacentLabel()` is the one place the ordinary improve prompt carries a
  cell value** — the text beside a formula, used to tell "Total" from "Average" on a follow-up round.
  It is suppressed when the flag is off.

`GET /api/capabilities` reports `chatEnabled` / `suggestionsEnabled` / `sendsOnlyStructure` so the UI
can hide what is not there. It is deliberately not part of `/api/settings`, which is user-editable —
a feature flag that looks settable invites a PUT that appears to turn the chat back on.

### Security posture

**Two shapes, one switch.** `sheetsmith.auth.enabled` (env `SHEETSMITH_AUTH_ENABLED`) defaults to
**false**: solo on your own machine, a login screen is a cost with no matching risk, and an upgrade
must not put one in front of an instance that never had one. On, `/api/**` needs a token. Both
shapes are built in `configs/SecurityConfig.filterChain` rather than one being guards inside the
other, so what is allowed through is readable in one place. The flag is reported by
`/api/capabilities` — never by `/api/settings`, because a security switch that travels with editable
settings invites a PUT that appears to turn it off.

**The chain names every path and denies the rest.** It used to end at `/api/** → authenticated`,
leaving the actual rule to whether somebody had remembered a `@PreAuthorize` on the service method —
default-allow, and five endpoints had been added without one (the LLM settings and their stored API
keys, writing and patching prices, and by omission the whole session surface). The chain now carries
every path-only rule and ends at `.requestMatchers("/api/**").denyAll()`, so an endpoint added
without a rule answers 403 the first time it is called. Path rules go through `AuthorizationManager`
lambdas backed by the same `Authz` bean the annotations use, because roles are not in the token and
`hasRole` has nothing to read.

`configs/SecurityProperties` is the `sheetsmith.security` properties holder (CORS allowlist, by-path
endpoint); it was called `SecurityConfig` until the filter chain needed that name.

**Three roles, and the rule that keeps them inert.** `USER` cannot touch other accounts; `ADMIN`
manages *ordinary users* and may hand out `ADMIN` but never take it back; `SUPERADMIN` is the seeded
account — the one that already cannot be deleted — and is the only role that can demote, delete, or
reach the instance's own configuration (model settings and keys, storage, prices).
`Authz.mayManage(targetRole)` is the half that was missing: `requireAdmin` asked only whether the
caller was an administrator and never who they were pointing at, so an administrator could reset the
superadmin's password and sign in as them — through the very door `changeRole` was built to keep
shut. The one-way door
is deliberate: mutual demotion between two administrators is a fight the software should not host.
Four refusals sit on `changeRole`: `SUPERADMIN` cannot be given out, the seeded account's role
cannot be changed, nobody changes their own, and demotion needs the seeded account.

**The trap: `@PreAuthorize("hasRole('ADMIN')")` would break the default configuration.** With
authentication off nobody is signed in, so a plain role expression denies every management call and
takes single-user mode down entirely — silently, on the setting most people run. Every rule
therefore points at one bean, `auth/Authz`, which reads the switch first and answers yes where there
are no accounts, because there the person at the keyboard is the operator by definition.
`RolesWithoutAuthTest` is a whole class guarding that.

`Authz` reads the role from the database rather than from the access token: a token says what was
true when it was issued, and with a two-hour life a demotion would keep working all afternoon.

`UserService.search` is deliberately open to anyone signed in, unlike everything else there — the
history screen builds its "started by" filter from it, so locking it down would quietly empty a
filter ordinary people use. `update` is guarded *inside* rather than on the method, because its two
callers differ: an administrator editing an ordinary user, and a person changing their own password.

`WorkVisibility.mayRead` now answers for a `DocumentSession` as well as a `JobRecord`: the ladder
was written for the history the day runs got owners, while the documents those runs work on had no
rule at all — an id was the whole of the security. The check is called from the **controllers**, not
from `DocumentSessionService.require`, because that method is also reached from a job's own virtual
thread where there is no security context and the honest answer to "who is asking" is nobody.

`LlmSettingsService` has two readers on purpose: `active()` is unguarded and carries the real keys
for the planner and the chat, `getSettings()` is superadmin-only and carries none. One method
serving both is how a stored API key ends up in every signed-in person's browser.

`SecurityMatrixTest` asks every guarded endpoint as USER / ADMIN / SUPERADMIN over HTTP with real
tokens. Service-level tests cannot find a missing guard — they call the method with a role in the
context, and a method nobody guards is one they never think to ask about.

`V11__user_roles.sql` backfills existing accounts to `ADMIN` and the first to `SUPERADMIN`. Setting
everyone to `USER` would wake a multi-person instance up with one administrator and everybody else
locked out of a screen they had yesterday — an upgrade must not remove authority somebody already
had. New accounts default to `USER`, and the column carries that default so a raw insert cannot fail.

Guards that stand whether or not anyone logs in, and must not be loosened casually:

- **CORS is an allowlist** (`sheetsmith.security.allowed-origins`), not `*`. With authentication off,
  `*` would let any site the user has open drive their instance, including overwriting the stored
  cloud API keys via `PUT /api/settings`.

  It is a `CorsConfigurationSource` bean read by the filter chain, **not** an MVC mapping. It was an
  MVC mapping, and that stops working the moment a chain exists: MVC mappings are applied by the
  dispatcher, which the chain sits in front of, and a preflight `OPTIONS` carries no credentials by
  definition — so an authenticated instance would refuse the preflight and the browser would report
  a CORS failure for a rule that is configured correctly. Nothing on the server looks wrong. Hence
  `OPTIONS` is permitted explicitly and `SecurityChainTest` asserts preflight in both shapes.

- **An unauthenticated call gets 401, not Spring's default 403** for an anonymous caller
  (`HttpStatusEntryPoint`). The browser's silent refresh keys off 401: it means "try again with a
  fresh token", where 403 means "you are known and still may not" and no retry can fix it.
- `services/PathGuard` — `POST /api/excel/improve/path` is disabled by default; when enabled, both
  paths must resolve inside a configured root, with symlinks followed and `..` resolved rather than
  string-matched.
- Job parallelism is controlled by a `Semaphore` bean (default `maxConcurrentJobs=1`), overridable via `MAX_CONCURRENT_JOBS`.
- Database: PostgreSQL, defaults to `localhost:5432/xlsxai` / `postgres` / `pass`; override with `DB_HOST`, `DB_PORT`, `DB_NAME`, `DB_USERNAME`, `DB_PASSWORD`. The database name stayed `xlsxai` when everything else was renamed — it names a database that already exists on anyone running an earlier build, and a new default would point them at an empty one.
- Settings are `sheetsmith.*` and their environment variables `SHEETSMITH_*`. Each placeholder in `application.yaml` reads the old `XLSXAI_*` name as a second fallback, so an `.env` written before the rename still configures the instance rather than silently reverting it to defaults.
- Schema: **Flyway owns it**, `ddl-auto: validate`. Migrations are `src/main/resources/db/migration/`;
  `V1__baseline.sql` is the schema as `ddl-auto: update` left it, generated from the mappings rather
  than typed. `baseline-on-migrate: true` is what makes an existing database safe to upgrade —
  Hibernate built it, so Flyway stamps it as version 1 and starts it at V2; an empty database runs V1
  itself. Adding a column now means writing the next `V*.sql`: `validate` fails startup on a mapping
  the migrations do not match, which is how `SchemaMigrationTest`'s container run catches it.

### Prices, and where money comes from

`model_prices` (V10) is a reference table keyed on provider + model, filled in by hand and never by
migration — nobody but the operator knows what they pay. `ModelPriceController` is four endpoints
plus two for the catalogue; there is no separate create, because provider + model is a natural key
and `PUT` is already "put a price at this address".

**Cost is worked out in Java, not joined in SQL** (`AnalyticsService.cost`). A join would either drop
unpriced rows or count them as zero, and both are a wrong total wearing the face of a right one.
Instead an unpriced model contributes nothing, lands in `unpricedModels`, and a group where nothing
could be priced answers `null` rather than `0.00`.

**`PriceCatalogueService` proposes; it never applies.** No provider publishes a price API — OpenAI,
Anthropic and Google put prices on documentation pages meant for people — so the source is
OpenRouter's open JSON catalogue, named on the dialog because this is the one outbound call the
application makes. Asking a model instead was rejected: it would state a confident price for a model
whose price it does not know, and this is a screen about money.

The comparison is a read; saving is a second call carrying the rows a person ticked, with the figures
from the request rather than re-fetched. Only models already priced or actually used are offered — a
list too long to read before confirming is a list confirmed without reading — and local models never
are, because free is not an unfilled price. Names are matched exactly, then by longest prefix:
Anthropic answers `claude-sonnet-4-20250514` where catalogues list `claude-sonnet-4`, and longest
wins so `claude-sonnet-4` cannot swallow `claude-sonnet-4-5`. `ModelCatalogue` is an interface with
one implementation purely so tests can hand the comparison a constant instead of the internet.

### Supported action types

`FORMAT_CELLS`, `CREATE_CHART`, `ADD_SHEET`, `ADD_FORMULA`, `SORT_DATA`, `FILTER_DATA`, `CONDITIONAL_FORMATTING`, `MERGE_CELLS`, `CLEAR_CELLS`, `RENAME_SHEET`, `RENAME_COLUMN`, `RENAME_CHART_TITLE`, `RENAME_CHART_AXIS`, `SET_CELL_VALUE`, `AUTOSIZE_COLUMNS`, `FREEZE_PANES`, `NUMBER_FORMAT`, `SET_BORDERS`, `ALIGN_CELLS`, `INSERT_ROWS`, `DELETE_ROWS`, `INSERT_COLUMNS`, `DELETE_COLUMNS`, `FILL_FORMULA`, `ADD_TOTALS_ROW`, `REMOVE_DUPLICATES`, `DELETE_SHEET`, `UNMERGE_CELLS`, `DATA_VALIDATION`, `CREATE_TABLE`, `COLOR_SCALE`, `DATA_BARS`, `GROUP_ROWS`, `PAGE_SETUP`, `HYPERLINK`, `COMMENT`, `PROTECT_SHEET`, `LOOKUP_FROM_SHEET`, `GROUP_BY`, `SPARKLINES`, `TRANSFORM_COLUMN`

**Three actions touch cell contents and the model has to pick between them**, so each of their catalog
entries points at the other two: `FORMAT_CELLS` changes only how cells *look*, `SET_CELL_VALUE` writes
*one literal* (and is the only way to put a value into an empty cell — before it, "write Q1 2026 in A1"
was not expressible at all), and `TRANSFORM_COLUMN` rewrites an existing column's values *in bulk*.
`TRANSFORM_COLUMN`'s rules (11 so far, in `services/excel/transform/`): `PHONE_US`, `TRIM`, `UPPER`,
`LOWER`, `TITLE_CASE`, `DIGITS_ONLY`, `REPLACE`, `REGEX_REPLACE`, `SPLIT_TAKE`, `PAD_LEFT`, `TO_NUMBER`.

`SET_CELL_VALUE` decides a literal's type from the JSON type it arrives as, and coerces a *quoted*
number only when the number renders back to exactly the string that was sent — so `"007"`, `"1.50"` and
`"42.0"` stay text while `"42"` becomes a number. Dates are never inferred (`valueType: "date"`, ISO
only), because a misread `01/02/2026` is silent corruption and an unformatted date reads as a serial
number. Writing over a cell that holds a formula removes the formula, so the step counts that and says
so — `describe()` alone would report only the value written, and a deleted formula raises no error for
the self-check to catch.

`AUTOSIZE_COLUMNS` is the one action whose cost scales with the sheet rather than the range it was given
— POI measures every value through AWT — so it is bounded by `sheetsmith.processing.max-autosize-cells` and
degrades to a counted skip both where fonts are unavailable and where the budget runs out. It also
refreshes formula cells with a `FormulaEvaluator` before measuring: POI sizes a formula from its
*cached* result, and the workbook is not recalculated until it is saved, so `ADD_FORMULA` followed by
`AUTOSIZE_COLUMNS` would otherwise size the column to the stale `0` and save the real total into a
column too narrow to show it.

**Four actions style cells, and they share `services/excel/CellStyles`** — `FORMAT_CELLS`,
`NUMBER_FORMAT`, `SET_BORDERS`, `ALIGN_CELLS`. Its rule is the reason the three new ones were built
as one batch: a style is **edited, never replaced**. A cell style in xlsx is a shared record, so the
obvious implementation (create a style, set the one facet, assign it) discards every other facet the
cell had — which is exactly what the old `StyleHandler` did, and why colouring a column used to wipe
the number format applied to it a step earlier. `CellStyles.apply()` clones the style each cell
actually has, changes the requested facet on the copy, and caches the result on the pair *(style the
cell had, edit being made)*, so 5 000 identically styled cells produce one new style rather than
5 000 — the workbook's style table tops out at 64 000. `StyleEdit.key()` returning null means "this
cell is not part of the edit": that is how an outline touches the rim of a block without creating
cells through the middle of it. `CellStyles.MAX_CELLS` (100 000) bounds a styling step, and
whole-column ranges are refused outright — POI resolves `A:A` to row -1, which survives every size check
and would materialise a million rows.

`NUMBER_FORMAT` validates a literal pattern through a `DataFormatter` before writing it, because POI
stores a malformed pattern happily and Excel then offers to repair the file. It also counts cells
holding **text** and reports them: no number format applies to a string, so without that the step is
a silent no-op that looks like a bug. `SET_BORDERS`' `sides` names sides of the *range*, not of every
cell, which is what makes "underline the header" one line rather than five; removal cannot clear a
line the neighbouring cell draws inward, and says so. `ALIGN_CELLS` returns custom row heights in the
range to automatic when wrapping is turned on, or Excel clips the wrapped text instead of growing the
row.

**Four actions move cells they were not pointed at** — `INSERT_ROWS`, `DELETE_ROWS`,
`INSERT_COLUMNS`, `DELETE_COLUMNS`, sharing `services/excel/actions/StructureShift`. What POI 5.5.1
does and does not do here is pinned by `RowColumnShiftTest`, because the catalog entries promise
users a specific answer: `shiftRows`/`shiftColumns` rewrite formulas **across every sheet in the
workbook** (a `Summary!Data!A4` reference follows the shift) and move merged regions; they do
**not** move a freeze pane (a pane is a view property, not a range) and do **not** repoint a chart,
whose ranges live in the drawing part rather than the formula table. The chart case has its own test
so that a future POI release changing it is noticed rather than silently making the catalog's advice
wrong.

Because a broken reference is otherwise a silent success — the file saves, the step reports done, and
a total three sheets away is now `#REF!` — each of the four scans the workbook's formulas through
`FormulaErrorScanner` before and after, and names the newly broken cells in its detail string. That
is the same scanner the chat's self-check uses, so the addresses reach the model in the trace it
reads next.

Inserted rows and columns arrive **empty and unstyled**: Excel's own insert copies the row above,
which is helpful under a human's eye and a trap in a plan, where the next step writes into cells that
have silently inherited a header's fill. `StructureShift.MAX_INSERT` (1 000) caps one insert; a
delete is instead clamped to what the sheet actually holds and reports the difference, while a delete
naming no target at all is refused outright.

**Three actions each collapse a plan's worth of steps into one**, which is a review-surface decision
before it is a token one: a plan card reading "Fill D2:D500 with =B2\*C2" is one idea a user can
approve, where 500 `ADD_FORMULA` cards are not.

`FILL_FORMULA` does the relative-reference shifting through POI's own machinery —
`FormulaParser.parse` → `FormulaShifter.createForRowCopy` (or `createForColumnCopy` for a one-row
range) → `FormulaRenderer.toFormulaString` — rather than rewriting the formula as text. Only the
parser knows that `$F$1` must not move, that `SUM(B2:B4)` holds one range rather than two
references, and that a `B2` inside a string literal is not a reference at all. A formula that shifts
off the sheet renders as `#REF!`, which POI will not parse back, so the cell is written as an error
value — which is what Excel shows in the same situation.

`ADD_TOTALS_ROW` totals only columns holding numbers and names the ones it skipped: `SUM` over a
column of names is `0`, an answer that is wrong without being an error. Date columns are skipped for
the same reason — their sum renders as a date centuries away. `REMOVE_DUPLICATES` removes **whole
sheet rows** rather than Excel's optional "current selection only", which silently misaligns every
row against its neighbours; it deletes bottom-up in contiguous blocks, because each removal
renumbers the rows beneath it and working downwards would delete a different row than the one that
matched.

**`DELETE_SHEET` finds its broken formulas differently from every other destructive action**, and
the difference is worth knowing before trusting `FormulaErrorScanner` anywhere new. That scanner
decides a formula is broken by *evaluating* it — but a formula naming a missing sheet does not
evaluate to an error, it fails to evaluate at all, and `errorOf()` treats an unevaluatable cell as
fine (it has to: POI implements fewer functions than Excel). So a sheet deletion is invisible to it.
`DeleteSheetHandler.formulasReferencing()` therefore reads formula *text* for `Name!` / `'Name'!`
**before** removing the sheet. POI leaves those formula strings untouched, so the `#REF!` the user is
warned about is what Excel shows on opening the file, not something in the saved XML. This was found
by a test asserting the opposite and failing.

`StructureShift.brokenFormulas()` is the shared before/after reporting the row, column and duplicate
removals use; its count check is what stops a step being blamed for damage it merely *moved*.
`DELETE_SHEET` deliberately does not use it — it would report nothing.

**`DATA_VALIDATION` and `CREATE_TABLE` change the sheet's future rather than its contents**, and
both are constrained by rules of the xlsx format rather than of this code — which is why each
refuses or repairs rather than writing a file Excel offers to "recover":

- an explicit validation list is one string capped at 255 characters, so a longer one is refused
  with `sourceRange` named as the fix;
- a table needs every column headed and no two headings equal, so `repairHeaders()` fills blanks and
  numbers repeats **before** `createTable`, and reports what it changed;
- table names must be unique workbook-wide and contain only letters, digits and underscores, so
  `name()` sanitises and de-duplicates; two tables may not overlap, which is checked up front.

`XSSFTable.updateHeaders()` is not optional after `createTable` — without it the table's columns are
named `Column1`, `Column2`, and a structured reference like `Sales[Amount]` names nothing. Both
actions are covered in `ActionRoundTripTest` because a table and a validation are separate parts of
the xlsx package, and "present in the object graph" is not the same claim as "present in the file".

**`SPARKLINES` is the only action written as raw XML, and the only one no Java test can really
verify.** POI 5.5.1 has no sparkline API and `poi-ooxml-full` carries no x14 schema class for one
(checked, not assumed), so the element is built as text and copied into the worksheet's `extLst`
with an `XmlCursor`. Nothing validates it on the way out — POI writes whatever it is handed — and
the failure mode is not an exception but a file Excel either repairs or, worse, opens happily while
ignoring the block entirely.

Which is what happened first. The verification was done by driving the real Excel over COM
(`Workbooks.Open`, then `Range("F2:F4").SparklineGroups.Count`), and the first attempt reported
**zero** groups while the XML looked perfect and survived Excel's own re-save untouched — because an
`extLst` entry Excel does not recognise is passed through the file rather than rejected. The cause,
isolated by changing one thing at a time, is the extension's uri: **Excel matches that GUID
case-sensitively**, so `{05C60535-1F16-4FD2-…}` is ignored where `{05C60535-1F16-4fd2-…}` is read.
`SparklineHandlerTest` pins the literal for that reason. If the XML in `SparklineHandler` is ever
changed, re-run the Excel check — the Java tests only prove the bytes are where we put them.

**`GROUP_BY` is deliberately not a pivot table, and the reason was measured rather than assumed.**
A spike built one with `XSSFPivotTable`: POI creates it, it survives a save, and the reopened file
still has the pivot — but the reopened *sheet has no rows at all*. POI writes a pivot cache marked
`refreshOnLoad` and leaves the result cells to Excel, so the numbers exist only once desktop Excel
opens the file. Here the sheet is read back by POI for the chat's query tools and previewed in the
browser by SheetJS, and neither computes a pivot: the user would approve a plan, apply it, and be
shown an empty sheet. So `GROUP_BY` writes a real block of cells and formulas instead, which the
preview shows, later steps can format or sort, and Excel recalculates. The same `_xlfn.` constraint
as `LOOKUP_FROM_SHEET` applies: `SUMIF` and `COUNTIF` are in POI's table and `AVERAGEIF` is not, so
an average is a sum over a count — and `MINIFS`/`MAXIFS` have no such workaround, which is why min
and max are refused with their reason rather than written as something that opens broken.

**`LOOKUP_FROM_SHEET` writes a formula, which makes POI's function table part of its contract.**
POI parses `IFNA(...)` without complaint, but the name is not in that table: post-2007 functions are
stored by Excel with an `_xlfn.` prefix and read as unknown defined names without one, so the
obvious spelling of "blank when there is no match" would have produced a column of `#NAME?`. The
fallback is therefore `IF(ISNA(VLOOKUP(…)),"",VLOOKUP(…))` — three functions POI implements — and
`LookupFromSheetHandlerTest` proves it by *evaluating* the result rather than reading the formula
back as text. The match is always exact, because VLOOKUP's approximate mode answers with the nearest
smaller key on unsorted data and looks like it worked. Unmatched keys are counted in Java against
the source column, with numbers and text kept apart the way Excel keeps them apart, so the step can
report the misses it has just blanked.

**`PROTECT_SHEET` is the one action whose obvious use is the wrong one.** Every cell in a sheet
carries a `locked` flag that is *already* set and does nothing until the sheet is protected — so
protecting on its own freezes everything, which is almost never the ask. `unlockedRange` unlocks the
cells that should stay fillable first, through `CellStyles` like any other style facet, and the step
reports how many stayed editable along with the honest caveat that Excel's protection is a guard
rail rather than a secret. `HYPERLINK` styles its cells for the same "otherwise it looks like it did
nothing" reason a link is blue and underlined in Excel, and rebuilds the cell's font rather than
replacing it so a bold heading survives becoming a link. Its second shape — a `range` with no
`address` — turns a column of addresses already sitting there into links, which is the review-surface
argument `FILL_FORMULA` and `ADD_TOTALS_ROW` already made: one card a person can approve beats 500.

**`GROUP_ROWS` and `PAGE_SETUP` change how the sheet is read rather than what it says**, which puts
them on the far side of a line `RowColumnShiftTest` already draws: a freeze pane is a view property
and does not move when rows are inserted, and an outline level and a print setup are the same kind
of thing. `GROUP_ROWS` clamps its span to the rows the sheet actually holds, because POI's
`groupRow` *creates* every row it is handed — "8:5000" would otherwise materialise five thousand
rows rather than fail. `summaryBelow` is a property of the whole sheet rather than of one group, so
it is documented as such: Excel puts the outline button on the summary row and assumes it sits below
the detail. `PAGE_SETUP` always sets `setFitToPage(true)` alongside `fitWidth`, because Excel ignores
the width on its own, and defaults an unnamed `fitToHeight` to 0 rather than POI's 1 — "one page
wide" almost never means "one page tall". Its print area and repeating rows are stored as defined
*names* in the workbook rather than on the sheet, which is why `ActionRoundTripTest` checks them in a
reopened file.

**Three actions colour a range by its numbers, and the choice between them is a threshold.**
`CONDITIONAL_FORMATTING` needs one — it paints every cell past a value the user already knows.
`COLOR_SCALE` and `DATA_BARS` need none: both anchor to the range's own minimum and maximum, which
is what makes "show me where the big numbers are" answerable at all. They share
`services/excel/actions/ValueScale`, whose only job is the thing Excel does silently — a scale or a
bar paints **numbers only**, ignoring text without complaint, so a rule laid over a text column
renders nothing while the step reports success. `ValueScale.coverage()` counts what the range
actually holds and the detail string says how many cells stay unpainted. A formula counts by its
*cached* result, because the workbook is not recalculated until it is saved and the cache is also
what Excel paints from.

`COLOR_SCALE` takes its colours all-or-nothing: naming one end means naming both, and mixing a
chosen colour with a default one is refused rather than producing a scale whose ends disagree.
Omitting `midColor` is how two-colour is asked for, which is why absence is a real choice here and
not a missing key.

Each handler lives in a group under `services/excel/actions/`, and its config model (a Lombok
record) in the matching group under `services/excel/model/` — the two trees mirror each other, so a
handler and its config are one directory apart:

| group | what it changes |
|---|---|
| `cell` | the contents of cells: values, clearing, merging, bulk rewrites, a header rename |
| `format` | how cells look, including the three that colour by value |
| `structure` | rows and columns that move, and the reordering of data |
| `formula` | steps that write formulas |
| `sheet` | whole sheets: adding, deleting, renaming, protecting |
| `view` | how the sheet is read rather than what it says: freezing, outlining, printing |
| `chart` | charts and sparklines |
| `table` | a table or a validation — the sheet's future rather than its contents |
| `annotate` | hyperlinks and comments |

`ActionDescriptions` stays at the root of `actions/` and `SheetTargetConfig` at the root of
`model/`: both are shared by every group. Splitting the packages is what made them public — they
had been package-private, which is worth knowing before treating either as an internal detail.

A test lives in the package of the thing it tests, which matters where a test pins something
package-private: `SparklineHandlerTest` asserts the extension GUID literal, and that constant is
not public.

### Query tool types (chat only, 5 total)

`READ_RANGE`, `AGGREGATE`, `FIND_ROWS`, `DESCRIBE_COLUMN`, `EVAL_FORMULA` — in `services/excel/query/`.

### History & file lifecycle

- `GET /api/history` — paginated job list (newest first)
- `GET /api/history/{id}` — single job detail
- `GET /api/history/{id}/download` — download result `.xlsx` (404 if not ready)
- `DELETE /api/history/{id}` — delete job record + input/result files from disk

**A job never deletes a session's files.** A session-backed job's input and result *are* revisions of
a live chain: deleting them would punch a hole in the undo history or take the current sheet with
them. `FileStorageService.deleteJobFiles()` is the single guard — it skips anything under
`sheetsmith.storage.session-dir`, so such a job drops its record only, and both `JobService.deleteJob`
and `FileCleanupService` go through it. Those files go when the session goes.

Chat endpoints live under `/api/chat/sessions` — see README for the full list.

`FileCleanupService` runs daily at 02:00 and deletes jobs (+ files) older than `sheetsmith.storage.ttl-days` (default 7, override via `SHEETSMITH_TTL_DAYS`), plus sessions (+ their revision directories) idle for the same period.

`StorageQuotaService` is the other half of that: a count cap and a size cap, both optional, both holding at once. It runs as each job finishes — that is when the archive grows, and it is never fatal, because failing a finished run over housekeeping would trade the user's work for tidiness — and again nightly at 02:30, for the caps a running instance cannot see itself cross (one lowered while it was idle, files that arrived without a run). Eviction is oldest-finished-first, in batches of 200 so a misconfigured cap cannot empty the table in one pass, and it skips any run that holds nothing it can free: a live session's revisions are not the cap's to take, and deleting such a row would remove a line of history while freeing nothing.

`application-prod.yaml` activates with `-Dspring-boot.run.profiles=prod` and enforces `ddl-auto: validate` + requires all DB env vars (`DB_HOST`, `DB_NAME`, `DB_USERNAME`, `DB_PASSWORD`).
