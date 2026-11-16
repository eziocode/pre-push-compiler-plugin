'use strict';

// Advanced I/O function (raw-http template).
//   POST <base>/report  — plugin uploads anonymous count deltas
//   GET  <base>/totals  — public aggregate for the GitHub Pages stats page
// <base> = https://<project-domain>/server/prepush_stats

const catalyst = require('zcatalyst-sdk-node');
const lib = require('./lib');

// Warm-container cache for /totals. Each container keeps its own copy; the
// Cache-Control header lets the browser/CDN cache too.
let totalsCache = null; // { value, expiresAt }

function send(res, status, data, extraHeaders) {
  res.writeHead(status, Object.assign({ 'Content-Type': 'application/json' }, extraHeaders || {}));
  res.end(data === undefined ? '' : JSON.stringify(data));
}

// Public read endpoint, no credentials. Do NOT also add the GitHub Pages
// origin under Authentication → Authorized Domains, or the header doubles.
const PUBLIC_CORS = {
  'Access-Control-Allow-Origin': '*',
  'Access-Control-Allow-Methods': 'GET, OPTIONS',
  'Access-Control-Allow-Headers': 'Content-Type',
  'Access-Control-Max-Age': '86400',
};

function readBody(req) {
  return new Promise((resolve, reject) => {
    if (req.body && typeof req.body === 'object') return resolve(req.body);
    if (typeof req.body === 'string') return resolve(parse(req.body));
    let data = '';
    let size = 0;
    req.on('data', (chunk) => {
      size += chunk.length;
      if (size > lib.MAX_BODY_BYTES) {
        reject(Object.assign(new Error('body too large'), { status: 413 }));
        req.destroy();
        return;
      }
      data += chunk;
    });
    req.on('end', () => resolve(parse(data)));
    req.on('error', reject);
  });
  function parse(s) {
    if (!s) return null;
    try {
      return JSON.parse(s);
    } catch (e) {
      return undefined; // distinguishes "bad JSON" from "empty"
    }
  }
}

async function findRow(zcql, installId) {
  // installId is validated against /^[a-z0-9-]{8,64}$/ — safe to inline.
  const result = await zcql.executeZCQLQuery(
    `SELECT * FROM ${lib.TABLE} WHERE install_id = '${installId}'`
  );
  const rows = (result || []).map((r) => r[lib.TABLE]).filter(Boolean);
  return rows[0] || null;
}

async function handleReport(app, req, res) {
  let body;
  try {
    body = await readBody(req);
  } catch (e) {
    return send(res, e.status || 400, { error: e.message });
  }
  if (body === undefined) return send(res, 400, { error: 'invalid JSON' });

  const v = lib.validateReport(body);
  if (!v.ok) return send(res, 400, { error: v.error });
  const report = v.value;
  const now = Date.now();

  const zcql = app.zcql();
  const table = app.datastore().table(lib.TABLE);
  const existing = await findRow(zcql, report.installId);

  if (existing) {
    const waitMs = lib.rateLimitRemaining(existing.last_report_ms, now);
    if (waitMs > 0) {
      return send(res, 429, { error: 'rate limited', retryAfterSeconds: Math.ceil(waitMs / 1000) },
        { 'Retry-After': String(Math.ceil(waitMs / 1000)) });
    }
    if (lib.isAllZero(report.deltas)) return send(res, 200, { accepted: true, changed: false });
    await table.updateRow(lib.mergeRow(existing, report, now));
  } else {
    if (lib.isAllZero(report.deltas)) return send(res, 200, { accepted: true, changed: false });
    try {
      await table.insertRow(lib.newRow(report, now));
    } catch (e) {
      // install_id is a unique column: a concurrent first report from the
      // same install loses the race. Treat as rate limited; client retries.
      if (/unique|duplicate/i.test(String(e && e.message))) {
        return send(res, 429, { error: 'rate limited', retryAfterSeconds: 60 }, { 'Retry-After': '60' });
      }
      throw e;
    }
  }
  totalsCache = null;
  return send(res, 200, { accepted: true, changed: true });
}

async function computeTotals(app) {
  const now = Date.now();
  try {
    const result = await app.zcql().executeZCQLQuery(
      `SELECT COUNT(ROWID) AS users, SUM(ok) AS ok_sum, SUM(blocked) AS blocked_sum, ` +
      `SUM(issues) AS issues_sum, SUM(timeouts) AS timeouts_sum FROM ${lib.TABLE}`
    );
    const parsed = lib.totalsFromZcql(result, now);
    if (parsed) return parsed;
    console.warn('Unrecognised aggregate shape, falling back to row scan:', JSON.stringify(result));
  } catch (e) {
    console.warn('Aggregate query failed, falling back to row scan:', e && e.message);
  }
  const table = app.datastore().table(lib.TABLE);
  const all = [];
  let nextToken;
  do {
    const page = await table.getPagedRows({ nextToken, maxRows: 200 });
    all.push(...(page.data || []));
    nextToken = page.more_records ? page.next_token : undefined;
  } while (nextToken);
  return lib.totalsFromRows(all, now);
}

async function handleTotals(app, res) {
  const now = Date.now();
  if (!totalsCache || totalsCache.expiresAt <= now) {
    totalsCache = { value: await computeTotals(app), expiresAt: now + lib.TOTALS_TTL_MS };
  }
  const maxAge = Math.max(0, Math.floor((totalsCache.expiresAt - now) / 1000));
  return send(res, 200, totalsCache.value,
    Object.assign({ 'Cache-Control': `public, max-age=${maxAge}` }, PUBLIC_CORS));
}

module.exports = async (req, res) => {
  try {
    const route = lib.routeOf(req.url);
    const method = req.method;

    if (route === 'totals' && method === 'OPTIONS') {
      res.writeHead(204, PUBLIC_CORS);
      return res.end();
    }
    if (route === 'totals' && method === 'GET') {
      const app = catalyst.initialize(req, { scope: 'admin' });
      return await handleTotals(app, res);
    }
    if (route === 'report' && method === 'POST') {
      const app = catalyst.initialize(req, { scope: 'admin' });
      return await handleReport(app, req, res);
    }
    if (route === 'report' || route === 'totals') {
      return send(res, 405, { error: 'method not allowed' });
    }
    return send(res, 404, { error: 'not found; use POST /report or GET /totals' });
  } catch (err) {
    console.error('prepush_stats error:', err);
    return send(res, 500, { error: 'internal error' });
  }
};
