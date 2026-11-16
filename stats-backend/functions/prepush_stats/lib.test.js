'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const lib = require('./lib');

const good = {
  installId: '3F2504E0-4F89-11D3-9A0C-0305E82C3301',
  pluginVersion: '2.8.0',
  ideBuild: 'IU-243.21565.193',
  ok: 3, blocked: 1, issues: 7, timeouts: 0,
};

test('valid report normalises installId and keeps deltas', () => {
  const r = lib.validateReport(good);
  assert.equal(r.ok, true);
  assert.equal(r.value.installId, good.installId.toLowerCase());
  assert.deepEqual(r.value.deltas, { ok: 3, blocked: 1, issues: 7, timeouts: 0 });
});

test('deltas clamp to 500', () => {
  const r = lib.validateReport({ ...good, issues: 10000 });
  assert.equal(r.value.deltas.issues, 500);
});

test('missing delta counts as zero, ideBuild optional', () => {
  const r = lib.validateReport({ installId: good.installId, pluginVersion: '2.8.0', ok: 1 });
  assert.equal(r.ok, true);
  assert.deepEqual(r.value.deltas, { ok: 1, blocked: 0, issues: 0, timeouts: 0 });
  assert.equal(r.value.ideBuild, '');
});

test('rejects bad shapes', () => {
  for (const bad of [
    null, [], 'x',
    { ...good, installId: 'short' },
    { ...good, installId: "x'; DROP TABLE--" },
    { ...good, pluginVersion: '' },
    { ...good, ideBuild: 'bad build!' },
    { ...good, ok: -1 },
    { ...good, ok: 1.5 },
    { ...good, ok: '3' },
  ]) {
    assert.equal(lib.validateReport(bad).ok, false, JSON.stringify(bad));
  }
});

test('rate limit: one call per minute', () => {
  const now = 1_000_000;
  assert.equal(lib.rateLimitRemaining(null, now), 0);
  assert.equal(lib.rateLimitRemaining(String(now - 60_000), now), 0);
  assert.equal(lib.rateLimitRemaining(String(now - 10_000), now), 50_000);
});

test('mergeRow adds deltas onto string columns', () => {
  const report = lib.validateReport(good).value;
  const row = lib.mergeRow({ ROWID: '9', ok: '10', blocked: '2', issues: '5', timeouts: null }, report, 42);
  assert.deepEqual(row, {
    ROWID: '9', plugin_version: '2.8.0', ide_build: 'IU-243.21565.193', last_report_ms: 42,
    ok: 13, blocked: 3, issues: 12, timeouts: 0,
  });
});

test('totalsFromZcql reads aliases or expression keys', () => {
  const now = Date.UTC(2026, 0, 1);
  const a = lib.totalsFromZcql([{ prepush_stats: { users: '2', ok_sum: '5', blocked_sum: '1', issues_sum: '4', timeouts_sum: '0' } }], now);
  assert.deepEqual(a, { users: 2, compilesOk: 5, blocked: 1, issuesCaught: 4, timeouts: 0, updatedAt: '2026-01-01T00:00:00.000Z' });
  const b = lib.totalsFromZcql([{ prepush_stats: { 'COUNT(ROWID)': '1', 'SUM(ok)': '9', 'SUM(blocked)': null, 'SUM(issues)': '0', 'SUM(timeouts)': '2' } }], now);
  assert.equal(b.users, 1);
  assert.equal(b.compilesOk, 9);
  assert.equal(b.blocked, 0);
  assert.equal(lib.totalsFromZcql([{ prepush_stats: { foo: 1 } }], now), null);
  assert.equal(lib.totalsFromZcql([], now), null);
});

test('totalsFromRows sums', () => {
  const t = lib.totalsFromRows([{ ok: '1', blocked: '2', issues: '3', timeouts: '4' }, { ok: 10 }], 0);
  assert.equal(t.users, 2);
  assert.equal(t.compilesOk, 11);
  assert.equal(t.issuesCaught, 3);
});

test('routeOf', () => {
  assert.equal(lib.routeOf('/server/prepush_stats/report'), 'report');
  assert.equal(lib.routeOf('/server/prepush_stats/totals?x=1'), 'totals');
  assert.equal(lib.routeOf('/totals/'), 'totals');
  assert.equal(lib.routeOf('/server/prepush_stats/execute'), '');
});
