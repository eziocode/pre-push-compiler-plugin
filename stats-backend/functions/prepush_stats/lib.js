'use strict';

// Pure helpers for the prepush_stats function. No Catalyst SDK here so they
// can be unit-tested with `node --test`.

const TABLE = 'prepush_stats';
const MAX_DELTA = 500;
const RATE_LIMIT_MS = 60 * 1000;
const TOTALS_TTL_MS = 5 * 60 * 1000;
const MAX_BODY_BYTES = 4096;

const DELTA_FIELDS = [
  // request key -> Data Store column
  ['ok', 'ok'],
  ['blocked', 'blocked'],
  ['issues', 'issues'],
  ['timeouts', 'timeouts'],
];

const INSTALL_ID_RE = /^[A-Za-z0-9-]{8,64}$/;
const VERSION_RE = /^[A-Za-z0-9.+_-]{1,32}$/;
const IDE_BUILD_RE = /^[A-Za-z0-9.+_-]{1,64}$/;

/**
 * Validates a /report body. Returns { ok: true, value } or { ok: false, error }.
 * Deltas must be non-negative integers; each is clamped to MAX_DELTA.
 */
function validateReport(body) {
  if (!body || typeof body !== 'object' || Array.isArray(body)) {
    return { ok: false, error: 'body must be a JSON object' };
  }
  const { installId, pluginVersion, ideBuild } = body;
  if (typeof installId !== 'string' || !INSTALL_ID_RE.test(installId)) {
    return { ok: false, error: 'installId must be 8-64 chars [A-Za-z0-9-]' };
  }
  if (typeof pluginVersion !== 'string' || !VERSION_RE.test(pluginVersion)) {
    return { ok: false, error: 'pluginVersion invalid' };
  }
  if (ideBuild !== undefined && ideBuild !== null && ideBuild !== ''
      && (typeof ideBuild !== 'string' || !IDE_BUILD_RE.test(ideBuild))) {
    return { ok: false, error: 'ideBuild invalid' };
  }
  const deltas = {};
  for (const [key] of DELTA_FIELDS) {
    const v = body[key] === undefined ? 0 : body[key];
    if (typeof v !== 'number' || !Number.isInteger(v) || v < 0) {
      return { ok: false, error: `${key} must be a non-negative integer` };
    }
    deltas[key] = Math.min(v, MAX_DELTA);
  }
  return {
    ok: true,
    value: {
      installId: installId.toLowerCase(),
      pluginVersion,
      ideBuild: typeof ideBuild === 'string' ? ideBuild : '',
      deltas,
    },
  };
}

function isAllZero(deltas) {
  return DELTA_FIELDS.every(([key]) => deltas[key] === 0);
}

/** Returns ms remaining until the install may report again (0 = allowed). */
function rateLimitRemaining(lastReportMs, nowMs) {
  const last = toInt(lastReportMs);
  if (!last) return 0;
  const elapsed = nowMs - last;
  return elapsed >= RATE_LIMIT_MS ? 0 : RATE_LIMIT_MS - elapsed;
}

/** New column values for an existing row after adding deltas. */
function mergeRow(existing, report, nowMs) {
  const row = {
    ROWID: existing.ROWID,
    plugin_version: report.pluginVersion,
    ide_build: report.ideBuild,
    last_report_ms: nowMs,
  };
  for (const [key, col] of DELTA_FIELDS) {
    row[col] = toInt(existing[col]) + report.deltas[key];
  }
  return row;
}

function newRow(report, nowMs) {
  const row = {
    install_id: report.installId,
    plugin_version: report.pluginVersion,
    ide_build: report.ideBuild,
    last_report_ms: nowMs,
  };
  for (const [key, col] of DELTA_FIELDS) row[col] = report.deltas[key];
  return row;
}

/** Data Store returns numbers as strings; tolerate null/garbage. */
function toInt(v) {
  const n = typeof v === 'number' ? v : parseInt(v, 10);
  return Number.isFinite(n) ? n : 0;
}

/** Sums flat rows (from getPagedRows) into the public totals shape. */
function totalsFromRows(rows, nowMs) {
  const t = { users: 0, compilesOk: 0, blocked: 0, issuesCaught: 0, timeouts: 0 };
  for (const r of rows) {
    t.users += 1;
    t.compilesOk += toInt(r.ok);
    t.blocked += toInt(r.blocked);
    t.issuesCaught += toInt(r.issues);
    t.timeouts += toInt(r.timeouts);
  }
  return { ...t, updatedAt: new Date(nowMs).toISOString() };
}

/**
 * Parses an aggregate ZCQL result. ZCQL wraps each result row under the table
 * name; aggregate keys may be the alias or the expression (e.g. "SUM(ok)"),
 * depending on runtime, so look up both. Returns null when the shape is
 * unrecognised (caller falls back to summing rows).
 */
function totalsFromZcql(result, nowMs) {
  if (!Array.isArray(result) || result.length === 0) return null;
  const first = result[0] || {};
  const flat = Object.assign({}, ...Object.values(first).filter((v) => v && typeof v === 'object'), first);
  const pick = (alias, expr) => {
    for (const k of Object.keys(flat)) {
      const lk = k.toLowerCase();
      if (lk === alias.toLowerCase() || lk === expr.toLowerCase()) return flat[k];
    }
    return undefined;
  };
  const raw = {
    users: pick('users', 'COUNT(ROWID)'),
    compilesOk: pick('ok_sum', 'SUM(ok)'),
    blocked: pick('blocked_sum', 'SUM(blocked)'),
    issuesCaught: pick('issues_sum', 'SUM(issues)'),
    timeouts: pick('timeouts_sum', 'SUM(timeouts)'),
  };
  if (raw.users === undefined) return null;
  const out = {};
  for (const [k, v] of Object.entries(raw)) out[k] = toInt(v);
  out.updatedAt = new Date(nowMs).toISOString();
  return out;
}

/** Extracts the route ("report" | "totals" | "") from a request URL. */
function routeOf(url) {
  let pathname;
  try {
    pathname = new URL(url, 'http://x').pathname;
  } catch (e) {
    return '';
  }
  const parts = pathname.split('/').filter(Boolean);
  const last = parts[parts.length - 1] || '';
  return last === 'report' || last === 'totals' ? last : '';
}

module.exports = {
  TABLE,
  MAX_DELTA,
  RATE_LIMIT_MS,
  TOTALS_TTL_MS,
  MAX_BODY_BYTES,
  validateReport,
  isAllZero,
  rateLimitRemaining,
  mergeRow,
  newRow,
  toInt,
  totalsFromRows,
  totalsFromZcql,
  routeOf,
};
