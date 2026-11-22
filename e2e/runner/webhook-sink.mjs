import http from 'node:http';
import fs from 'node:fs';
import path from 'node:path';

const PORT = Number(process.env.WEBHOOK_SINK_PORT || 19091);
const CATALOG_FILE = process.env.CATALOG_FILE || '/catalog/addons.json';
const ADDONS_DIR = process.env.ADDONS_DIR || '/artifacts/addons';

const received = [];

function sendJson(res, status, data) {
  const body = JSON.stringify(data);
  res.writeHead(status, {
    'Content-Type': 'application/json; charset=utf-8',
    'Content-Length': Buffer.byteLength(body)
  });
  res.end(body);
}

const server = http.createServer((req, res) => {
  const url = new URL(req.url || '/', `http://127.0.0.1:${PORT}`);

  if (req.method === 'GET' && url.pathname === '/health') {
    return sendJson(res, 200, { status: 'ok', capturedCount: received.length });
  }

  if (req.method === 'POST' && url.pathname === '/webhook') {
    let raw = '';
    req.on('data', chunk => { raw += chunk; });
    req.on('end', () => {
      let parsed = null;
      try { parsed = JSON.parse(raw); } catch { /* keep raw */ }
      received.push({
        receivedAt: new Date().toISOString(),
        headers: req.headers,
        body: parsed ?? raw
      });
      console.log(`[webhook-sink] Received #${received.length}: event=${parsed?.event ?? 'raw'} payload=${JSON.stringify(parsed?.payload ?? {})}`);
      res.writeHead(204);
      res.end();
    });
    return;
  }

  if (req.method === 'GET' && url.pathname === '/webhooks') {
    return sendJson(res, 200, { events: received });
  }

  if (req.method === 'DELETE' && url.pathname === '/webhooks') {
    received.length = 0;
    return sendJson(res, 200, { cleared: true });
  }

  if (req.method === 'GET' && url.pathname === '/catalog/addons.json') {
    try {
      const content = fs.readFileSync(CATALOG_FILE, 'utf8');
      res.writeHead(200, { 'Content-Type': 'application/json; charset=utf-8' });
      res.end(content);
    } catch {
      sendJson(res, 200, { schemaVersion: 1, addons: [] });
    }
    return;
  }

  if (req.method === 'GET' && url.pathname.startsWith('/catalog/jars/')) {
    const file = path.basename(url.pathname);
    const fullPath = path.join(ADDONS_DIR, file);
    if (fs.existsSync(fullPath)) {
      const bytes = fs.readFileSync(fullPath);
      res.writeHead(200, {
        'Content-Type': 'application/java-archive',
        'Content-Length': bytes.length
      });
      res.end(bytes);
    } else {
      sendJson(res, 404, { error: 'JAR not found' });
    }
    return;
  }

  sendJson(res, 404, { error: 'Not found' });
});

server.listen(PORT, '0.0.0.0', () => {
  console.log(`[webhook-sink] Listening on 0.0.0.0:${PORT}`);
});
