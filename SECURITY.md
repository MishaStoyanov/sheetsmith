# Security

## What this application assumes

SheetSmith is self-hosted, and it runs in one of **two modes**. Which one you are in is decided by
`SHEETSMITH_AUTH_ENABLED`, and everything below depends on the answer.

### Without authentication — the default

`SHEETSMITH_AUTH_ENABLED=false` (or unset) assumes the instance is running on a machine you control,
reachable only by you — a laptop, or a host behind something that already decides who gets in.

Anyone who can reach the port can upload a spreadsheet, read every session on the instance, and
change the stored settings, including the cloud API keys. That is the intended shape of a personal
tool, and it is stated plainly so nobody deploys it expecting otherwise. Runs are recorded with no
owner, because there is nobody to name.

### With authentication

`SHEETSMITH_AUTH_ENABLED=true` puts a login in front of `/api/**`. The first account is created by a
migration and its password is **`admin` / `admin`** — published here on purpose, because a known
first password with a nag attached is more honest than a generated one nobody can find. Change it;
the interface says so until you do.

Two things to understand before relying on it:

**There are three roles**, and the ladder is short enough to state in full.

| | `USER` | `ADMIN` | `SUPERADMIN` |
|---|---|---|---|
| Their own work, and their own account | yes | yes | yes |
| Ordinary users' runs, documents and spending | — | yes | yes |
| Another administrator's work, or the seeded account's | — | **no** | yes |
| Create, rename, reset the password of an ordinary user | — | yes | yes |
| Give `ADMIN` | — | yes | yes |
| Take `ADMIN` back, delete an account | — | — | yes |
| Model settings and stored API keys, storage, prices | — | — | yes |

`SUPERADMIN` is the seeded first account rather than a rank that can be handed out: it is the way
back into an instance, so there is exactly one and nobody can create a second. An administrator may
not act on a peer or on the account above them — not to reset a password, not to set a spend limit.
Without that half, "administrators cannot demote each other" would be a rule with a door beside it.

The default account cannot be deleted, and nobody can delete the account they are signed in with.
That is not a permission rule, it is a lock against locking yourself out.

**Where the rules live.** The filter chain names every `/api` path and what it needs, and refuses
anything it does not name; the rules that depend on the row — whose run, whose document, whose
account — are method guards on the services that hold it. Both read the same rules bean, and the
role is read from the database per request rather than from the token, so a demotion takes effect
immediately rather than when the token expires.

**Stored API keys are never sent to a browser.** The settings screen is told which providers have a
key, not what the key is; a key is only ever written.

**A login is not a substitute for a boundary.** Sessions rest on a two-hour access token and a
rotating refresh token in an httpOnly `SameSite=Strict` cookie; the cookie is deliberately not
`Secure`, because the app is normally reached over plain http on a local network and a secure cookie
would simply never be sent. Over the open internet that reasoning stops holding.

**Do not put it on the open internet** in either mode. If it has to be reachable from outside, put a
reverse proxy with TLS and its own authentication in front of it, and do not rely on the guards
below to stand in for that.

### If the password is lost

There is no email recovery and there will not be: a self-hosted instance has no mail server, and
nobody but you can reach your database — which is the point, not a gap. Set
`SHEETSMITH_ADMIN_PASSWORD_RESET` to a new password, start the instance once, then remove the
variable. It resets the default account and ends every session it had. While the variable is set,
every restart resets the password again and it sits in plain text in your environment.

Failing that, the password column is a bcrypt hash and you have SQL access — the README has the
statement that returns the default account to `admin`.

## What the guards actually do

Three things carry weight, and they are worth understanding before you loosen any of them.

**CORS is an allowlist, not `*`** (`sheetsmith.security.allowed-origins`). Without authentication, a
wildcard would let any page the user happens to have open drive their instance from their browser —
including overwriting the stored API keys through `PUT /api/settings`. If you add an origin, add the
specific one.

**`POST /api/excel/improve/path` is disabled by default** (`sheetsmith.security.path-endpoint-enabled`).
It reads and writes files by path, so when enabled both paths must resolve inside a configured root;
`PathGuard` follows symlinks and resolves `..` rather than matching strings. Leave it off unless you
are scripting against your own files.

**Uploads are bounded** — 50 MB per file — and sessions and their revisions are deleted after
`sheetsmith.storage.ttl-days` (7 by default) by a nightly job.

## What leaves the machine

By default, only the sheet's *structure* reaches the language model — names, headers, ranges,
formula text — plus the result of any query the chat itself runs, which does contain real cell
values. The root README says exactly where those two lines fall.

`SHEETSMITH_CHAT_ENABLED=false` removes the parts that could send anything more; they are absent from
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

Reports about the absence of authentication in the default mode, or about what a signed-in user can
do to their own instance, are not vulnerabilities — they are the design, described above. Anything
that lets one account act **as another**, read another account's runs or documents, reach the stored
keys or the price table without being the superadmin, or get past the login when it is enabled, is.

Five of exactly that shape were found and fixed on 2026-08-28, by asking every endpoint as each role
over HTTP rather than trusting that each service method had remembered its guard. That check is now
a test, and the chain refuses any path it does not name — so the next endpoint added without a rule
fails closed.
