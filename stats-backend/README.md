# Pre-push stats backend (Zoho Catalyst)

Collects anonymous, opt-in pre-push counts from the plugin and serves an
aggregate for the GitHub Pages stats page.

## Endpoint

```
STATS_BASE_URL = https://crmqaautomation-60066298984.development.catalystserverless.in/server/prepush_stats
```

Deployed 2026-11-16 to project **CrmQaAutomation** (org ZohoCorpD `60066298984`,
project `31247000000660668`, IN DC), **Development** environment. Table
`prepush_stats` id `31247000009554314`. If promoted to Production, the domain
changes — update the places below.

Set this value in:

- plugin: `src/main/java/com/github/prepushchecker/stats/StatsEndpoint.java`
  (`<base>/report`)
- GitHub: repository variable `STATS_TOTALS_URL` = `<base>/totals`
  (used by `.github/workflows/stats.yml`)
- `docs/index.html`: `TOTALS_URL` constant

### `POST <base>/report`

```json
{ "installId": "uuid", "pluginVersion": "2.8.0", "ideBuild": "IU-243.21565.193",
  "ok": 3, "blocked": 1, "issues": 7, "timeouts": 0 }
```

- `installId`: 8–64 chars `[A-Za-z0-9-]` (random UUID; stored lower-case).
- `ok`/`blocked`/`issues`/`timeouts`: deltas since last successful upload,
  non-negative integers. Missing = 0. Each is clamped to 500 per call.
- At most one call per minute per `installId`.

| Status | Meaning | Client action |
|---|---|---|
| 200 `{accepted:true, changed}` | stored (or all-zero no-op) | clear local deltas |
| 400 `{error}` | bad shape | drop deltas (won't ever succeed) |
| 413 | body > 4 KB | drop |
| 429 `{retryAfterSeconds}` + `Retry-After` | rate limited | keep deltas, retry later |
| 5xx | server error | keep deltas, retry later |

### `GET <base>/totals`

```json
{ "users": 12, "compilesOk": 3456, "blocked": 210, "issuesCaught": 987,
  "timeouts": 4, "updatedAt": "2026-11-16T10:00:00.000Z" }
```

Public, `Access-Control-Allow-Origin: *`, cached 5 min (in the warm
container + `Cache-Control: public, max-age=…`). `users` = installs that
reported at least one non-zero delta.

## Data Store table `prepush_stats`

| Column | Type | Notes |
|---|---|---|
| `install_id` | varchar(64) | **unique**, mandatory |
| `plugin_version` | varchar(32) | last reported |
| `ide_build` | varchar(64) | last reported |
| `ok` | bigint | default 0 |
| `blocked` | bigint | default 0 |
| `issues` | bigint | default 0 |
| `timeouts` | bigint | default 0 |
| `last_report_ms` | bigint | epoch ms of last accepted report (rate limit) |

No boolean columns (Data Store booleans come back as `"true"`/`"false"`
strings). Numbers come back as strings too; `lib.toInt` handles that.
The function uses admin scope, so App User permissions can stay read-only
(or none) — nothing talks to the table directly from a browser.

## Code

```
stats-backend/
  functions/prepush_stats/
    catalyst-config.json   advancedio, node20
    index.js               HTTP routing, Data Store I/O
    lib.js                 pure validation / merge / totals helpers
    lib.test.js            node --test
```

Run unit tests (no Catalyst account needed):

```bash
cd stats-backend/functions/prepush_stats && npm test
```

## Deploy (creates resources in your Catalyst account)

Requires Catalyst CLI ≥ 1.27 (`catalyst --version`) and `catalyst login`.

1. Create (or pick) a project in the Catalyst console. Note org id + project id
   (`catalyst project:list` or console URL).
2. Link this folder to it (non-interactive; writes `catalyst.json` + `.catalystrc`):

   ```bash
   cd stats-backend
   catalyst init --org <ORG_ID> -p <PROJECT_ID> -ni
   ```

   Make sure `catalyst.json` contains:

   ```json
   { "functions": { "folder_path": "functions", "targets": ["prepush_stats"] } }
   ```

3. Create the table in Console → Cloud Scale → Data Store → *Create table*
   `prepush_stats` with the columns above (or via the Catalyst MCP
   `Create_Table` / `Create_Column` tools). Mark `install_id` unique.
4. Install deps and test locally (Data Store calls hit the Development env):

   ```bash
   cd functions/prepush_stats && npm install && cd ../..
   catalyst serve
   curl -X POST http://localhost:3000/server/prepush_stats/report \
     -H 'Content-Type: application/json' \
     -d '{"installId":"00000000-0000-4000-8000-000000000001","pluginVersion":"2.8.0","ok":1,"issues":2,"blocked":1}'
   curl http://localhost:3000/server/prepush_stats/totals
   ```

   (Port is printed by `catalyst serve`.) A second POST within 60 s → 429.
5. Deploy to Development and verify the same two curls against the printed URL:

   ```bash
   catalyst deploy --only functions:prepush_stats -ni
   ```

6. Console → Functions → `prepush_stats` → Security Rules: authentication
   `optional` (public — required, the plugin sends no credentials).
   Do **not** add the GitHub Pages domain under Authentication → Authorized
   Domains: the function already sends `Access-Control-Allow-Origin: *` on
   `/totals`, and both together produce a duplicate header that browsers reject.
7. Promote to Production (Console → Deploy → Production) and use the
   production URL as `STATS_BASE_URL`.

## Limits / notes

- Advanced I/O: 30 s timeout, 10 concurrent executions per env by default.
  The plugin uploads every 30 min per install, so this is far from the limit.
- No transactions: two simultaneous first reports from one install → second
  insert hits the unique constraint → 429 (client retries). Updates are
  guarded by the 60 s rate limit.
- Abuse ceiling: a forged installId can add at most 500 per field per minute.
  Acceptable for a vanity counter; delete rows by `install_id` if needed.
