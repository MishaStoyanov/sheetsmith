# Security

## What this application assumes

SheetSmith is **self-hosted and unauthenticated by design**. It assumes it is running on a machine
you control, reachable only by you — a laptop, or a host behind something that already decides who
gets in.

Anyone who can reach the port can upload a spreadsheet, read every session on the instance, and
change the stored settings, including the cloud API keys. That is the intended shape of a personal
tool, and it is stated plainly so nobody deploys it expecting otherwise.

**Do not put it on the open internet.** If it has to be reachable from outside, put a reverse proxy
with authentication in front of it, and do not rely on the guards below to stand in for that.

The database has a `users` table, and it does not change any of the above. Nothing authenticates
against it yet — it exists so that a job can record who ran it once there is a login to record. Read
"unauthenticated by design" as still true until this paragraph says otherwise.

## What the guards actually do

Three things carry weight, and they are worth understanding before you loosen any of them.

**CORS is an allowlist, not `*`** (`xlsxai.security.allowed-origins`). Without authentication, a
wildcard would let any page the user happens to have open drive their instance from their browser —
including overwriting the stored API keys through `PUT /api/settings`. If you add an origin, add the
specific one.

**`POST /api/excel/improve/path` is disabled by default** (`xlsxai.security.path-endpoint-enabled`).
It reads and writes files by path, so when enabled both paths must resolve inside a configured root;
`PathGuard` follows symlinks and resolves `..` rather than matching strings. Leave it off unless you
are scripting against your own files.

**Uploads are bounded** — 50 MB per file — and sessions and their revisions are deleted after
`xlsxai.storage.ttl-days` (7 by default) by a nightly job.

## What leaves the machine

By default, only the sheet's *structure* reaches the language model — names, headers, ranges,
formula text — plus the result of any query the chat itself runs, which does contain real cell
values. The root README says exactly where those two lines fall.

`XLSXAI_CHAT_ENABLED=false` removes the parts that could send anything more; they are absent from
the running application rather than merely unused.

If you point the app at OpenAI or Anthropic instead of a local Ollama, that data goes to them under
their terms. That is your choice to make, and the setting that makes it is not hidden.

## Reporting something

If you find a vulnerability — particularly one that lets a *remote* page or user reach an instance
that is not exposed on purpose — please report it privately rather than opening a public issue:

- open a [GitHub security advisory](https://docs.github.com/en/code-security/security-advisories/guidance-on-reporting-and-writing-information-about-vulnerabilities/privately-reporting-a-security-vulnerability)
  on this repository, or
- email the maintainer at the address on their GitHub profile.

Please include what you did, what happened, and what you expected. There is no bounty; there is a
maintainer who will read it and fix it.

Reports about the absence of authentication, or about what an authenticated local user can do to
their own instance, are not vulnerabilities — they are the design, described above.
