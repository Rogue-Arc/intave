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

module.exports = async function prepareModrinth({ core, context, github }) {
  core.setOutput('publish', 'false');
  const isRelease = context.ref.startsWith('refs/tags/');
  const project = process.env.MODRINTH_ID;
  if (!project || !process.env.MODRINTH_TOKEN) {
    if (isRelease) {
      throw new Error('Modrinth release publishing requires MODRINTH_ID as a variable or secret and MODRINTH_TOKEN as a secret. Configure them and re-run this workflow.');
    }
    core.notice('Modrinth publishing is disabled. Set MODRINTH_ID as a variable or secret and MODRINTH_TOKEN as a secret to enable it.');
    return;
  }

  // Re-running an old workflow must not make an old commit the latest dev build.
  if (!isRelease) {
    const { data: branch } = await github.rest.repos.getBranch({
      ...context.repo,
      branch: 'master',
    });
    if (branch.commit.sha !== context.sha) {
      core.notice('Skipping Modrinth upload because master has a newer commit.');
      return;
    }
  }

  // Commit-based versions stay stable across retries and the moving nightly tag.
  const version = isRelease ? context.ref.slice('refs/tags/'.length) : `dev-${context.sha.slice(0, 12)}`;
  const response = await fetch(
    `https://api.modrinth.com/v2/project/${encodeURIComponent(project)}/version/${encodeURIComponent(version)}`,
    {
      headers: {
        'User-Agent': 'intave/intave GitHub Actions (https://github.com/intave/intave)',
        Authorization: process.env.MODRINTH_TOKEN,
      },
      signal: AbortSignal.timeout(30000),
    },
  );
  if (response.ok) {
    core.notice(`Modrinth already contains ${version}; skipping upload.`);
    return;
  }
  if (response.status !== 404) {
    throw new Error(`Modrinth version lookup failed: HTTP ${response.status}`);
  }

  const url = `${context.serverUrl}/${context.repo.owner}/${context.repo.repo}`;
  core.setOutput('version', version);
  core.setOutput('version_type', isRelease ? 'release' : 'alpha');
  core.setOutput('featured', isRelease ? 'true' : 'false');
  if (isRelease) {
    const { data: release } = await github.rest.repos.getReleaseByTag({
      ...context.repo,
      tag: version,
    });
    core.setOutput('changelog', release.body || `Release [${version}](${release.html_url}).`);
    core.setOutput('publish', 'true');
    return;
  }
  const { execFileSync } = require('node:child_process');
  const subject = execFileSync('git', ['log', '-1', '--format=%s'], { encoding: 'utf8' }).trim();
  core.setOutput('changelog', [
    `Development build from [${context.sha.slice(0, 7)}](${url}/commit/${context.sha}).`,
    '',
    subject,
    '',
    `[Build details](${url}/actions/runs/${context.runId})`,
    '',
  ].join('\n'));
  core.setOutput('publish', 'true');
};
