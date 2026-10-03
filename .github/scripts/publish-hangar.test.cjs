/*
 * Copyright 2026 Intave
 *
 * This software is licensed under the PolyForm Perimeter License 1.0.0.
 * You may use this software for any purpose, except for providing to
 * others any product that competes with the software.
 *
 * A copy of the license is available at:
 *   https://polyformproject.org/licenses/perimeter/1.0.0/
 */

const assert = require('node:assert/strict');
const { test } = require('node:test');
const publish = require('./publish-hangar.cjs');

function scenario({ tag = 'v2026.09.18', existing = 404, project = 200, auth = 200, upload = 200, stale = false } = {}) {
  const requests = [];
  const notices = [];
  const sha = 'abcdef1234567890';
  const context = { ref: tag ? `refs/tags/${tag}` : 'refs/heads/master', sha, repo: { owner: 'intave', repo: 'intave' } };
  const core = { notice: text => notices.push(text), info() {}, setSecret: token => assert.equal(token, 'test-jwt') };
  const github = { rest: { repos: {
    getBranch: async () => {
      assert.equal(tag, null, 'Tags must publish even when master has moved');
      return { data: { commit: { sha: stale ? 'newer-commit' : sha } } };
    },
    getReleaseByTag: async ({ tag: releaseTag }) => {
      assert.equal(releaseTag, tag || 'nightly');
      return { data: { body: 'GitHub release notes', html_url: 'https://github.com/intave/intave/releases' } };
    },
  } } };
  const fetch = async (url, options) => {
    const path = new URL(url).pathname;
    requests.push({ url, options });
    if (path.endsWith('/authenticate')) {
      assert.equal(options.method, 'POST');
      assert.equal(new URL(url).searchParams.get('apiKey'), 'test-api-key');
      return new Response(JSON.stringify({ token: 'test-jwt' }), { status: auth });
    }
    assert.equal(options.headers.Authorization, 'test-jwt');
    if (path === '/api/v1/projects/intave') return new Response('{}', { status: project });
    if (path.includes('/versions/')) return new Response('{}', { status: existing });
    assert.equal(path, '/api/v1/projects/intave/upload');
    assert.equal(options.method, 'POST');
    return new Response('{}', { status: upload });
  };
  const run = () => publish({ core, context, github }, {
    fetch, apiKey: 'test-api-key',
    readArtifact: async path => {
      assert.equal(path, 'build/libs/Intave-bundled.jar');
      return Buffer.from('bundled-jar-bytes');
    },
  });
  return { run, requests, notices };
}

for (const [tag, version, channel] of [
  ['v2026.09.18', 'v2026.09.18', 'Release'],
  ['2026.09.18', '2026.09.18', 'Release'],
  [null, 'dev-abcdef123456', 'Snapshot'],
]) {
  test(`publishes ${version} to ${channel} with the bundled artifact and GitHub notes`, async () => {
    const { run, requests } = scenario({ tag });
    await run();
    const form = requests.at(-1).options.body;
    const metadata = JSON.parse(await form.get('versionUpload').text());
    assert.equal(metadata.version, version);
    assert.equal(metadata.channel, channel);
    assert.equal(metadata.description, 'GitHub release notes');
    assert.deepEqual(metadata.platformDependencies, { PAPER: ['1.8.8-26.3'] });
    assert.deepEqual(metadata.files, [{ platforms: ['PAPER'] }]);
    assert.equal(form.get('files').name, 'Intave-bundled.jar');
    assert.equal(await form.get('files').text(), 'bundled-jar-bytes');
  });
}

test('skips an already published version without another upload', async () => {
  const { run, requests, notices } = scenario({ existing: 200 });
  await run();
  assert.equal(requests.length, 3);
  assert.match(notices[0], /already contains/);
});

test('skips stale nightly builds before contacting Hangar', async () => {
  const { run, requests, notices } = scenario({ tag: null, stale: true });
  await run();
  assert.equal(requests.length, 0);
  assert.match(notices[0], /newer commit/);
});

test('fails clearly when the secret is missing', async () => {
  await assert.rejects(publish({}, { apiKey: '' }), /HANGAR_API_TOKEN/);
});

for (const [options, message, count] of [
  [{ auth: 401 }, /authentication failed: HTTP 401/, 1],
  [{ project: 404 }, /project intave is unavailable: HTTP 404/, 2],
  [{ existing: 403 }, /version lookup failed: HTTP 403/, 3],
  [{ existing: 500 }, /version lookup failed: HTTP 500/, 3],
  [{ upload: 400 }, /upload failed: HTTP 400/, 4],
]) {
  test(`fails without treating ${JSON.stringify(options)} as a missing version`, async () => {
    const { run, requests } = scenario(options);
    await assert.rejects(run(), message);
    assert.equal(requests.length, count);
  });
}
