#!/usr/bin/env node
/**
 * Generates:
 *  - test-run.log  (self-contained log with ALL resolved versions, digests & results AT THE TOP,
 *                   followed by provision, runner, velocity, panel, wings, and webhook-sink logs)
 *  - report.md     (GitHub-flavored Markdown report with version matrix at top)
 *  - report.json   (structured JSON artifact)
 *  - report.html   (standalone visual HTML report with collapsible log sections)
 */
import fs from 'node:fs';
import path from 'node:path';

const resultsDir = process.argv[2] || process.env.RESULTS_DIR || '.';
const versionsPath = path.join(resultsDir, 'versions.json');
const resultsPath = path.join(resultsDir, 'results.json');
const logsDir = path.join(resultsDir, 'logs');

function readJsonSafe(file, fallback = {}) {
  try {
    return JSON.parse(fs.readFileSync(file, 'utf8'));
  } catch {
    return fallback;
  }
}

function readTextSafe(file) {
  try {
    return fs.readFileSync(file, 'utf8');
  } catch {
    return null;
  }
}

const versions = readJsonSafe(versionsPath, {});
const suite = readJsonSafe(resultsPath, {
  panel: versions.panel?.name || 'unknown',
  total: 0,
  passed: 0,
  failed: 0,
  results: []
});

const logSections = [
  { key: 'provision',    title: 'PROVISIONING LOG (provision.log)',            file: path.join(logsDir, 'provision.log') },
  { key: 'runner',       title: 'MC BOT TEST RUNNER LOG (runner.log)',         file: path.join(logsDir, 'runner.log') },
  { key: 'velocity',     title: 'VELOCITY + VPP PLUGIN LOG (velocity.log)',    file: path.join(logsDir, 'velocity.log') },
  { key: 'panel',        title: 'LIVE PANEL LOG (panel.log)',                  file: path.join(logsDir, 'panel.log') },
  { key: 'wings',        title: 'LIVE WINGS DAEMON LOG (wings.log)',           file: path.join(logsDir, 'wings.log') },
  { key: 'webhook-sink', title: 'WEBHOOK & CATALOG SINK LOG (webhook-sink.log)', file: path.join(logsDir, 'webhook-sink.log') }
].map(s => ({ ...s, content: readTextSafe(s.file) }))
 .filter(s => s.content !== null && s.content.trim().length > 0);

const panelName = versions.panel?.name || suite.panel || 'unknown';
const overallStatus = suite.failed === 0 && suite.total > 0 && !suite.fatalError ? 'PASSED' : 'FAILED';
const generatedAt = new Date().toISOString();

const combined = {
  generatedAt,
  status: overallStatus,
  versions,
  summary: {
    total: suite.total,
    passed: suite.passed,
    failed: suite.failed,
    startedAt: suite.startedAt || null,
    finishedAt: suite.finishedAt || null,
    fatalError: suite.fatalError || null
  },
  results: suite.results || [],
  logFiles: logSections.map(s => `logs/${path.basename(s.file)}`)
};

// 1. Structured JSON
fs.writeFileSync(path.join(resultsDir, 'report.json'), JSON.stringify(combined, null, 2));

const shortDigest = (d) => {
  if (!d) return 'n/a';
  const idx = d.indexOf('sha256:');
  return idx >= 0 ? d.slice(idx, idx + 19) + '…' : d;
};

// 2. Unified Self-Contained test-run.log (ALL versions & results AT THE VERY TOP, full logs below)
const sep = '='.repeat(80);
const subSep = '-'.repeat(80);
const logLines = [
  sep,
  `VELOCITYPTEROPOWER LIVE E2E TEST LOG — ${panelName.toUpperCase()}`,
  sep,
  `Status            : ${overallStatus} (${suite.passed}/${suite.total} passed, ${suite.failed} failed)`,
  `Generated At      : ${generatedAt}`,
  `Started At        : ${suite.startedAt || 'n/a'}`,
  `Finished At       : ${suite.finishedAt || 'n/a'}`,
  `Host OS           : ${versions.host?.os || 'unknown'}`,
  `Container Runtime : ${versions.host?.containerRuntime || 'unknown'}`,
  '',
  '--- VERSIONS USED (:latest RESOLVED) ---',
  `VPP Plugin        : ${versions.vpp?.version || 'unknown'} (branch=${versions.vpp?.gitBranch || '?'}, commit=${versions.vpp?.gitCommit || '?'}${versions.vpp?.gitDirty ? ', dirty' : ''})`,
  `Panel (${panelName}) : ${versions.panel?.version || 'unknown'}`,
  `  Panel Image     : ${versions.panel?.image || 'n/a'}`,
  `  Panel Digest    : ${versions.panel?.digest || 'n/a'}`,
  `Wings Daemon      : ${versions.wings?.version || 'unknown'}`,
  `  Wings Image     : ${versions.wings?.image || 'n/a'}`,
  `  Wings Digest    : ${versions.wings?.digest || 'n/a'}`,
  `Velocity Proxy    : ${versions.velocity?.version || 'unknown'} build #${versions.velocity?.build ?? '?'} (${versions.velocity?.file || 'n/a'})`,
  `  Velocity SHA256 : ${versions.velocity?.sha256 || 'n/a'}`,
  `Java Runtime      : ${versions.velocity?.javaVersion || 'Java 25'}`,
  `MC Backend        : PicoLimbo ${versions.backend?.version || 'unknown'} (Quozul/PicoLimbo Wings Egg)`,
  `MC Test Client    : minecraft-protocol (MC ${suite.mcProtocolVersion || '1.21.4'})`,
  '',
  '--- SCENARIO RESULTS ---'
];

if (combined.results.length === 0) {
  logLines.push('(No scenario results recorded — check PROVISIONING LOG below for early startup failure)');
} else {
  for (const r of combined.results) {
    const dur = `${(r.durationMs / 1000).toFixed(2)}s`.padStart(7);
    logLines.push(`[${r.status.padEnd(4)}] ${dur}  ${r.id.padEnd(32)} ${r.name}${r.detail ? ' :: ' + r.detail : ''}`);
  }
}

if (suite.fatalError) {
  logLines.push('', '--- FATAL ERROR ---', suite.fatalError);
}

for (const sec of logSections) {
  logLines.push('', sep, sec.title, subSep, sec.content.trimEnd());
}

logLines.push('', sep, 'END OF VELOCITYPTEROPOWER LIVE E2E TEST LOG', sep, '');
fs.writeFileSync(path.join(resultsDir, 'test-run.log'), logLines.join('\n'));

// 3. Markdown Report (versions at the very top + summary + log pointers)
const mdLines = [
  `# VelocityPteroPower Live E2E Test Report — \`${panelName}\``,
  '',
  `**Overall Status:** ${overallStatus === 'PASSED' ? '✅ PASSED' : '❌ FAILED'} (${suite.passed}/${suite.total} passed, ${suite.failed} failed)  `,
  `**Generated At:** \`${generatedAt}\`  `,
  `**VPP Plugin:** \`${versions.vpp?.version || 'unknown'}\` (\`${versions.vpp?.gitBranch || '?'}\` @ \`${versions.vpp?.gitCommit || '?'}\`${versions.vpp?.gitDirty ? ' dirty' : ''})  `,
  `**Runtime:** \`${versions.host?.containerRuntime || 'Docker/Podman'}\` on \`${versions.host?.os || 'Linux'}\``,
  '',
  '## Component Versions Tested (`:latest` Resolved)',
  '',
  '| Component | Resolved Version | Image / Artifact | Digest / SHA |',
  '|---|---|---|---|',
  `| **Panel (${panelName})** | \`${versions.panel?.version || 'unknown'}\` | \`${versions.panel?.image || 'n/a'}\` | \`${shortDigest(versions.panel?.digest)}\` |`,
  `| **Wings Daemon** | \`${versions.wings?.version || 'unknown'}\` | \`${versions.wings?.image || 'n/a'}\` | \`${shortDigest(versions.wings?.digest)}\` |`,
  `| **Velocity Proxy** | \`${versions.velocity?.version || 'unknown'} #${versions.velocity?.build ?? '?'}\` | \`${versions.velocity?.file || 'n/a'}\` | \`${shortDigest(versions.velocity?.sha256 ? 'sha256:' + versions.velocity.sha256 : '')}\` |`,
  `| **Java Runtime** | \`${versions.velocity?.javaVersion || 'Java 25'}\` | \`eclipse-temurin:25-jre-alpine\` | — |`,
  `| **MC Backend (PicoLimbo)** | \`${versions.backend?.version || 'unknown'}\` | \`Quozul/PicoLimbo\` (Wings Egg) | — |`,
  `| **MC Test Client** | \`MC ${suite.mcProtocolVersion || '1.21.4'}\` | \`minecraft-protocol\` (Node 22) | — |`,
  '',
  '## Automated Minecraft Client Test Scenarios',
  '',
  '| # | Scenario | Status | Duration | Details |',
  '|---|---|---|---|---|'
];

if (combined.results.length === 0) {
  mdLines.push('| — | *Suite did not reach scenario execution (check `test-run.log` / `logs/provision.log`)* | ❌ FAIL | — | — |');
} else {
  for (const r of combined.results) {
    const icon = r.status === 'PASS' ? '✅ PASS' : '❌ FAIL';
    const detail = String(r.detail || '').replace(/\|/g, '\\|').replace(/\n/g, ' ');
    mdLines.push(`| \`${r.id}\` | ${r.name} | ${icon} | ${(r.durationMs / 1000).toFixed(2)}s | ${detail} |`);
  }
}

if (suite.fatalError) {
  mdLines.push('', '## Fatal Error', '', '```', suite.fatalError, '```');
}

mdLines.push(
  '',
  '## Captured Logs',
  '',
  '- Combined versioned log: `test-run.log`',
  ...logSections.map(s => `- ${s.title}: \`logs/${path.basename(s.file)}\``)
);

fs.writeFileSync(path.join(resultsDir, 'report.md'), mdLines.join('\n') + '\n');

// 4. Standalone Visual HTML Report (versions at top + collapsible logs below)
const esc = (s) => String(s ?? '')
  .replace(/&/g, '&amp;')
  .replace(/</g, '&lt;')
  .replace(/>/g, '&gt;');

const html = `<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>VPP Live E2E Report — ${esc(panelName)}</title>
<style>
  :root { color-scheme: light dark; --bg: #0f1117; --card: #161a23; --fg: #e6edf3; --muted: #8b949e; --border: #2b3240; --ok: #2ea043; --fail: #f85149; --code: #0d1016; }
  @media (prefers-color-scheme: light) { :root { --bg: #f6f8fa; --card: #fff; --fg: #1f2328; --muted: #656d76; --border: #d0d7de; --ok: #1a7f37; --fail: #cf222e; --code: #f0f3f6; } }
  body { margin: 0; padding: 24px; background: var(--bg); color: var(--fg); font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", sans-serif; line-height: 1.5; }
  .wrap { max-width: 1020px; margin: 0 auto; display: flex; flex-direction: column; gap: 18px; }
  .card { background: var(--card); border: 1px solid var(--border); border-radius: 10px; padding: 18px; }
  h1, h2 { margin: 0 0 10px; }
  .badge { display: inline-block; padding: 3px 10px; border-radius: 999px; font-size: 12px; font-weight: 700; color: #fff; background: ${overallStatus === 'PASSED' ? 'var(--ok)' : 'var(--fail)'}; }
  table { width: 100%; border-collapse: collapse; font-size: 13px; }
  th, td { text-align: left; padding: 8px 10px; border-bottom: 1px solid var(--border); }
  th { color: var(--muted); font-size: 11px; text-transform: uppercase; letter-spacing: 0.04em; }
  code { font-family: ui-monospace, SFMono-Regular, Menlo, monospace; font-size: 12px; background: rgba(127,127,127,0.14); padding: 1px 5px; border-radius: 4px; }
  .pass { color: var(--ok); font-weight: 700; }
  .fail { color: var(--fail); font-weight: 700; }
  details { border: 1px solid var(--border); border-radius: 8px; padding: 8px 12px; margin-bottom: 8px; }
  summary { cursor: pointer; font-weight: 600; font-size: 13px; }
  pre { margin: 10px 0 0; padding: 12px; background: var(--code); border-radius: 6px; overflow-x: auto; max-height: 420px; font-size: 11.5px; line-height: 1.4; }
</style>
</head>
<body>
<div class="wrap">
  <div class="card">
    <h1>VelocityPteroPower Live E2E Report — <code>${esc(panelName)}</code> <span class="badge">${esc(overallStatus)}</span></h1>
    <div style="color:var(--muted);font-size:13px;">
      ${suite.passed}/${suite.total} passed • VPP <code>${esc(versions.vpp?.version)}</code> (<code>${esc(versions.vpp?.gitBranch)}</code> @ <code>${esc(versions.vpp?.gitCommit)}</code>) • Generated <code>${esc(generatedAt)}</code>
    </div>
  </div>
  <div class="card">
    <h2>Resolved Component Versions (:latest)</h2>
    <table>
      <thead><tr><th>Component</th><th>Version</th><th>Image / Artifact</th><th>Digest / SHA</th></tr></thead>
      <tbody>
        <tr><td><strong>Panel (${esc(panelName)})</strong></td><td><code>${esc(versions.panel?.version)}</code></td><td><code>${esc(versions.panel?.image)}</code></td><td><code>${esc(shortDigest(versions.panel?.digest))}</code></td></tr>
        <tr><td><strong>Wings Daemon</strong></td><td><code>${esc(versions.wings?.version)}</code></td><td><code>${esc(versions.wings?.image)}</code></td><td><code>${esc(shortDigest(versions.wings?.digest))}</code></td></tr>
        <tr><td><strong>Velocity Proxy</strong></td><td><code>${esc(versions.velocity?.version)} #${esc(versions.velocity?.build)}</code></td><td><code>${esc(versions.velocity?.file)}</code></td><td><code>${esc(shortDigest(versions.velocity?.sha256 ? 'sha256:' + versions.velocity.sha256 : ''))}</code></td></tr>
        <tr><td><strong>Java Runtime</strong></td><td><code>${esc(versions.velocity?.javaVersion)}</code></td><td><code>eclipse-temurin:25-jre-alpine</code></td><td>—</td></tr>
        <tr><td><strong>MC Backend (PicoLimbo)</strong></td><td><code>${esc(versions.backend?.version)}</code></td><td><code>Quozul/PicoLimbo</code></td><td>—</td></tr>
        <tr><td><strong>Host / Runtime</strong></td><td><code>${esc(versions.host?.containerRuntime)}</code></td><td><code>${esc(versions.host?.os)}</code></td><td>—</td></tr>
      </tbody>
    </table>
  </div>
  <div class="card">
    <h2>Automated Minecraft Client Scenarios</h2>
    <table>
      <thead><tr><th>ID</th><th>Scenario</th><th>Status</th><th>Duration</th><th>Details</th></tr></thead>
      <tbody>
        ${combined.results.length === 0
          ? '<tr><td colspan="5">No scenario results recorded — inspect provisioning/container logs below.</td></tr>'
          : combined.results.map(r => `<tr>
          <td><code>${esc(r.id)}</code></td>
          <td>${esc(r.name)}</td>
          <td class="${r.status === 'PASS' ? 'pass' : 'fail'}">${esc(r.status)}</td>
          <td>${(r.durationMs / 1000).toFixed(2)}s</td>
          <td>${esc(r.detail)}</td>
        </tr>`).join('')}
      </tbody>
    </table>
  </div>
  <div class="card">
    <h2>Captured Run Logs (Also combined in <code>test-run.log</code>)</h2>
    ${logSections.length === 0
      ? '<p style="color:var(--muted);margin:0;">No container logs captured.</p>'
      : logSections.map(s => `<details>
          <summary>${esc(s.title)}</summary>
          <pre>${esc(s.content)}</pre>
        </details>`).join('')}
  </div>
</div>
</body>
</html>
`;

fs.writeFileSync(path.join(resultsDir, 'report.html'), html);
console.log(`[report] Generated ${path.join(resultsDir, 'test-run.log')} and report.{md,json,html}`);
