# Contributing to SheetSmith

Issues and pull requests are welcome. This page is what the repository assumes you know.

## Running it

You need Java 21, Maven 3.9+, Node 20+, PostgreSQL 14+, and something for the model to talk to —
a local [Ollama](https://ollama.com) by default.

```bash
cd sheetsmith-java && docker compose up      # everything at once: Postgres, Ollama, the model, the app
```

Or the two halves separately, which is what you want while working on either:

```bash
cd sheetsmith-java && mvn spring-boot:run          # API on :8080
cd sheetsmith-react && npm install && npm run dev   # UI on :5173, /api proxied to :8080
```

`sheetsmith-java/ARCHITECTURE.md` is the architecture map, and it is kept accurate. Read it before
changing anything structural — it will save you an afternoon.

## There is no CI, on purpose

Nothing runs on a push. That is a deliberate choice about cost, not an oversight, and it means the
checks are yours to run:

```bash
cd sheetsmith-java && mvn test     # the engine
cd sheetsmith-react && npm test     # the UI
cd sheetsmith-react && npm run lint
```

Please run all three before opening a pull request, and say in the description that you did.

## The bar for new code

**Tests come with it.** The backend suite is large and fast; there is no reason for a new action or
a new endpoint to arrive without one.

**A test has to be able to fail for a real reason.** The convention here is to assert the thing that
actually breaks: an action's effect is checked on a workbook that has been *written to disk and
reopened*, because "present in the object graph" is not the same claim as "present in the file".
`ActionRoundTripTest` is where those live.

**Say what the code does that the code cannot say.** Comments here explain a why, an invariant or a
POI quirk, in a sentence or two. A comment restating the line below it will be removed.

## Adding an action

This is the most common change, and the engine is built for it. The full six-stage routine lives in
`.claude/skills/excel-task/SKILL.md`; the short version:

1. **Write the handler.** A `@Component` implementing `ActionHandler` in
   `services/excel/actions/`. `@Component` *is* the registration — there is no list to add it to.
   Its config model is a Lombok class in `services/excel/model/`, deserialized with
   `FAIL_ON_UNKNOWN_PROPERTIES = false`, because a language model sends extra keys.
2. **`execute` returns a detail string or null.** If the action can succeed over only part of its
   input, it *must* return a count. Without that, a half-done job reaches the user as an unqualified
   success — the one rule in this codebase with no exceptions.
3. **`describe()` must never throw**, for any property map, and must never show a raw enum. Use the
   helpers in `ActionDescriptions`; they tolerate missing keys and wrong types on purpose. Add the
   handler to `ActionDescribeTest`'s `HANDLERS` list, and its new keys to `ALL_KEYS` — a handler
   missing from that list is silently outside the guarantee.
4. **Document it in the prompt, in both tiers.** `ActionCatalog.MUTATING_ACTIONS` gets the full
   entry, and `MUTATING_ACTIONS_INDEX_LIST` gets a one-line form. This is the step people forget:
   the chat opens every turn with the compact index, so an action missing from it is invisible to
   the chat and will never be chosen.
5. **Write the tests**, including the round-trip one, and run `mvn test`.
6. **Update the docs**: the list in `sheetsmith-java/ARCHITECTURE.md`, the table in
   `sheetsmith-java/README.md`, and the action count in the root `README.md` — that number is the
   only hand-maintained copy left in the repository, which is why it is worth mentioning here.

A new `TRANSFORM_COLUMN` rule is easier still: a `@Component` implementing `ColumnTransform`
documents itself into both prompts through the registry, and `ActionCatalog` is not edited at all.

## Commits

Write the message for someone who will read it in a year while trying to understand why the code is
shaped this way. Say what changed and, more importantly, *why* — the trap you hit, the behaviour you
measured, the alternative you rejected. The history here reads that way and it is worth keeping.

## What not to send

- Anything that removes a report of partial success.
- A new dependency without a reason that could not be met by the ones already here.
- Reformatting mixed in with a behaviour change.
- Authentication. This is designed to run on a machine you control — see [SECURITY.md](SECURITY.md).
