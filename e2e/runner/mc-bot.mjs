import mc from 'minecraft-protocol';

/**
 * Lightweight automated Minecraft client for testing real Velocity proxy flows.
 * Supports custom forced-host handshakes, chat/actionbar capture, command sending,
 * and automatic tracking of disconnects & server switches.
 */
export class McBot {
  constructor({ host, port, username, version = '1.21.4', serverHost = null }) {
    this.host = host;
    this.port = port;
    this.username = username;
    this.version = version;
    this.serverHost = serverHost || host;
    this.client = null;
    this.connected = false;
    this.ended = false;
    this.kickReason = null;
    this.chatMessages = [];
    this.actionBarMessages = [];
    this.loginCount = 0;
  }

  async connect(timeoutMs = 20000) {
    return new Promise((resolve, reject) => {
      let settled = false;
      const timer = setTimeout(() => {
        if (!settled) {
          settled = true;
          this.disconnect();
          reject(new Error(`Bot ${this.username} timed out connecting to ${this.host}:${this.port} (vhost=${this.serverHost})`));
        }
      }, timeoutMs);

      const opts = {
        host: this.host,
        port: this.port,
        username: this.username,
        auth: 'offline',
        version: this.version,
        hideErrors: true
      };

      // Force virtual host in Minecraft handshake packet for Velocity forced-host tests
      if (this.serverHost && this.serverHost !== this.host) {
        opts.fakeHost = this.serverHost;
      }

      const client = mc.createClient(opts);
      this.client = client;

      client.on('login', () => {
        this.loginCount += 1;
        this.connected = true;
        if (!settled) {
          settled = true;
          clearTimeout(timer);
          resolve(this);
        }
      });

      const recordChat = (raw, isActionBar = false) => {
        const text = extractPlainText(raw);
        if (!text) return;
        if (isActionBar) {
          this.actionBarMessages.push(text);
        } else {
          this.chatMessages.push(text);
        }
      };

      client.on('system_chat', (pkt) => recordChat(pkt.formattedMessage ?? pkt.content, Boolean(pkt.isActionBar)));
      client.on('player_chat', (pkt) => recordChat(pkt.unsignedChatContent ?? pkt.plainMessage, false));
      client.on('chat', (pkt) => recordChat(pkt.message, pkt.position === 2));
      client.on('action_bar', (pkt) => recordChat(pkt.text, true));

      client.on('kick_disconnect', (pkt) => {
        this.kickReason = extractPlainText(pkt.reason);
        this.connected = false;
        this.ended = true;
        if (!settled) {
          settled = true;
          clearTimeout(timer);
          resolve(this);
        }
      });

      client.on('disconnect', (pkt) => {
        this.kickReason = extractPlainText(pkt.reason);
        this.connected = false;
        this.ended = true;
        if (!settled) {
          settled = true;
          clearTimeout(timer);
          resolve(this);
        }
      });

      client.on('end', (reason) => {
        if (!this.kickReason && reason) this.kickReason = String(reason);
        this.connected = false;
        this.ended = true;
        if (!settled) {
          settled = true;
          clearTimeout(timer);
          resolve(this);
        }
      });

      client.on('error', (err) => {
        if (!settled) {
          settled = true;
          clearTimeout(timer);
          reject(err);
        }
      });
    });
  }

  sendCommand(cmd) {
    if (!this.client || !this.connected) {
      throw new Error(`Bot ${this.username} is not connected`);
    }
    const clean = cmd.startsWith('/') ? cmd.slice(1) : cmd;
    try {
      this.client.write('chat_command', { command: clean });
    } catch {
      this.client.write('chat_command_signed', {
        command: clean,
        timestamp: BigInt(Date.now()),
        salt: 0n,
        argumentSignatures: [],
        messageCount: 0,
        acknowledged: Buffer.alloc(3)
      });
    }
  }

  async waitForChat(pattern, timeoutMs = 15000) {
    const regex = pattern instanceof RegExp ? pattern : new RegExp(pattern, 'i');
    const deadline = Date.now() + timeoutMs;
    while (Date.now() < deadline) {
      const hit = this.chatMessages.find(m => regex.test(m));
      if (hit) return hit;
      await sleep(200);
    }
    throw new Error(
      `Bot ${this.username} did not receive chat matching ${regex} within ${timeoutMs}ms. Seen: ${JSON.stringify(this.chatMessages)}`
    );
  }

  disconnect() {
    this.connected = false;
    this.ended = true;
    try {
      this.client?.end('e2e-done');
    } catch { /* ignore */ }
  }
}

export function sleep(ms) {
  return new Promise(r => setTimeout(r, ms));
}

function extractPlainText(value) {
  if (value == null) return '';
  if (typeof value === 'string') {
    try {
      const parsed = JSON.parse(value);
      return extractPlainText(parsed);
    } catch {
      return value;
    }
  }
  if (typeof value === 'object') {
    // Handle minecraft-protocol NBT compound objects ({ type: 'compound', value: { ... } })
    if (value.type && value.value !== undefined) {
      return extractPlainText(value.value);
    }
    let out = '';
    if (typeof value.text === 'string') out += value.text;
    if (value.text && typeof value.text === 'object') out += extractPlainText(value.text);
    if (typeof value.translate === 'string') out += value.translate;
    if (Array.isArray(value.extra)) {
      out += value.extra.map(extractPlainText).join('');
    }
    if (value.extra?.value?.value && Array.isArray(value.extra.value.value)) {
      out += value.extra.value.value.map(extractPlainText).join('');
    }
    if (Array.isArray(value.with)) {
      out += ' ' + value.with.map(extractPlainText).join(' ');
    }
    return out.trim();
  }
  return String(value);
}
