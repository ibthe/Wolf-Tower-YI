const http = require('http');

const DOMAIN_OK = /^[a-z0-9.-]+$/i;

/** PAC-файл: через ByeDPI идут только домены выбранных сервисов, всё остальное напрямую. */
function buildPac(domains, port) {
  const list = domains.filter((d) => DOMAIN_OK.test(d)).map((d) => d.toLowerCase());
  const proxy = `SOCKS5 127.0.0.1:${port}; SOCKS 127.0.0.1:${port}; DIRECT`;
  return (
    'function FindProxyForURL(url, host) {\n' +
    '  host = host.toLowerCase();\n' +
    `  var d = ${JSON.stringify(list)};\n` +
    '  for (var i = 0; i < d.length; i++) {\n' +
    `    if (host === d[i] || dnsDomainIs(host, "." + d[i])) return "${proxy}";\n` +
    '  }\n' +
    '  return "DIRECT";\n' +
    '}\n'
  );
}

/** Локальный HTTP-сервер, который отдаёт текущий PAC. */
class PacServer {
  constructor() {
    this.content = buildPac([], 0);
    this.server = null;
    this.port = 0;
  }

  async start() {
    if (this.server) return;
    this.server = http.createServer((req, res) => {
      res.writeHead(200, { 'Content-Type': 'application/x-ns-proxy-autoconfig', 'Cache-Control': 'no-store' });
      res.end(this.content);
    });
    await new Promise((resolve, reject) => {
      this.server.once('error', reject);
      this.server.listen(0, '127.0.0.1', resolve);
    });
    this.port = this.server.address().port;
  }

  get url() {
    return `http://127.0.0.1:${this.port}/proxy.pac`;
  }

  /** domains пустой: все запросы идут напрямую (защита выключена). */
  update(domains, socksPort) {
    this.content = buildPac(domains, socksPort);
  }

  async stop() {
    if (!this.server) return;
    const s = this.server;
    this.server = null;
    await new Promise((resolve) => s.close(resolve));
  }
}

module.exports = { buildPac, PacServer };
