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

const { readFile } = require('node:fs/promises');

// Hangar's upload contract: https://github.com/HangarMC/hangar-publish-plugin
module.exports = async function publishHangar({ core, context, github }, {
  fetch = globalThis.fetch,
  readArtifact = readFile,
  apiKey = process.env.HANGAR_API_TOKEN,
} = {}) {
  if (!apiKey) {
    throw new Error('Set the HANGAR_API_TOKEN Actions secret with create_version and view_public_info permissions, then re-run this workflow.');
  }

  const isRelease = context.ref.startsWith('refs/tags/');
  if (!isRelease) {
    const { data: branch } = await github.rest.repos.getBranch({ ...context.repo, branch: 'master' });
    if (branch.commit.sha !== context.sha) {
      core.notice('Skipping Hangar upload because master has a newer commit.');
      return;
    }
  }
  const version = isRelease ? context.ref.slice('refs/tags/'.length) : `dev-${context.sha.slice(0, 12)}`;
  const channel = isRelease ? 'Release' : 'Snapshot';
  const base = 'https://hangar.papermc.io/api/v1';
  const headers = { 'User-Agent': 'intave/intave GitHub Actions (https://github.com/intave/intave)' };
  const request = (path, options = {}) => fetch(`${base}${path}`, {
    ...options,
    headers,
    redirect: 'error',
    signal: AbortSignal.timeout(120000),
  });
  const authenticate = await request(`/authenticate?apiKey=${encodeURIComponent(apiKey)}`, { method: 'POST' });
  if (!authenticate.ok) {
    throw new Error(`Hangar authentication failed: HTTP ${authenticate.status}`);
  }
  const { token } = await authenticate.json();
  if (!token) throw new Error('Hangar authentication returned no token.');
  core.setSecret(token);
  headers.Authorization = token;

  // Check the project first: a missing project must not be mistaken for a missing version.
  const projectResponse = await request('/projects/intave');
  if (!projectResponse.ok) {
    throw new Error(`Hangar project intave is unavailable: HTTP ${projectResponse.status}. Check project visibility and token access.`);
  }
  const existing = await request(`/projects/intave/versions/${encodeURIComponent(version)}`);
  if (existing.ok) {
    core.notice(`Hangar already contains ${version}; skipping upload.`);
    return;
  }
  if (existing.status !== 404) {
    throw new Error(`Hangar version lookup failed: HTTP ${existing.status}`);
  }

  const { data: release } = await github.rest.repos.getReleaseByTag({
    ...context.repo,
    tag: isRelease ? version : 'nightly',
  });
  const body = new FormData();
  body.append('versionUpload', new Blob([JSON.stringify({
    version,
    channel,
    description: release.body || `Release [${version}](${release.html_url}).`,
    platformDependencies: { PAPER: ['1.8.8-26.3'] },
    pluginDependencies: { PAPER: [] },
    files: [{ platforms: ['PAPER'] }],
  })], { type: 'application/json' }));
  body.append('files', new Blob([await readArtifact('build/libs/Intave-bundled.jar')], {
    type: 'application/octet-stream',
  }), 'Intave-bundled.jar');

  const uploaded = await request('/projects/intave/upload', { method: 'POST', body });
  if (!uploaded.ok) {
    throw new Error(`Hangar upload failed: HTTP ${uploaded.status}. Check that the ${channel} channel exists, the Paper version range is available, and the token has create_version permission.`);
  }
  core.info(`Published Intave ${version} to Hangar (${channel}).`);
};
