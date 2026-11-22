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
    return `panel=${h.panelType}, addons=${activeAddonIds.join(',')}, wsCache=${JSON.stringify(cache)}`;
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

  // 12. Graceful Proxy Shutdown -> shutdownOnProxyExit stops configured servers on Live Wings
  await runScenario('12-proxy-shutdown-stop', 'Proxy Shutdown (shutdownOnProxyExit) Stops Servers on Live Wings', async () => {
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
