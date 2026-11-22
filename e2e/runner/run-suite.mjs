import fs from 'node:fs';
import path from 'node:path';
import { McBot, sleep } from './mc-bot.mjs';

const VELOCITY_HOST = process.env.VELOCITY_HOST || 'velocity';
const VELOCITY_PORT = Number(process.env.VELOCITY_PORT || 25577);
const BRIDGE_URL = process.env.BRIDGE_URL || 'http://velocity:19090';
const WEBHOOK_SINK_URL = process.env.WEBHOOK_SINK_URL || 'http://webhook-sink:19091';
const PANEL_TYPE = (process.env.PANEL_TYPE || 'pterodactyl').toLowerCase();
const PANEL_URL = (process.env.PANEL_URL || '').replace(/\/+$/, '');
const PANEL_API_KEY = process.env.PANEL_API_KEY || '';
const MC_VERSION = process.env.MC_VERSION || '1.21.4';
const RESULTS_DIR = process.env.RESULTS_DIR || '/results';
const SHARED_DIR = process.env.SHARED_DIR || '/shared';

let panelMeta = {};
try {
  panelMeta = JSON.parse(fs.readFileSync(path.join(SHARED_DIR, 'panel-meta.json'), 'utf8'));
} catch { /* loaded via env or bridge */ }

async function httpJson(url, opts = {}) {
  const res = await fetch(url, {
    ...opts,
    headers: {
      'Accept': 'application/json',
      'Content-Type': 'application/json',
      ...(opts.headers || {})
    }
  });
  const text = await res.text();
  let json = null;
  try { json = text ? JSON.parse(text) : null; } catch { json = { raw: text }; }
  return { status: res.status, ok: res.ok, json, text };
}

async function bridgeHealth() {
  return httpJson(`${BRIDGE_URL}/health`);
}

async function bridgeState() {
  const r = await httpJson(`${BRIDGE_URL}/state`);
  if (!r.ok) throw new Error(`Bridge /state failed (${r.status}): ${r.text}`);
  return r.json;
}

async function bridgeCommand(command, opts = {}) {
  const r = await httpJson(`${BRIDGE_URL}/command`, {
    method: 'POST',
    body: JSON.stringify({ command, ...opts })
  });
  if (!r.ok) throw new Error(`Bridge /command failed (${r.status}): ${r.text}`);
  return r.json;
}

async function setBotPermissions(username, permissions) {
  const r = await httpJson(`${BRIDGE_URL}/permissions`, {
    method: 'POST',
    body: JSON.stringify({ username, permissions })
  });
  if (!r.ok) throw new Error(`Bridge /permissions failed: ${r.text}`);
}

async function setExternalMaintenance(cfg) {
  const r = await httpJson(`${BRIDGE_URL}/maintenance-provider`, {
    method: 'POST',
    body: JSON.stringify(cfg)
  });
  if (!r.ok) throw new Error(`Bridge /maintenance-provider failed: ${r.text}`);
}

async function setRouting(cfg) {
  const r = await httpJson(`${BRIDGE_URL}/routing-provider`, {
    method: 'POST',
    body: JSON.stringify(cfg)
  });
  if (!r.ok) throw new Error(`Bridge /routing-provider failed: ${r.text}`);
}

function commandText(result) {
  return (result?.messages || []).join('\n');
}

function assertCommand(result, pattern, label) {
  const re = pattern instanceof RegExp ? pattern : new RegExp(pattern, 'i');
  const text = commandText(result);
  if (!re.test(text)) {
    throw new Error(`${label} did not match ${re}. Output: ${JSON.stringify(result?.messages || [])}`);
  }
}

function makeBot(username, serverHost) {
  return new McBot({
    host: VELOCITY_HOST,
    port: VELOCITY_PORT,
    username,
    version: MC_VERSION,
    serverHost: serverHost || null
  });
}

async function disconnectBots(bots) {
  for (const bot of bots) {
    try { bot.disconnect(); } catch { /* already gone */ }
  }
  await sleep(400);
}

async function panelServerState(serverName) {
  const id = panelMeta.servers?.[serverName];
  if (!id || !PANEL_URL || !PANEL_API_KEY) return 'unknown';
  const r = await httpJson(`${PANEL_URL}/api/client/servers/${id}/resources`, {
    headers: { Authorization: `Bearer ${PANEL_API_KEY}` }
  });
  if (!r.ok || !r.json) return `http-${r.status}`;
  if (PANEL_TYPE === 'calagopus') {
    return r.json.resources?.state || r.json.state || 'unknown';
  }
  return r.json.attributes?.current_state || 'unknown';
}

/**
 * Real-time server state directly from Live Wings WebSocket (via VPP's ServerStateCache),
 * avoiding Pterodactyl/Pelican's 20-second Redis cache on /api/client/.../resources.
 * Falls back to panelServerState() when WebSocket state is unavailable (e.g. after proxy shutdown).
 */
async function liveServerState(serverName) {
  try {
    const s = await bridgeState();
    const ws = s?.serverStateCache?.[serverName];
    if (ws && ws !== 'STALE_OR_NONE' && ws !== 'UNKNOWN') {
      return ws.toLowerCase();
    }
  } catch { /* bridge unavailable (e.g., proxy stopped) */ }
  return panelServerState(serverName);
}

async function panelPower(serverName, signal) {
  const id = panelMeta.servers?.[serverName];
  if (!id || !PANEL_URL || !PANEL_API_KEY) return;
  const body = PANEL_TYPE === 'calagopus' ? { action: signal } : { signal };
  await httpJson(`${PANEL_URL}/api/client/servers/${id}/power`, {
    method: 'POST',
    headers: { Authorization: `Bearer ${PANEL_API_KEY}` },
    body: JSON.stringify(body)
  });
}

async function waitUntil(desc, fn, timeoutMs = 90000, intervalMs = 1000) {
  const deadline = Date.now() + timeoutMs;
  let lastErr = null;
  while (Date.now() < deadline) {
    try {
      const val = await fn();
      if (val) return val;
    } catch (err) {
      lastErr = err;
    }
    await sleep(intervalMs);
  }
  throw new Error(`Timed out (${timeoutMs}ms) waiting for: ${desc}${lastErr ? ` (last error: ${lastErr.message})` : ''}`);
}

function findPlayerServer(state, username) {
  const p = (state.onlinePlayers || []).find(x => x.username.toLowerCase() === username.toLowerCase());
  return p ? p.server : null;
}

async function bridgeSuggest(command, opts = {}) {
  const r = await httpJson(`${BRIDGE_URL}/suggest`, {
    method: 'POST',
    body: JSON.stringify({ command, ...opts })
  });
  if (!r.ok) throw new Error(`Bridge /suggest failed (${r.status}): ${r.text}`);
  return r.json.suggestions || [];
}

async function bridgeFileRead(rel) {
  const r = await httpJson(`${BRIDGE_URL}/file?path=${encodeURIComponent(rel)}`);
  if (!r.ok) throw new Error(`Bridge GET /file ${rel} failed (${r.status}): ${r.text}`);
  return r.json.content;
}

async function bridgeFileWrite(rel, content) {
  const r = await httpJson(`${BRIDGE_URL}/file`, {
    method: 'POST',
    body: JSON.stringify({ path: rel, content })
  });
  if (!r.ok) throw new Error(`Bridge POST /file ${rel} failed (${r.status}): ${r.text}`);
}

function escapeRe(s) {
  return s.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
}

/** Set a scalar at `key` or `section.key` in a simple 2-space-indented YAML document. */
function yamlSet(text, dotted, value) {
  const rendered = typeof value === 'string' ? `"${value}"` : String(value);
  const lines = text.split('\n');
  const parts = dotted.split('.');
  if (parts.length === 1) {
    const re = new RegExp(`^${escapeRe(parts[0])}:`);
    const i = lines.findIndex((l) => re.test(l));
    if (i < 0) lines.push(`${parts[0]}: ${rendered}`);
    else lines[i] = `${parts[0]}: ${rendered}`;
    return lines.join('\n');
  }
  if (parts.length !== 2) throw new Error(`yamlSet supports one nesting level, got ${dotted}`);
  const head = lines.findIndex((l) => l.replace(/\s+$/, '') === `${parts[0]}:`);
  if (head < 0) throw new Error(`Section ${parts[0]} not found in config.yml`);
  const childRe = new RegExp(`^ {2}${escapeRe(parts[1])}:`);
  let j = head + 1;
  for (; j < lines.length && (/^\s/.test(lines[j]) || lines[j].trim() === ''); j++) {
    if (childRe.test(lines[j])) {
      lines[j] = `  ${parts[1]}: ${rendered}`;
      return lines.join('\n');
    }
  }
  lines.splice(j, 0, `  ${parts[1]}: ${rendered}`);
  return lines.join('\n');
}

async function reloadVpp() {
  const r = await bridgeCommand('ptero reload', { settleMs: 3000 });
  if (!r.ok) throw new Error(`/ptero reload failed: ${JSON.stringify(r)}`);
  await waitUntil('bridge healthy after reload', async () => {
    const h = await bridgeHealth();
    return h.ok && h.json?.vppLoaded === true && h.json?.apiClientReady === true;
  }, 30000, 500);
  return r;
}

/** Apply config.yml edits, /ptero reload, run fn, then always restore the original file and reload. */
async function withVppConfig(edits, fn) {
  const original = await bridgeFileRead('config.yml');
  let text = original;
  for (const [k, v] of Object.entries(edits)) text = yamlSet(text, k, v);
  await bridgeFileWrite('config.yml', text);
  await reloadVpp();
  try {
    return await fn();
  } finally {
    await bridgeFileWrite('config.yml', original);
    await reloadVpp();
  }
}

async function addonActive(id) {
  const h = (await bridgeHealth()).json;
  return (h.addons || []).some((a) => a.id === id && a.active);
}

async function webhookEvents() {
  const wh = await httpJson(`${WEBHOOK_SINK_URL}/webhooks`);
  return wh.json?.events || [];
}

async function stopGameServers() {
  await ensureServerStopped('survival');
  await ensureServerStopped('skywars');
  await ensureServerStopped('bedwars');
}

const results = [];

async function runScenario(id, name, fn) {
  const start = Date.now();
  process.stdout.write(`\n▶ [${id}] ${name} ... `);
  try {
    const detail = await fn();
    const durationMs = Date.now() - start;
    console.log(`PASS (${durationMs}ms)${detail ? ' — ' + detail : ''}`);
    results.push({ id, name, status: 'PASS', durationMs, detail: detail || '' });
  } catch (err) {
    const durationMs = Date.now() - start;
    console.log(`FAIL (${durationMs}ms)\n   ${err.stack || err.message}`);
    results.push({ id, name, status: 'FAIL', durationMs, detail: err.message || String(err) });
  }
}

async function ensureServerStopped(name) {
  const st = await liveServerState(name);
  if (st !== 'offline') {
    await panelPower(name, 'stop');
    await waitUntil(`${name} to become offline on live Wings`, async () => {
      return (await liveServerState(name)) === 'offline';
    }, 60000, 1000);
  }
}

async function main() {
  const suiteStartedAt = new Date().toISOString();
  console.log(`[vpp-e2e] Starting Live E2E Suite against panel=${PANEL_TYPE} velocity=${VELOCITY_HOST}:${VELOCITY_PORT}`);

  // Wait for Velocity + VPP Test Bridge to be healthy
  await waitUntil('Velocity + VPP Test Bridge /health', async () => {
    const h = await bridgeHealth();
    return h.ok && h.json?.vppLoaded === true && h.json?.apiClientReady === true;
  }, 120000, 1500);

  // Clear webhook sink before scenarios start
  await httpJson(`${WEBHOOK_SINK_URL}/webhooks`, { method: 'DELETE' }).catch(() => {});

  // 1. Panel auth, detected panel type, addons, and live Wings WebSocket subscription
  await runScenario('01-panel-auth-ws', 'Panel Auth, Type Detection & Live Wings WebSocket', async () => {
    const h = (await bridgeHealth()).json;
    if (h.panelType.toLowerCase() !== PANEL_TYPE) {
      throw new Error(`Expected panelType=${PANEL_TYPE}, got ${h.panelType}`);
    }
    const activeAddonIds = (h.addons || []).filter(a => a.active).map(a => a.id);
    for (const req of ['vpp-webhooks', 'vpp-scheduler', 'vpp-move-history']) {
      if (!activeAddonIds.includes(req)) {
        throw new Error(`Expected active addon ${req}, found: ${JSON.stringify(activeAddonIds)}`);
      }
    }
    // Wait for PanelWebSocketService to populate fresh state cache from live Wings
    const cache = await waitUntil('Wings WS live state cache populated', async () => {
      const s = await bridgeState();
      const vals = Object.values(s.serverStateCache || {});
      return vals.some(v => v !== 'STALE_OR_NONE') ? s.serverStateCache : false;
    }, 45000, 1500);
    const names = Object.keys(cache);
    for (const required of ['limbo', 'lobby-1', 'lobby-2', 'survival', 'skywars', 'bedwars']) {
      if (!names.includes(required)) {
        throw new Error(`Wings cache missing ${required}: ${JSON.stringify(cache)}`);
      }
    }
    const state = await bridgeState();
    if (typeof state.rateLimitRemaining !== 'number' || typeof state.rateLimitMax !== 'number') {
      throw new Error(`Rate-limit fields missing from bridge state: ${JSON.stringify(state)}`);
    }
    return `panel=${h.panelType}, addons=${activeAddonIds.join(',')}, wsServers=${names.length}, rateRemaining=${state.rateLimitRemaining}/${state.rateLimitMax}`;
  });

  // 2. Always-online limbo auto-started on Live Wings
  await runScenario('02-always-online-limbo', 'Always-Online Limbo Started on Live Wings', async () => {
    await waitUntil('limbo running on live Wings', async () => (await liveServerState('limbo')) === 'running', 90000, 1000);
    await waitUntil('lobby-1 running (minOnline=1)', async () => (await liveServerState('lobby-1')) === 'running', 90000, 1000);
    return 'limbo=running, lobby-1=running';
  });

  // 3. Cold-start on forced-host join -> limbo holding -> HYBRID ready + settle -> auto-transfer
  await runScenario('03-cold-start-forced-host', 'Cold Start via Forced-Host -> Limbo -> HYBRID Ready -> Transfer', async () => {
    await ensureServerStopped('survival');
    const alice = new McBot({
      host: VELOCITY_HOST,
      port: VELOCITY_PORT,
      username: 'Alice',
      version: MC_VERSION,
      serverHost: 'survival.vpp.test'
    });
    try {
      await alice.connect();
      // Alice should be held in limbo or lobby while survival boots
      const holdSrv = await waitUntil('Alice placed in holding server', async () => {
        const srv = findPlayerServer(await bridgeState(), 'Alice');
        return srv === 'limbo' || srv === 'lobby-1' || srv === 'lobby-2' ? srv : false;
      }, 20000, 500);

      // Live Wings starts survival container and VPP auto-transfers Alice after settle delay
      await waitUntil('Alice auto-transferred to survival', async () => {
        return findPlayerServer(await bridgeState(), 'Alice') === 'survival';
      }, 90000, 1000);

      const panelSt = await liveServerState('survival');
      if (panelSt !== 'running') throw new Error(`Expected survival running on live Wings, got ${panelSt}`);
      return `held in ${holdSrv} -> transferred to survival (wings=${panelSt})`;
    } finally {
      alice.disconnect();
    }
  });

  // 4. Idle shutdown & cancel-on-rejoin race
  await runScenario('04-idle-shutdown-and-cancel', 'Idle Shutdown Schedule, Cancel-on-Rejoin & Live Wings Stop', async () => {
    // Wait for shutdown deadline to be scheduled after Alice left survival in scenario 03
    await waitUntil('survival shutdown deadline scheduled', async () => {
      const s = await bridgeState();
      return Boolean(s.shutdownDeadlines?.survival);
    }, 25000, 1000);

    // Bob joins survival before the 12s timeout -> must cancel scheduled shutdown
    const bob = new McBot({
      host: VELOCITY_HOST,
      port: VELOCITY_PORT,
      username: 'Bob',
      version: MC_VERSION,
      serverHost: 'survival.vpp.test'
    });
    try {
      await bob.connect();
      await waitUntil('Bob connected to survival', async () => {
        return findPlayerServer(await bridgeState(), 'Bob') === 'survival';
      }, 20000, 500);

      await waitUntil('survival shutdown deadline cancelled', async () => {
        const s = await bridgeState();
        return !s.shutdownDeadlines?.survival;
      }, 15000, 500);
    } finally {
      bob.disconnect();
    }

    // Now that Bob disconnected, survival must actually stop on Live Wings!
    await waitUntil('survival stopped on live Wings after idle timeout', async () => {
      return (await liveServerState('survival')) === 'offline';
    }, 90000, 1000);
    return 'cancel-on-rejoin verified -> live Wings stopped survival';
  });

  // 5. Concurrent second player deduplication
  await runScenario('05-concurrent-queue-dedup', 'Concurrent Players Join Queue & Auto-Transfer Together', async () => {
    await ensureServerStopped('survival');
    const alice = new McBot({ host: VELOCITY_HOST, port: VELOCITY_PORT, username: 'Alice', version: MC_VERSION, serverHost: 'survival.vpp.test' });
    const bob = new McBot({ host: VELOCITY_HOST, port: VELOCITY_PORT, username: 'Bob', version: MC_VERSION, serverHost: 'survival.vpp.test' });
    try {
      await alice.connect();
      await sleep(800);
      await bob.connect();

      await waitUntil('Both Alice and Bob transferred to survival', async () => {
        const s = await bridgeState();
        return findPlayerServer(s, 'Alice') === 'survival' && findPlayerServer(s, 'Bob') === 'survival';
      }, 90000, 1000);
      return 'Alice + Bob both transferred after single cold start';
    } finally {
      alice.disconnect();
      bob.disconnect();
    }
  });

  // 6. Real Whitelist enforcement (from Live Panel/Wings whitelist.json) + Admin bypass
  await runScenario('06-whitelist-and-bypass', 'Live Panel Whitelist Enforcement, Reload & ptero.bypass', async () => {
    await ensureServerStopped('survival');
    const reloadResp = await bridgeCommand('ptero whitelistReload');
    if (!reloadResp.ok) throw new Error(`whitelistReload failed: ${JSON.stringify(reloadResp)}`);
    await sleep(1500);

    // Stranger is NOT in whitelist.json -> must be rejected WITHOUT starting survival!
    const stranger = new McBot({
      host: VELOCITY_HOST,
      port: VELOCITY_PORT,
      username: 'Stranger',
      version: MC_VERSION,
      serverHost: 'survival.vpp.test'
    });
    try {
      await stranger.connect();
      await sleep(1500);
      if (stranger.connected && findPlayerServer(await bridgeState(), 'Stranger') === 'survival') {
        throw new Error('Non-whitelisted Stranger was allowed onto survival!');
      }
      const st = await liveServerState('survival');
      if (st !== 'offline') {
        throw new Error(`Expected survival to remain offline after Stranger rejected, got ${st}`);
      }
    } finally {
      stranger.disconnect();
    }

    // Admin has ptero.bypass -> allowed to start & join survival
    await setBotPermissions('Admin', ['ptero.bypass', 'ptero.maxcap.bypass']);
    const admin = new McBot({
      host: VELOCITY_HOST,
      port: VELOCITY_PORT,
      username: 'Admin',
      version: MC_VERSION,
      serverHost: 'survival.vpp.test'
    });
    try {
      await admin.connect();
      await waitUntil('Admin transferred to survival via ptero.bypass', async () => {
        return findPlayerServer(await bridgeState(), 'Admin') === 'survival';
      }, 90000, 1000);
      return 'Stranger blocked (survival stayed offline); Admin bypassed via ptero.bypass';
    } finally {
      admin.disconnect();
    }
  });

  // 7. Server Groups (skywars + bedwars linked start + group idle guard) & Start Password unlock
  await runScenario('07-groups-and-start-password', 'Server Groups Linked Start + Single-Use Start Password (/ptero unlock)', async () => {
    await ensureServerStopped('survival');
    await ensureServerStopped('skywars');
    await ensureServerStopped('bedwars');

    // bedwars has startPassword="e2e-unlock-pass" -> Alice denied before unlock
    const alice = new McBot({ host: VELOCITY_HOST, port: VELOCITY_PORT, username: 'Alice', version: MC_VERSION });
    try {
      await alice.connect();
      await waitUntil('Alice on lobby/limbo', async () => Boolean(findPlayerServer(await bridgeState(), 'Alice')), 15000, 500);
      await sleep(2500); // clear any initial lobby-start cooldown

      // Try switching to bedwars without unlocking -> must remain offline
      alice.sendCommand('server bedwars');
      await sleep(2500);
      const bwBefore = await liveServerState('bedwars');
      if (bwBefore !== 'offline') {
        throw new Error(`Expected bedwars offline before password unlock, got ${bwBefore}`);
      }

      // Unlock single-use password as Alice via bridge command
      await bridgeCommand('ptero unlock bedwars e2e-unlock-pass', { asPlayer: 'Alice' });
      await sleep(800);

      // Now request skywars (grouped with bedwars) -> BOTH skywars AND bedwars start on Live Wings!
      alice.sendCommand('server skywars');
      await waitUntil('skywars AND grouped peer bedwars both running on live Wings', async () => {
        const [sw, bw] = await Promise.all([liveServerState('skywars'), liveServerState('bedwars')]);
        return sw === 'running' && bw === 'running';
      }, 90000, 1000);

      await waitUntil('Alice transferred to skywars', async () => {
        return findPlayerServer(await bridgeState(), 'Alice') === 'skywars';
      }, 45000, 1000);
      return 'bedwars gated before unlock; skywars start triggered grouped peer bedwars on Live Wings';
    } finally {
      alice.disconnect();
    }
  });

  // 8. Max-Online Cap queueing + bypass (maxOnlineServers=2; skywars+bedwars running -> survival queued!)
  await runScenario('08-max-online-cap', 'Max-Online Cap Queueing & Free-Slot Auto-Start', async () => {
    await ensureServerStopped('survival');
    await ensureServerStopped('skywars');
    await ensureServerStopped('bedwars');

    // Connect Alice FIRST and let any lobby-start cooldown expire so /server survival isn't cooldown-denied
    const alice = new McBot({ host: VELOCITY_HOST, port: VELOCITY_PORT, username: 'Alice', version: MC_VERSION });
    try {
      await alice.connect();
      await waitUntil('Alice on holding server', async () => Boolean(findPlayerServer(await bridgeState(), 'Alice')), 15000, 500);
      await sleep(2500);

      // Start skywars & bedwars cleanly (fills 2/2 non-exempt maxOnlineServers slots)
      await panelPower('skywars', 'start');
      await panelPower('bedwars', 'start');
      await waitUntil('skywars & bedwars running on live Wings', async () => {
        const [sw, bw] = await Promise.all([liveServerState('skywars'), liveServerState('bedwars')]);
        return sw === 'running' && bw === 'running';
      }, 90000, 1000);
      // Allow HYBRID Velocity ping cache to observe skywars & bedwars as reachable
      await sleep(2500);

      alice.sendCommand('server survival');

      // Alice should be queued (waitingPlayers contains survival) while survival stays offline
      await waitUntil('Alice queued for survival due to maxOnlineServers cap', async () => {
        const s = await bridgeState();
        return (s.waitingPlayers?.survival || []).length > 0;
      }, 20000, 500);
      const stCapped = await liveServerState('survival');
      if (stCapped !== 'offline') {
        throw new Error(`Expected survival offline while cap full, got ${stCapped}`);
      }

      // Free one slot by stopping bedwars -> survival should auto-start and transfer Alice!
      await panelPower('bedwars', 'stop');
      await waitUntil('survival auto-started after slot freed and Alice transferred', async () => {
        return findPlayerServer(await bridgeState(), 'Alice') === 'survival';
      }, 90000, 1000);
      return 'Queued at 2/2 cap -> slot freed -> survival auto-started & transferred Alice';
    } finally {
      alice.disconnect();
    }
  });

  // 9. Maintenance Mode (Built-in + External SPI)
  await runScenario('09-maintenance-builtin-and-spi', 'Maintenance Mode (Built-in /ptero maintenance + External SPI)', async () => {
    await ensureServerStopped('survival');
    await ensureServerStopped('skywars');
    await ensureServerStopped('bedwars');

    await bridgeCommand('ptero maintenance survival on');
    const alice = new McBot({ host: VELOCITY_HOST, port: VELOCITY_PORT, username: 'Alice', version: MC_VERSION, serverHost: 'survival.vpp.test' });
    const bob = new McBot({ host: VELOCITY_HOST, port: VELOCITY_PORT, username: 'Bob', version: MC_VERSION });
    try {
      await alice.connect();
      await sleep(2500);
      if ((await liveServerState('survival')) !== 'offline') {
        throw new Error('Built-in maintenance failed to block survival cold start');
      }
      // Disconnect Alice BEFORE disabling maintenance so maintenance-clear auto-reconnect doesn't start survival
      alice.disconnect();
      await sleep(500);
      await bridgeCommand('ptero maintenance survival off');

      // Now test External MaintenanceProvider SPI via bridge with a fresh lobby-connected bot
      await setExternalMaintenance({ enabled: true, global: false, servers: ['skywars'], detail: ' (e2e-spi)' });
      await bob.connect();
      await waitUntil('Bob on holding server', async () => Boolean(findPlayerServer(await bridgeState(), 'Bob')), 15000, 500);
      await sleep(2500);
      bob.sendCommand('server skywars');
      await sleep(2500);
      if ((await liveServerState('skywars')) !== 'offline') {
        throw new Error('External MaintenanceProvider SPI failed to block skywars cold start');
      }
      bob.disconnect();
      await sleep(500);
      await setExternalMaintenance({ enabled: false, global: false, servers: [] });
      return 'Built-in + External MaintenanceProvider SPI both blocked cold starts cleanly';
    } finally {
      alice.disconnect();
      bob.disconnect();
      await bridgeCommand('ptero maintenance survival off').catch(() => {});
      await setExternalMaintenance({ enabled: false, global: false, servers: [] }).catch(() => {});
    }
  });

  // 10. Persistent Queue Reconnect Resume
  await runScenario('10-persistent-queue-resume', 'Persistent Queue Resumes Auto-Transfer After Reconnect', async () => {
    await ensureServerStopped('survival');
    const alice1 = new McBot({ host: VELOCITY_HOST, port: VELOCITY_PORT, username: 'Alice', version: MC_VERSION, serverHost: 'survival.vpp.test' });
    await alice1.connect();
    await waitUntil('survival marked starting', async () => {
      const s = await bridgeState();
      return (s.startingServers || []).includes('survival') || (await liveServerState('survival')) !== 'offline';
    }, 20000, 500);
    // Disconnect mid-boot
    alice1.disconnect();
    await sleep(1000);

    // Reconnect before survival finishes / shortly after -> PersistentQueueService resumes transfer!
    const alice2 = new McBot({ host: VELOCITY_HOST, port: VELOCITY_PORT, username: 'Alice', version: MC_VERSION });
    try {
      await alice2.connect();
      await waitUntil('Alice resumed from persistent queue onto survival', async () => {
        return findPlayerServer(await bridgeState(), 'Alice') === 'survival';
      }, 90000, 1000);
      return 'Disconnected mid-boot -> reconnected -> resumed onto survival';
    } finally {
      alice2.disconnect();
    }
  });

  // 11. Core /ptero commands + Official Addons (Webhooks, Scheduler, Move-History, Addon manager)
  await runScenario('11-commands-and-addons', 'Core /ptero Commands & Official Addons (Webhooks, Scheduler, Audit)', async () => {
    const listRes = await bridgeCommand('ptero list');
    if (!listRes.messages.some(m => /survival/i.test(m))) {
      throw new Error(`/ptero list missing survival: ${JSON.stringify(listRes.messages)}`);
    }
    const infoRes = await bridgeCommand('ptero info survival');
    if (!infoRes.messages.length) throw new Error('/ptero info returned empty output');

    const schedRes = await bridgeCommand('ptero schedule list');
    if (!schedRes.messages.some(m => /e2e-nightly-check/i.test(m))) {
      throw new Error(`/ptero schedule list missing fixture: ${JSON.stringify(schedRes.messages)}`);
    }

    const auditRes = await bridgeCommand('ptero audit recent');
    if (!auditRes.ok) throw new Error(`/ptero audit failed: ${JSON.stringify(auditRes)}`);

    const addonList = await bridgeCommand('ptero addon list');
    if (!addonList.messages.some(m => /vpp-webhooks/i.test(m))) {
      throw new Error(`/ptero addon list missing vpp-webhooks: ${JSON.stringify(addonList.messages)}`);
    }

    // Verify WebhooksAddon actually delivered live power & connect events to webhook-sink
    const wh = await httpJson(`${WEBHOOK_SINK_URL}/webhooks`);
    const events = wh.json?.events || [];
    const eventTypes = new Set(events.map(e => e.body?.event).filter(Boolean));
    if (!eventTypes.has('power') || !eventTypes.has('connect')) {
      throw new Error(`Expected webhook events power & connect, got: ${JSON.stringify([...eventTypes])} (total=${events.length})`);
    }
    return `Commands OK; Webhooks captured ${events.length} events (${[...eventTypes].join(', ')})`;
  });

  // 12. Admin power commands against a real Wings backend
  await runScenario('12-admin-power-commands', 'Admin Power Commands (start, restart, stop, stopidle)', async () => {
    await ensureServerStopped('skywars');
    await ensureServerStopped('bedwars');
    await ensureServerStopped('survival');

    const started = await bridgeCommand('ptero start skywars', { settleMs: 2500 });
    assertCommand(started, /start/i, '/ptero start skywars');
    assertCommand(started, /skywars/i, '/ptero start skywars');
    await waitUntil('skywars running after /ptero start', async () => (await liveServerState('skywars')) === 'running', 90000, 1000);
    // Group peer bedwars is started with skywars
    await waitUntil('grouped bedwars running after skywars start', async () => (await liveServerState('bedwars')) === 'running', 90000, 1000);

    const restarted = await bridgeCommand('ptero restart skywars', { settleMs: 2500 });
    assertCommand(restarted, /restart/i, '/ptero restart skywars');
    await waitUntil('skywars running after /ptero restart', async () => (await liveServerState('skywars')) === 'running', 90000, 1000);

    const stopped = await bridgeCommand('ptero stop skywars', { settleMs: 2500 });
    assertCommand(stopped, /stop/i, '/ptero stop skywars');
    await waitUntil('skywars offline after /ptero stop', async () => (await liveServerState('skywars')) === 'offline', 90000, 1000);

    await panelPower('skywars', 'start');
    await waitUntil('skywars running before stopidle', async () => (await liveServerState('skywars')) === 'running', 90000, 1000);
    const idle = await bridgeCommand('ptero stopidle', { settleMs: 2500 });
    assertCommand(idle, /Stopping all/i, '/ptero stopidle');
    await waitUntil('skywars offline after stopidle', async () => (await liveServerState('skywars')) === 'offline', 90000, 1000);
    const limbo = await liveServerState('limbo');
    if (limbo !== 'running') {
      throw new Error(`stopidle stopped ignored limbo, state=${limbo}`);
    }
    await ensureServerStopped('bedwars');
    return 'start+group peer, restart, stop, and stopidle (limbo ignored)';
  });

  // 13. Staff command surface, permission denial, and the two-step force confirm prompt
  await runScenario('13-staff-commands-and-denials', 'Staff Commands, Permission Denial, Force-Confirm Prompt', async () => {
    await ensureServerStopped('skywars');
    await ensureServerStopped('survival');

    const denied = await bridgeCommand('ptero start skywars', {
      allPermissions: false,
      permissions: ['ptero.list'],
      settleMs: 800
    });
    assertCommand(denied, /permission/i, 'permission denial');
    if ((await liveServerState('skywars')) !== 'offline') {
      throw new Error('Denied /ptero start still powered skywars');
    }

    const missing = await bridgeCommand('ptero start not-a-real-server', { settleMs: 800 });
    assertCommand(missing, /not found|not managed/i, 'unknown server');

    const usage = await bridgeCommand('ptero start', { settleMs: 800 });
    assertCommand(usage, /Usage/i, 'start usage');

    const setPw = await bridgeCommand('ptero password set skywars e2e-temp-pass', { settleMs: 800 });
    assertCommand(setPw, /password set/i, 'password set');
    const statusOn = await bridgeCommand('ptero password status skywars', { settleMs: 800 });
    assertCommand(statusOn, /password gate/i, 'password status');
    assertCommand(statusOn, /active/i, 'password status active');
    const cleared = await bridgeCommand('ptero password clear skywars', { settleMs: 800 });
    assertCommand(cleared, /Cleared start password/i, 'password clear');
    const statusOff = await bridgeCommand('ptero password status skywars', { settleMs: 800 });
    assertCommand(statusOff, /none/i, 'password status cleared');

    const queue = await bridgeCommand('ptero queue', { settleMs: 800 });
    assertCommand(queue, /No players are waiting|waiting for/i, '/ptero queue');

    const api = await bridgeCommand('ptero api', { settleMs: 1200 });
    assertCommand(api, /VPP API version/i, '/ptero api');
    assertCommand(api, /PanelHttpFacade available: true/i, '/ptero api http facade');

    const threads = await bridgeCommand('ptero apithreads', { settleMs: 1200 });
    assertCommand(threads, /API Threads Info/i, '/ptero apithreads');

    const maint = await bridgeCommand('ptero maintenance', { settleMs: 800 });
    assertCommand(maint, /Maintenance: global=/i, 'maintenance status');

    const reload = await bridgeCommand('ptero reload', { settleMs: 3000 });
    assertCommand(reload, /reloaded/i, '/ptero reload');
    await waitUntil('bridge healthy after reload', async () => {
      const h = await bridgeHealth();
      return h.ok && h.json?.vppLoaded === true && h.json?.apiClientReady === true;
    }, 30000, 500);

    const forceStop = await bridgeCommand('ptero forcestopall', { settleMs: 800 });
    assertCommand(forceStop, /confirm/i, 'forcestopall prompt');
    const forceStart = await bridgeCommand('ptero forcestartall', { settleMs: 800 });
    assertCommand(forceStart, /confirm/i, 'forcestartall prompt');
    assertCommand(forceStart, /Warning/i, 'forcestartall warning');
    if ((await liveServerState('limbo')) !== 'running') {
      throw new Error('Unconfirmed force command stopped limbo');
    }
    if ((await liveServerState('survival')) !== 'offline') {
      throw new Error('Unconfirmed forcestartall started survival');
    }
    return 'denial, password, queue, api, reload, and unconfirmed force prompts';
  });

  // 14. Global maintenance blocks a cold start, then clears
  await runScenario('14-maintenance-global', 'Global Maintenance Blocks Cold Start', async () => {
    await ensureServerStopped('survival');
    await bridgeCommand('ptero maintenance global on');
    const alice = makeBot('Alice', 'survival.vpp.test');
    try {
      const stateOn = await bridgeState();
      if (stateOn.maintenanceGlobal !== true) {
        throw new Error(`Expected maintenanceGlobal=true, got ${stateOn.maintenanceGlobal}`);
      }
      await alice.connect();
      await sleep(2500);
      if ((await liveServerState('survival')) !== 'offline') {
        throw new Error('Global maintenance did not block survival');
      }
      if (findPlayerServer(await bridgeState(), 'Alice') === 'survival') {
        throw new Error('Alice reached survival during global maintenance');
      }
      alice.disconnect();
      await sleep(400);
      await bridgeCommand('ptero maintenance global off');
      const stateOff = await bridgeState();
      if (stateOff.maintenanceGlobal !== false) {
        throw new Error('Global maintenance stayed on after off');
      }
      return 'global on blocked survival; global off cleared the flag';
    } finally {
      alice.disconnect();
      await bridgeCommand('ptero maintenance global off').catch(() => {});
    }
  });

  // 15. ptero.maxcap.bypass starts a third game server while the cap is full
  await runScenario('15-maxcap-bypass', 'ptero.maxcap.bypass Starts a Server Above the Cap', async () => {
    await ensureServerStopped('survival');
    await ensureServerStopped('skywars');
    await ensureServerStopped('bedwars');
    await panelPower('skywars', 'start');
    await panelPower('bedwars', 'start');
    await waitUntil('cap filled by skywars and bedwars', async () => {
      const [sw, bw] = await Promise.all([liveServerState('skywars'), liveServerState('bedwars')]);
      return sw === 'running' && bw === 'running';
    }, 90000, 1000);
    await sleep(2000);

    await setBotPermissions('Admin', ['ptero.bypass', 'ptero.maxcap.bypass']);
    const admin = makeBot('Admin', 'survival.vpp.test');
    try {
      await admin.connect();
      await waitUntil('Admin bypassed max-online cap onto survival', async () => {
        return findPlayerServer(await bridgeState(), 'Admin') === 'survival';
      }, 90000, 1000);
      if ((await liveServerState('survival')) !== 'running') {
        throw new Error('survival did not reach running after maxcap bypass');
      }
      return '2/2 cap full; Admin with ptero.maxcap.bypass still started survival';
    } finally {
      admin.disconnect();
      await disconnectBots([admin]);
      await ensureServerStopped('survival');
      await ensureServerStopped('skywars');
      await ensureServerStopped('bedwars');
    }
  });

  // 16. Back-to-back cold starts honor playerStartCooldown
  await runScenario('16-player-start-cooldown', 'playerStartCooldown Blocks a Second Cold Start', async () => {
    await ensureServerStopped('survival');
    await ensureServerStopped('skywars');
    await ensureServerStopped('bedwars');
    const alice = makeBot('Alice');
    try {
      await alice.connect();
      await waitUntil('Alice on a holding server', async () => Boolean(findPlayerServer(await bridgeState(), 'Alice')), 20000, 500);
      await sleep(2500);
      alice.sendCommand('server survival');
      await sleep(150);
      alice.sendCommand('server skywars');
      await waitUntil('survival cold start accepted', async () => {
        const st = await liveServerState('survival');
        return st === 'starting' || st === 'running';
      }, 20000, 400);
      await sleep(2500);
      if ((await liveServerState('skywars')) !== 'offline') {
        throw new Error('Second cold start was not blocked by playerStartCooldown');
      }
      return 'survival started; skywars stayed offline inside the cooldown window';
    } finally {
      alice.disconnect();
      await disconnectBots([alice]);
      await ensureServerStopped('survival');
      await ensureServerStopped('skywars');
      await ensureServerStopped('bedwars');
    }
  });

  // 17. Four bots across lobby scale-up plus two game backends
  await runScenario('17-multi-backend-players', 'Four Players Across Lobbies and Two Game Backends', async () => {
    await ensureServerStopped('survival');
    await ensureServerStopped('skywars');
    await ensureServerStopped('bedwars');
    const bots = ['Alice', 'Bob', 'Carol', 'Dave'].map((name) => makeBot(name));
    try {
      await Promise.all(bots.map((b) => b.connect()));
      await waitUntil('Alice, Bob, Carol, and Dave online', async () => {
        const s = await bridgeState();
        return ['Alice', 'Bob', 'Carol', 'Dave'].every((name) => Boolean(findPlayerServer(s, name)));
      }, 30000, 500);

      await waitUntil('lobby-2 scaled up under four players', async () => {
        return (await liveServerState('lobby-2')) === 'running';
      }, 90000, 1000);

      bots[0].sendCommand('server survival');
      await sleep(400);
      bots[1].sendCommand('server skywars');
      await waitUntil('Alice on survival and Bob on skywars', async () => {
        const s = await bridgeState();
        return findPlayerServer(s, 'Alice') === 'survival' && findPlayerServer(s, 'Bob') === 'skywars';
      }, 90000, 1000);

      const placed = await bridgeState();
      const backends = new Set(['Alice', 'Bob', 'Carol', 'Dave'].map((name) => findPlayerServer(placed, name)));
      if (backends.size < 3) {
        throw new Error(`Expected players on at least 3 backends, got ${JSON.stringify([...backends])}`);
      }
      const history = await bridgeCommand('ptero history Alice', { settleMs: 1500 });
      assertCommand(history, /History for Alice/i, '/ptero history Alice');
      return `backends=${[...backends].join(',')}`;
    } finally {
      await disconnectBots(bots);
      await ensureServerStopped('survival');
      await ensureServerStopped('skywars');
      await ensureServerStopped('bedwars');
    }
  });

  // 17b. Lobby scale-down: with everyone gone, the extra lobby stops after scaleDownIdleSeconds
  await runScenario('17b-lobby-scale-down', 'Idle Extra Lobby Scales Back Down', async () => {
    // Scenario 17 asserted lobby-2 was scaled up; nothing but the balancer's scale-down stops it.
    await waitUntil('lobby-2 scaled down after idle (scaleDownIdleSeconds=20)', async () => {
      return (await liveServerState('lobby-2')) === 'offline';
    }, 120000, 2000);
    if ((await liveServerState('lobby-1')) !== 'running') {
      throw new Error('Scale-down also stopped lobby-1 (minOnline=1)');
    }
    return 'lobby-2 stopped after idle; lobby-1 kept for minOnline=1';
  });

  // 18. A player on skywars keeps empty bedwars from idle-stopping
  await runScenario('18-group-idle-guard', 'Group Peer Stays Up While Another Member Has Players', async () => {
    await ensureServerStopped('survival');
    await ensureServerStopped('skywars');
    await ensureServerStopped('bedwars');
    await panelPower('skywars', 'start');
    await waitUntil('skywars running for group guard', async () => (await liveServerState('skywars')) === 'running', 90000, 1000);

    const alice = makeBot('Alice');
    try {
      await alice.connect();
      await waitUntil('Alice holding before skywars switch', async () => Boolean(findPlayerServer(await bridgeState(), 'Alice')), 20000, 500);
      await sleep(2200);
      alice.sendCommand('server skywars');
      await waitUntil('Alice on skywars', async () => findPlayerServer(await bridgeState(), 'Alice') === 'skywars', 45000, 500);

      await panelPower('bedwars', 'start');
      await waitUntil('bedwars running beside occupied skywars', async () => (await liveServerState('bedwars')) === 'running', 90000, 1000);
      await sleep(28000);

      if ((await liveServerState('bedwars')) !== 'running') {
        throw new Error('bedwars idle-stopped while skywars still had Alice');
      }
      if ((await liveServerState('skywars')) !== 'running') {
        throw new Error('skywars stopped while Alice was online');
      }
      const deadlines = (await bridgeState()).shutdownDeadlines || {};
      if (deadlines.bedwars) {
        throw new Error(`bedwars had a shutdown deadline despite an occupied group peer: ${deadlines.bedwars}`);
      }
      return 'bedwars stayed running for 28s while Alice occupied skywars';
    } finally {
      alice.disconnect();
      await disconnectBots([alice]);
      await ensureServerStopped('skywars');
      await ensureServerStopped('bedwars');
    }
  });

  // 19. RoutingProvider SPI rewrites a forced-host connect onto limbo
  await runScenario('19-routing-provider', 'RoutingProvider Overrides Forced-Host Target', async () => {
    await ensureServerStopped('survival');
    await setRouting({ enabled: true, overrideTarget: 'limbo', cancel: false });
    const eve = makeBot('Eve', 'survival.vpp.test');
    try {
      await eve.connect();
      await waitUntil('Eve held on limbo by routing override', async () => {
        return findPlayerServer(await bridgeState(), 'Eve') === 'limbo';
      }, 20000, 500);
      await sleep(2000);
      if ((await liveServerState('survival')) !== 'offline') {
        throw new Error('Routing override still cold-started survival');
      }
      const api = await bridgeCommand('ptero api', { settleMs: 1000 });
      assertCommand(api, /RoutingProvider registered: true/i, 'routing provider visible');
      return 'Eve forced-host survival was rewritten to limbo';
    } finally {
      eve.disconnect();
      await setRouting({ enabled: false, overrideTarget: null, cancel: false }).catch(() => {});
    }
  });

  // 20. queueWhenMaxOnline=false denies at the cap; maxOnlineReservations shrinks the cap
  await runScenario('20-cap-queue-off-and-reservations', 'queueWhenMaxOnline=false and maxOnlineReservations', async () => {
    await stopGameServers();
    const alice = makeBot('Alice');
    // Bob and Carol occupy the two capped servers so idle shutdown cannot free a slot mid-test
    const bob = makeBot('Bob');
    const carol = makeBot('Carol');
    try {
      await Promise.all([alice.connect(), bob.connect(), carol.connect()]);
      await waitUntil('Alice, Bob, Carol holding', async () => {
        const s = await bridgeState();
        return ['Alice', 'Bob', 'Carol'].every((n) => Boolean(findPlayerServer(s, n)));
      }, 20000, 500);
      await panelPower('skywars', 'start');
      await panelPower('bedwars', 'start');
      await waitUntil('cap filled (skywars + bedwars)', async () => {
        const [sw, bw] = await Promise.all([liveServerState('skywars'), liveServerState('bedwars')]);
        return sw === 'running' && bw === 'running';
      }, 90000, 1000);
      await sleep(2500);
      bob.sendCommand('server skywars');
      carol.sendCommand('server bedwars');
      await waitUntil('Bob on skywars and Carol on bedwars', async () => {
        const s = await bridgeState();
        return findPlayerServer(s, 'Bob') === 'skywars' && findPlayerServer(s, 'Carol') === 'bedwars';
      }, 30000, 500);

      const denied = await withVppConfig({ queueWhenMaxOnline: false }, async () => {
        alice.sendCommand('server survival');
        await sleep(4000);
        const s = await bridgeState();
        if ((s.waitingPlayers?.survival || []).length > 0) {
          throw new Error('Alice was queued although queueWhenMaxOnline=false');
        }
        if ((await liveServerState('survival')) !== 'offline') {
          throw new Error('survival started above the cap with queueWhenMaxOnline=false');
        }
        if (findPlayerServer(s, 'Alice') === 'survival') throw new Error('Alice reached survival');
        return 'denied without queueing';
      });

      carol.disconnect();
      await panelPower('bedwars', 'stop');
      await waitUntil('bedwars offline (1/2 online)', async () => (await liveServerState('bedwars')) === 'offline', 90000, 1000);
      await sleep(2500);

      const original = await bridgeFileRead('config.yml');
      await bridgeFileWrite('config.yml', yamlSet(original, 'maxOnlineReservations', 1));
      await reloadVpp();
      try {
        alice.sendCommand('server survival');
        await waitUntil('Alice queued at 1/2 because one slot is reserved', async () => {
          const s = await bridgeState();
          return (s.waitingPlayers?.survival || []).length > 0;
        }, 20000, 500);
        await sleep(5000);
        if ((await liveServerState('survival')) !== 'offline') {
          throw new Error('survival started despite maxOnlineReservations=1 at 1/2');
        }
      } finally {
        await bridgeFileWrite('config.yml', original);
        await reloadVpp();
      }
      // Reservation lifted -> the queued start goes through
      await waitUntil('Alice transferred to survival once the reservation was removed', async () => {
        return findPlayerServer(await bridgeState(), 'Alice') === 'survival';
      }, 90000, 1000);
      return `${denied}; reservation held survival at 1/2, then released it`;
    } finally {
      await disconnectBots([alice, bob, carol]);
      await stopGameServers();
    }
  });

  // 21. forcedHostOfflineBehavior DISCONNECT and LIMBO_ONLY (sendToLimboOnStart off)
  await runScenario('21-forced-host-offline-behavior', 'Forced-Host DISCONNECT and LIMBO_ONLY', async () => {
    await stopGameServers();
    const kicked = await withVppConfig({
      'lobbyBalancer.sendToLimboOnStart': false,
      'lobbyBalancer.forcedHostOfflineBehavior': 'DISCONNECT'
    }, async () => {
      const alice = makeBot('Alice', 'survival.vpp.test');
      try {
        try { await alice.connect(); } catch { /* kicked during login is expected */ }
        await waitUntil('Alice disconnected with a starting message', async () => !alice.connected, 20000, 250);
        if (!/start/i.test(alice.kickReason || '')) {
          throw new Error(`Unexpected kick reason: ${alice.kickReason}`);
        }
        await waitUntil('survival cold-started for the kicked player', async () => {
          const st = await liveServerState('survival');
          return st === 'starting' || st === 'running';
        }, 30000, 500);
        return alice.kickReason;
      } finally {
        alice.disconnect();
      }
    });
    await ensureServerStopped('survival');

    const held = await withVppConfig({
      'lobbyBalancer.sendToLimboOnStart': false,
      'lobbyBalancer.forcedHostOfflineBehavior': 'LIMBO_ONLY'
    }, async () => {
      const bob = makeBot('Bob', 'survival.vpp.test');
      try {
        await bob.connect();
        await waitUntil('Bob held on limbo (LIMBO_ONLY)', async () => {
          return findPlayerServer(await bridgeState(), 'Bob') === 'limbo';
        }, 20000, 500);
        await waitUntil('Bob transferred to survival', async () => {
          return findPlayerServer(await bridgeState(), 'Bob') === 'survival';
        }, 90000, 1000);
        return 'limbo -> survival';
      } finally {
        await disconnectBots([bob]);
      }
    });
    await ensureServerStopped('survival');
    return `DISCONNECT kicked ("${kicked}") and still started survival; LIMBO_ONLY ${held}`;
  });

  // 22. serverStatusCheckMethod PANEL_API and VELOCITY_PING both complete a cold start + transfer
  await runScenario('22-status-check-methods', 'serverStatusCheckMethod PANEL_API and VELOCITY_PING', async () => {
    const out = [];
    for (const method of ['PANEL_API', 'VELOCITY_PING']) {
      await ensureServerStopped('survival');
      const took = await withVppConfig({ serverStatusCheckMethod: method }, async () => {
        const started = Date.now();
        const carol = makeBot('Alice', 'survival.vpp.test');
        try {
          await carol.connect();
          await waitUntil(`Alice transferred to survival under ${method}`, async () => {
            return findPlayerServer(await bridgeState(), 'Alice') === 'survival';
          }, 120000, 1000);
          return Date.now() - started;
        } finally {
          await disconnectBots([carol]);
        }
      });
      out.push(`${method}=${Math.round(took / 1000)}s`);
    }
    await ensureServerStopped('survival');
    return out.join(', ');
  });

  // 23. Deeper command checks: tab-complete, password generate+unlock, queue/history content, de_DE
  await runScenario('23-commands-deep', 'Tab-Complete, Password Generate, Queue/History Content, de_DE', async () => {
    await stopGameServers();

    const root = await bridgeSuggest('ptero st');
    for (const want of ['start', 'stop', 'stopidle']) {
      if (!root.includes(want)) throw new Error(`Tab-complete 'ptero st' missing ${want}: ${JSON.stringify(root)}`);
    }
    const servers = await bridgeSuggest('ptero start sur');
    if (JSON.stringify(servers) !== JSON.stringify(['survival'])) {
      throw new Error(`Tab-complete 'ptero start sur' = ${JSON.stringify(servers)}`);
    }
    const confirm = await bridgeSuggest('ptero forcestopall c');
    if (!confirm.includes('confirm')) throw new Error(`forcestopall confirm not suggested: ${JSON.stringify(confirm)}`);
    const limited = await bridgeSuggest('ptero ', { permissions: ['ptero.list'] });
    if (limited.includes('start') || limited.includes('forcestopall') || !limited.includes('list')) {
      throw new Error(`Permission-filtered tab-complete wrong: ${JSON.stringify(limited)}`);
    }

    // Password generate -> a player unlocks with the generated value -> cold start allowed
    const gen = await bridgeCommand('ptero password generate survival', { settleMs: 800 });
    assertCommand(gen, /Generated start password/i, 'password generate');
    const pwd = (commandText(gen).match(/survival:\s*(\S+)/) || [])[1];
    if (!pwd) throw new Error(`Could not parse generated password from ${commandText(gen)}`);

    const alice = makeBot('Alice');
    try {
      await alice.connect();
      await waitUntil('Alice holding', async () => Boolean(findPlayerServer(await bridgeState(), 'Alice')), 20000, 500);
      await sleep(2500);
      alice.sendCommand('server survival');
      await sleep(2500);
      if ((await liveServerState('survival')) !== 'offline') {
        throw new Error('Generated password gate did not block survival');
      }
      await bridgeCommand(`ptero unlock survival ${pwd}`, { asPlayer: 'Alice' });
      await sleep(2500);
      alice.sendCommand('server survival');

      // While survival boots, /ptero queue must list it with Alice waiting
      const queued = await waitUntil('/ptero queue lists survival', async () => {
        const q = await bridgeCommand('ptero queue', { settleMs: 600 });
        return /survival/i.test(commandText(q)) && /1\s*waiting/i.test(commandText(q)) ? commandText(q) : false;
      }, 30000, 1000);
      await waitUntil('Alice transferred to survival after unlock', async () => {
        return findPlayerServer(await bridgeState(), 'Alice') === 'survival';
      }, 90000, 1000);

      const history = await bridgeCommand('ptero history Alice', { settleMs: 1500 });
      assertCommand(history, /History for Alice/i, 'history header');
      assertCommand(history, /survival/i, 'history lists the survival move');

      const empty = await bridgeCommand('ptero queue', { settleMs: 600 });
      assertCommand(empty, /No players are waiting/i, 'queue empty after transfer');

      // Language: de_DE via languageOverride + reload, then back
      const german = await withVppConfig({ languageOverride: 'de_DE' }, async () => {
        const st = await bridgeState();
        if (st.messagesLanguage !== 'de_DE') throw new Error(`messagesLanguage=${st.messagesLanguage}`);
        const q = await bridgeCommand('ptero queue', { settleMs: 600 });
        assertCommand(q, /Keine Spieler warten/i, 'German queue-empty message');
        return commandText(q);
      });
      const back = await bridgeCommand('ptero queue', { settleMs: 600 });
      assertCommand(back, /No players are waiting/i, 'English restored after de_DE');
      await bridgeCommand('ptero password clear survival', { settleMs: 600 });
      return `tab-complete ok; generated pw unlocked survival; queue="${queued.split('\n').pop()}"; de_DE="${german.trim()}"`;
    } finally {
      await disconnectBots([alice]);
      await bridgeCommand('ptero password clear survival', { settleMs: 400 }).catch(() => {});
      await stopGameServers();
    }
  });

  // 24. Webhooks: Discord embed format (auto-detected by URL) and /ptero webhook test
  await runScenario('24-webhooks-discord-and-test', 'Webhooks Discord Embed + /ptero webhook test', async () => {
    const all = await webhookEvents();
    const discord = all.filter((e) => (e.path || '').includes('discord.com/api/webhooks'));
    const embed = discord.find((e) => Array.isArray(e.body?.embeds) && e.body.embeds.length > 0);
    if (!embed) {
      throw new Error(`No Discord-format webhook captured (total=${all.length}, discord=${discord.length})`);
    }
    if (embed.body.username !== 'VelocityPteroPower' || !Array.isArray(embed.body.embeds[0].fields)) {
      throw new Error(`Malformed Discord body: ${JSON.stringify(embed.body)}`);
    }
    if (all.some((e) => !(e.path || '').includes('discord') && e.body?.embeds)) {
      throw new Error('Generic endpoint received a Discord-format body');
    }

    await httpJson(`${WEBHOOK_SINK_URL}/webhooks`, { method: 'DELETE' });
    const res = await bridgeCommand('ptero webhook test', { settleMs: 1000 });
    assertCommand(res, /Test webhook dispatched.*2/i, '/ptero webhook test');
    const got = await waitUntil('test webhook delivered to both endpoints', async () => {
      const ev = await webhookEvents();
      const generic = ev.find((e) => e.body?.event === 'test' && e.body?.payload?.source === 'manual-test');
      const dc = ev.find((e) => e.body?.embeds?.[0]?.title === 'Webhook test');
      return generic && dc ? ev : false;
    }, 15000, 500);
    return `discord embeds=${discord.length}; test delivered to ${got.length} endpoint(s)`;
  });

  // 25. Addon manager: browse, disable/enable, uninstall + install from the (local) catalog, audit persistence
  await runScenario('25-addon-manager', 'Addon browse, disable/enable, uninstall/install, audit reload', async () => {
    const browse = await bridgeCommand('ptero addon browse', { settleMs: 2500 });
    assertCommand(browse, /Addon Catalog/i, 'addon browse header');
    for (const id of ['vpp-webhooks', 'vpp-scheduler', 'vpp-move-history']) {
      if (!browse.messages.some((m) => m.includes(id) && /INSTALLED/.test(m))) {
        throw new Error(`browse missing [INSTALLED] ${id}: ${JSON.stringify(browse.messages)}`);
      }
    }

    // Disable / enable
    assertCommand(await bridgeCommand('ptero addon disable vpp-webhooks'), /Disabled addon/i, 'addon disable');
    if (await addonActive('vpp-webhooks')) throw new Error('vpp-webhooks still active after disable');
    const gone = await bridgeCommand('ptero webhook test', { settleMs: 800 });
    if (/Test webhook dispatched/i.test(commandText(gone))) throw new Error('Disabled addon still handled its command');
    assertCommand(await bridgeCommand('ptero addon enable vpp-webhooks'), /Enabled addon/i, 'addon enable');
    if (!(await addonActive('vpp-webhooks'))) throw new Error('vpp-webhooks not active after enable');

    // Move-history persists to disk across an addon reload
    const before = await bridgeCommand('ptero audit Alice', { settleMs: 1200 });
    const countBefore = Number((commandText(before).match(/\((\d+) entries\)/) || [])[1] || 0);
    if (countBefore < 1) throw new Error(`Expected audit entries for Alice, got: ${commandText(before)}`);
    assertCommand(await bridgeCommand('ptero addon reload vpp-move-history', { settleMs: 1500 }), /Reloaded addon/i, 'addon reload');
    const after = await bridgeCommand('ptero audit Alice', { settleMs: 1200 });
    const countAfter = Number((commandText(after).match(/\((\d+) entries\)/) || [])[1] || 0);
    if (countAfter < countBefore) throw new Error(`Audit lost entries across reload: ${countBefore} -> ${countAfter}`);
    const stored = await bridgeFileRead('addons/data/vpp-move-history/history.json');
    if (!stored.includes('Alice')) throw new Error('history.json on disk does not contain Alice');

    // Uninstall + reinstall from the catalog (webhook-sink serves the freshly built JAR)
    assertCommand(await bridgeCommand('ptero addon uninstall vpp-scheduler'), /Uninstalled addon/i, 'addon uninstall');
    const noSched = await bridgeCommand('ptero schedule list', { settleMs: 800 });
    if (/VPP Schedules/i.test(commandText(noSched))) throw new Error('schedule command still registered after uninstall');
    const install = await bridgeCommand('ptero addon install vpp-scheduler', { settleMs: 5000 });
    assertCommand(install, /Installing|Installed/i, 'addon install');
    await waitUntil('vpp-scheduler active after catalog install', () => addonActive('vpp-scheduler'), 30000, 500);
    const sched = await bridgeCommand('ptero schedule list', { settleMs: 800 });
    assertCommand(sched, /e2e-nightly-check/i, 'reinstalled scheduler kept its config');
    const info = await bridgeCommand('ptero addon info vpp-scheduler', { settleMs: 1500 });
    assertCommand(info, /ACTIVE/i, 'addon info status');
    return `browse ok; disable/enable ok; audit ${countBefore}->${countAfter} after reload; reinstall ok`;
  });

  // 26. Scheduler fires a real cron against Wings
  await runScenario('26-scheduler-live-fire', 'Scheduler Cron Fires a Real Start', async () => {
    await stopGameServers();
    const rel = 'addons/configs/vpp-scheduler.yml';
    const original = await bridgeFileRead(rel);
    const live = [
      'enabled: true',
      'timezone: "UTC"',
      'checkIntervalSeconds: 5',
      'schedules:',
      '  - name: "e2e-every-minute"',
      '    enabled: true',
      '    cron: "* * * * *"',
      '    action: "start"',
      '    servers:',
      '      - skywars',
      ''
    ].join('\n');
    try {
      await bridgeFileWrite(rel, live);
      assertCommand(await bridgeCommand('ptero addon reload vpp-scheduler', { settleMs: 1500 }), /Reloaded addon/i, 'scheduler reload');
      const list = await bridgeCommand('ptero schedule list', { settleMs: 800 });
      assertCommand(list, /e2e-every-minute.*on/i, 'live schedule listed as on');
      const started = Date.now();
      await waitUntil('cron started skywars on live Wings', async () => {
        const st = await liveServerState('skywars');
        return st === 'starting' || st === 'running';
      }, 80000, 1000);
      return `cron fired after ${Math.round((Date.now() - started) / 1000)}s`;
    } finally {
      await bridgeFileWrite(rel, original);
      await bridgeCommand('ptero addon reload vpp-scheduler', { settleMs: 1000 }).catch(() => {});
      await stopGameServers();
    }
  });

  // 27. resourcePrefetchEnabled warms the resource cache for every managed server
  await runScenario('27-resource-prefetch', 'resourcePrefetch Warms the Resource Cache', async () => {
    const ids = Object.values(panelMeta.servers || {});
    let last = null;
    const cached = await waitUntil('resource cache holds every managed server', async () => {
      last = await bridgeState();
      const keys = last.resourceCacheServerIds || [];
      return ids.length > 0 && ids.every((id) => keys.includes(id)) ? keys : false;
    }, 60000, 2000).catch((err) => {
      throw new Error(`${err.message}; cached=${JSON.stringify(last?.resourceCacheServerIds)} `
        + `rate=${last?.rateLimitRemaining}/${last?.rateLimitMax} waiting=${JSON.stringify(last?.waitingPlayers)}`);
    });
    return `${cached.length} server(s) prefetched`;
  });

  // 28. forcestartall / forcestopall confirmed for real; always-online brings limbo back
  await runScenario('28-force-all-confirmed', 'forcestartall confirm + forcestopall confirm + Always-Online Recovery', async () => {
    const start = await bridgeCommand('ptero forcestartall confirm', { settleMs: 2500 });
    assertCommand(start, /start/i, 'forcestartall confirm');
    // Idle shutdown may stop empty game servers again quickly, so record each one seen running
    const all = ['limbo', 'lobby-1', 'lobby-2', 'survival', 'skywars', 'bedwars'];
    const seen = new Set();
    await waitUntil('all six servers seen running after forcestartall confirm', async () => {
      const st = await Promise.all(all.map(liveServerState));
      st.forEach((x, i) => { if (x === 'running') seen.add(all[i]); });
      return seen.size === all.length;
    }, 120000, 1000);

    const stop = await bridgeCommand('ptero forcestopall confirm', { settleMs: 2500 });
    assertCommand(stop, /stop/i, 'forcestopall confirm');
    await waitUntil('game servers offline after forcestopall confirm', async () => {
      const st = await Promise.all(['survival', 'skywars', 'bedwars'].map(liveServerState));
      return st.every((x) => x === 'offline');
    }, 90000, 1000);
    await waitUntil('always-online keeper restarts limbo', async () => (await liveServerState('limbo')) === 'running', 120000, 1500);
    await waitUntil('lobby minOnline restores lobby-1', async () => (await liveServerState('lobby-1')) === 'running', 120000, 1500);
    return 'all started, all stopped, limbo + lobby-1 recovered';
  });

  // 29. Graceful Proxy Shutdown -> shutdownOnProxyExit stops configured servers on Live Wings
  await runScenario('29-proxy-shutdown-stop', 'Proxy Shutdown (shutdownOnProxyExit) Stops Servers on Live Wings', async () => {
    await panelPower('survival', 'start');
    await waitUntil('survival running before proxy shutdown', async () => (await liveServerState('survival')) === 'running', 90000, 1000);

    await httpJson(`${BRIDGE_URL}/shutdown`, { method: 'POST', body: '{}' });

    await waitUntil('survival stopped on live Wings after Velocity shutdown', async () => {
      return (await panelServerState('survival')) === 'offline';
    }, 90000, 1500);
    return 'Velocity shutdown triggered STOP -> live Wings stopped survival';
  });

  const passed = results.filter(r => r.status === 'PASS').length;
  const failed = results.filter(r => r.status === 'FAIL').length;

  const summary = {
    panel: PANEL_TYPE,
    startedAt: suiteStartedAt,
    finishedAt: new Date().toISOString(),
    mcProtocolVersion: MC_VERSION,
    total: results.length,
    passed,
    failed,
    results
  };

  fs.mkdirSync(RESULTS_DIR, { recursive: true });
  fs.writeFileSync(path.join(RESULTS_DIR, 'results.json'), JSON.stringify(summary, null, 2));
  console.log(`\n[vpp-e2e] Completed: ${passed}/${results.length} passed, ${failed} failed.`);
  process.exit(failed > 0 ? 1 : 0);
}

main().catch(err => {
  console.error('[vpp-e2e] FATAL:', err);
  fs.mkdirSync(RESULTS_DIR, { recursive: true });
  fs.writeFileSync(path.join(RESULTS_DIR, 'results.json'), JSON.stringify({
    panel: PANEL_TYPE,
    startedAt: new Date().toISOString(),
    finishedAt: new Date().toISOString(),
    total: results.length,
    passed: results.filter(r => r.status === 'PASS').length,
    failed: results.filter(r => r.status === 'FAIL').length + 1,
    fatalError: err.stack || err.message,
    results
  }, null, 2));
  process.exit(1);
});
