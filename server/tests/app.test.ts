import assert from 'node:assert/strict';
import { mkdtempSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import test from 'node:test';
import { createApp } from '../src/app.ts';
import { VERSION } from '../src/config.ts';

function testApp() {
  const dir = mkdtempSync(join(tmpdir(), 'ryanmusic-'));
  return createApp({ webRoot: dir, cacheDir: join(dir, 'cache') });
}

test('api.php rejects missing params', async () => {
  const app = testApp();
  const res = await app.request('/api.php');
  assert.equal(res.status, 400);
  assert.match(await res.text(), /缺少请求参数/);
});

test('GET /health returns ok and version without auth', async () => {
  const app = testApp();
  const res = await app.request('/health');
  assert.equal(res.status, 200);
  assert.match(res.headers.get('content-type') || '', /application\/json/);
  const json = (await res.json()) as { ok: boolean; version: string };
  assert.equal(json.ok, true);
  assert.equal(json.version, VERSION);
});

test('xhr search without fields returns 403 payload', async () => {
  const app = testApp();
  const res = await app.request('/', {
    method: 'POST',
    headers: {
      'X-Requested-With': 'XMLHttpRequest',
      'Content-Type': 'application/x-www-form-urlencoded',
    },
    body: 'foo=1',
  });
  assert.equal(res.status, 200);
  const json = (await res.json()) as { code: number };
  assert.equal(json.code, 403);
});

test('missing frontend build returns a clear error', async () => {
  const app = testApp();
  const res = await app.request('/');
  assert.equal(res.status, 500);
  assert.match(await res.text(), /前端未构建/);
});
