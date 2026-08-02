# CLOUD-SPEC — GitHub Gist backup invariants

PacheLauncher backs up save files to one secret GitHub Gist per player, per game, in the player's
own GitHub account. This document is the list of invariants the implementation
(`launcher/src/main/kotlin/com/teampacheworks/launcher/cloud/`) depends on. If you touch any file
in that package, re-read the invariant it affects before you change behaviour — several of these
were learned from a real data-loss bug in a sister project and are not obvious from the code alone.

## Auth

- The player supplies a GitHub fine-grained personal access token themselves (paste, or "Paste
  from clipboard"). `CloudBackupActivity` walks them through creating one: Expiration ~366 days,
  Account permissions -> Gists -> Read and write, nothing else.
- The token's shape is validated **locally**, before any network call:
  `^github_pat_[A-Za-z0-9_]+$` for a fine-grained token. A classic token
  (`^gh[pousr]_[A-Za-z0-9]+$`) is recognised and rejected with its own message telling the player
  to use a fine-grained token instead (`GitHubToken`).
- After the shape check passes, `GET /user` verifies the token actually works and fetches the
  login name.
- `GitHubToken` never exposes the raw value through `toString()` (masked) or through any method
  other than `applyTo(...)`, which sets the `Authorization: Bearer <token>` header directly. There
  is no other way to read the token value out of the type.

## Token storage

- `TokenStore` (`filesDir/cloud.token`) prefixes the file with a magic header —
  `LauncherConfig.cloudTokenMagic`, default `"PACHE-CLOUD-TOKEN-1\n"` — then AES-GCM wraps the
  token bytes with a key compiled into the app. This is **obfuscation, not hardware protection**,
  and is documented as such in `TokenStore`'s class comment. It is a deliberate choice, not an
  oversight: a sister project lost a real token when an Android Keystore alias silently
  disappeared on a real handheld, so this library does not use the Keystore.
- `TokenStore.tryLoad()` **never throws**. Absent file, corrupt bytes, foreign magic header, or an
  unwrap failure are all treated identically: signed out, and the bad file is deleted.
- No token on disk means zero network requests — enforced structurally: every authenticated
  `GistClient` entry point takes a non-null `GitHubToken`, and the request builder throws if one is
  somehow missing, rather than silently sending an unauthenticated call.
- The token file (and `LauncherConfig.cloudPrefsName`'s SharedPreferences file) must be excluded
  from Android Auto Backup by the **host app's own** backup rules — this library cannot declare
  that on the host's behalf. See INTEGRATION.md.

## Gist layout and payload

One secret gist (`"public": false` — GitHub does not allow flipping a gist from public to secret
after creation, so this is set once at creation time and never revisited) contains:

- `00-README.md` — human-readable explanation of the gist and how to decode a slot by hand
- `01-manifest.json` — metadata, see below
- `save-01.zip.b64.txt` .. `save-NN.zip.b64.txt` — one file per backup slot; the player triggers
  every upload manually (Upload button), there is no autosave/auto-ring to the cloud

### Payload format (`CloudPayload`)

```
<cloudProductName> cloud save · slot 01 · <bytes> bytes · sha256 <hex>
Base64 of save bundle zip. Decode: base64 -d <file> > save.zip
-----BEGIN <cloudFenceTag> SAVE-----
<base64, wrapped at 76 columns>
-----END <cloudFenceTag> SAVE-----
```

Base64 exists because the Gist API makes no documented promise that file `content` comes back
byte-for-byte — it is a JSON string field in a system built for source code, where end-of-line
normalisation is normal and undocumented. A save bundle's corruption would be silent and its loss
is a playthrough, so this format removes the entire risk class: base64's alphabet
(`A-Za-z0-9+/=`) contains nothing a text pipeline touches, and the decoder ignores whitespace.

The header lines are for a human reading the gist in a browser. **The decoder never trusts
them** — it locates the fence (which must start a line, so a fence-shaped substring quoted inside
prose cannot be mistaken for a payload) and nothing else. The authoritative byte count and hash
live in the manifest, not in the header text.

`cloudFenceTag` must stay stable once players have cloud backups: changing it does not corrupt
anything, but every payload uploaded under the old tag will fail to decode as "ours" any more.

### Manifest (`01-manifest.json`, `CloudManifest`)

```json
{
  "app": "<cloudAppId>",
  "version": 1,
  "revision": 7,
  "updatedAt": "2026-01-01T00:00:00Z",
  "slots": [
    { "file": "save-01.zip.b64.txt", "sha256": "...", "bytes": 12345,
      "note": "before boss fight", "uploadedAt": "2026-01-01T00:00:00Z" }
  ]
}
```

- Held as a live JSON object and mutated in place: known fields go through `CloudManifest`'s typed
  accessors, everything else is carried through a rewrite verbatim. A future build that adds a
  field must be able to round-trip through an older build without losing it.
- **Correctness never depends on this file.** It is display data plus one cross-check (the
  SHA-256). What actually decides whether a downloaded bundle may overwrite local saves is
  `SaveBundle.validate` run on the decoded bytes in a temp file (see below). A missing, stale, or
  hand-edited manifest degrades the UI and nothing more.

## Sync invariants

These nine hold regardless of which game is using the library. `CloudStore` and `GistClient`
implement them; do not weaken any of them without updating this document and every call site.

1. **One PATCH = the slot file and the manifest, together, in one request.** There is never a
   window in which a slot's bytes and its manifest row disagree. Deleting a slot is a `null` value
   for that file name in the same PATCH, not a separate delete call.
2. **Staleness is decided by a monotonic revision counter, never a timestamp.** The device
   remembers `lastWrittenRevision` (`CloudState`); a freshly fetched manifest whose `revision` is
   lower than that is a stale edge/CDN copy and must never be shown as truth or written over.
3. **Duplicate detection is SHA-256 content identity, never mtime.** Uploading a bundle whose hash
   already matches an existing slot reports "Up to date" — no new slot, no revision bump.
4. **Download order is fixed:** fetch -> decode fence -> verify SHA-256 against the manifest ->
   write to a temp file -> `SaveBundle.validate` the temp file -> confirm dialog -> commit through
   `SaveImporter` (which itself backs up to `pre-import.bak.zip` and rolls back byte-for-byte on
   failure) -> clean up the temp file in a `finally`, on every exit path.
5. **Dual read (`GistClient.readTextFile`):** try the authenticated `GET /gists/{id}` first for the
   life of the session. Only a 401/403, or a 404 seen before any strategy has been chosen, flips the
   session to the anonymous raw URL (`gist.githubusercontent.com/.../raw/...`). A 5xx or timeout is
   transient — it is retried and never changes strategy. Once a strategy is chosen it is sticky for
   the `GistClient` instance's lifetime (`GistReadStrategy`).
6. **Discovery** (`GET /gists?per_page=100`, up to 3 pages): a gist belongs to this game if its
   `01-manifest.json` exists and its `"app"` field equals `LauncherConfig.cloudAppId` —
   discovery never uses the gist description to decide ownership. Exactly one matching candidate is
   auto-adopted; more than one is left for the player to choose between. Discovery is re-run once
   more immediately before creating a new gist, to avoid creating a duplicate from a race.
7. **Retry policy:** at most 3 attempts total, backoff `2^attempt * 400ms + jitter`, honouring
   `Retry-After` when present (clamped to 0-3600s). Only 5xx is retried freely; 429 is retried
   exactly once, and only when the wait is <= 30 seconds.
8. **Status mapping** (`GistClient.classify`): 401 -> Unauthorized; 429, or 403 with a rate-limit
   signal -> RateLimited; 403 with "Resource not accessible" -> MissingGistPermission; 404 ->
   NotFound; 422 -> Rejected; >=500 -> ServerError. GitHub's `message` field is the only response
   body content ever surfaced to the player, and only after `CloudException.scrub` runs on it.
9. **Headers and limits:** `X-GitHub-Api-Version: 2022-11-28`, gzip accepted, 60s timeout on
   connect/read/write. `GitHub-Authentication-Token-Expiration` is captured from every response so
   the host can warn the player 30/7 days before a token expires.

## What CloudBackupActivity shows

- No token yet: instructions + a token field (paste, or "Paste from clipboard").
- Token present: the slot list from the manifest (note, size, uploaded-at), Upload (prompts for an
  optional note), Download, Delete slot, Disconnect (removes the local token only), and
  "Disconnect and delete cloud data" (deletes the whole gist — a two-step confirmation, since it is
  the one truly irreversible action in this screen).

## What never leaves this device unscrubbed

- The token value itself: only ever sent as an `Authorization: Bearer` header, never logged, never
  shown after the initial paste.
- Gist ids: a gist id is a bearer capability (anyone with it can read every save in it), so any log
  line involving one calls `CloudException.maskGistId` first (7-char prefix, then `…`).
- Any GitHub response body: only the `message` field is ever surfaced, and only after
  `CloudException.scrub`/`GistClient`'s own credential-shaped regex removes anything that looks
  like a token.
