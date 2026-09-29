#!/usr/bin/env node
/**
 * e2e-https-relay.mjs — TLS front door for the on-device E2E suite.
 *
 * `opencode serve` speaks plain HTTP. The B5/B6 matrix rows need the same server over HTTPS
 * with a self-signed certificate, so this relay terminates TLS and forwards bytes to the
 * plain-HTTP backend. It is test scaffolding: never used by the app itself.
 *
 * Usage: node scripts/e2e-https-relay.mjs <cert.pem> <key.pem> <listen-port> <backend-port>
 */
import tls from "node:tls";
import net from "node:net";
import fs from "node:fs";

const [certPath, keyPath, listenPortArg, backendPortArg] = process.argv.slice(2);
if (!certPath || !keyPath || !listenPortArg || !backendPortArg) {
  console.error("usage: e2e-https-relay.mjs <cert.pem> <key.pem> <listen-port> <backend-port>");
  process.exit(2);
}
const listenPort = Number(listenPortArg);
const backendPort = Number(backendPortArg);

const server = tls.createServer(
  {
    cert: fs.readFileSync(certPath),
    key: fs.readFileSync(keyPath),
  },
  (tlsSocket) => {
    const upstream = net.connect(backendPort, "127.0.0.1");
    const destroy = () => {
      tlsSocket.destroy();
      upstream.destroy();
    };
    tlsSocket.on("error", destroy);
    upstream.on("error", destroy);
    upstream.on("connect", () => {
      tlsSocket.pipe(upstream);
      upstream.pipe(tlsSocket);
    });
  },
);

server.on("error", (err) => {
  console.error(`e2e-https relay failed: ${err.message}`);
  process.exit(1);
});

server.listen(listenPort, "0.0.0.0", () => {
  console.log(`e2e-https relay: 0.0.0.0:${listenPort} -> 127.0.0.1:${backendPort}`);
});
