'use strict';

/**
 * HA WebSocket gateway in front of two bridge workers.
 *
 * Public port (default 8766) — Apache ProxyPass stays pointed here.
 * Workers: A on 8768, B on 8769 (configurable).
 *
 * Routing:
 *  - New connections → preferred healthy worker (sticky by active preference)
 *  - Client "reconnect" with session_id → worker that owns that agent
 *  - Worker death → close client so browser/app reconnects; next open picks healthy peer
 *
 * Rolling restart: restart workers one at a time; gateway stays up so clients
 * always have an accept socket. Combined with agent-runtime detach, in-flight
 * Grok processes survive worker restarts and reattach on the next healthy worker.
 */

const http = require('http');
const { WebSocketServer, WebSocket } = require('ws');

function envFirst(...keys) {
    for (const k of keys) {
        const v = process.env[k];
        if (v !== undefined && v !== '') return v;
    }
    return undefined;
}

const PORT = parseInt(envFirst('GROKIFY_BRIDGE_PORT', 'GROKPOT_BRIDGE_PORT') || '8876', 10);
const BACKENDS = (envFirst('GROKIFY_BRIDGE_BACKENDS', 'GROKPOT_BRIDGE_BACKENDS') || '127.0.0.1:8878,127.0.0.1:8879')
    .split(',')
    .map((s) => s.trim())
    .filter(Boolean);

const HEALTH_MS = 2000;
const backendState = new Map(); // host:port → { ok, agents, instance, lastCheck }

function parseBackend(b) {
    const [host, portStr] = b.split(':');
    return { id: b, host: host || '127.0.0.1', port: parseInt(portStr || '8768', 10) };
}

const backends = BACKENDS.map(parseBackend);
let preferIndex = 0;

function log(...args) {
    console.log(new Date().toISOString(), '[gateway]', ...args);
}

function checkHealth(be) {
    return new Promise((resolve) => {
        const req = http.get(
            { host: be.host, port: be.port, path: '/health', timeout: 1500 },
            (res) => {
                let body = '';
                res.on('data', (c) => { body += c; });
                res.on('end', () => {
                    try {
                        const j = JSON.parse(body);
                        backendState.set(be.id, {
                            ok: j.status === 'healthy' || res.statusCode === 200,
                            agents: j.agents || 0,
                            instance: j.instance || be.id,
                            lastCheck: Date.now(),
                        });
                    } catch {
                        backendState.set(be.id, { ok: res.statusCode === 200, agents: 0, lastCheck: Date.now() });
                    }
                    resolve();
                });
            }
        );
        req.on('error', () => {
            backendState.set(be.id, { ok: false, agents: 0, lastCheck: Date.now() });
            resolve();
        });
        req.on('timeout', () => {
            req.destroy();
            backendState.set(be.id, { ok: false, agents: 0, lastCheck: Date.now() });
            resolve();
        });
    });
}

async function refreshHealth() {
    await Promise.all(backends.map(checkHealth));
}

function healthyBackends() {
    return backends.filter((be) => backendState.get(be.id)?.ok);
}

function pickBackend() {
    const healthy = healthyBackends();
    if (!healthy.length) return backends[preferIndex % backends.length] || backends[0];
    // Prefer least agents among healthy
    healthy.sort((a, b) => {
        const aa = backendState.get(a.id)?.agents || 0;
        const bb = backendState.get(b.id)?.agents || 0;
        return aa - bb;
    });
    return healthy[0];
}

function queryAgentOwner(sessionId) {
    return new Promise((resolve) => {
        const healthy = healthyBackends();
        if (!healthy.length) {
            resolve(null);
            return;
        }
        let remaining = healthy.length;
        let found = null;
        for (const be of healthy) {
            const req = http.get(
                {
                    host: be.host,
                    port: be.port,
                    path: `/agent/${encodeURIComponent(sessionId)}`,
                    timeout: 1500,
                },
                (res) => {
                    let body = '';
                    res.on('data', (c) => { body += c; });
                    res.on('end', () => {
                        try {
                            const j = JSON.parse(body);
                            if (j && j.present && !found) found = be;
                        } catch (_) {}
                        remaining--;
                        if (remaining <= 0) resolve(found);
                    });
                }
            );
            req.on('error', () => {
                remaining--;
                if (remaining <= 0) resolve(found);
            });
            req.on('timeout', () => {
                req.destroy();
                remaining--;
                if (remaining <= 0) resolve(found);
            });
        }
    });
}

function openBackendWs(be, reqUrl) {
    // Forward the same path/query (includes token)
    const pathQ = reqUrl || '/';
    const url = `ws://${be.host}:${be.port}${pathQ.startsWith('/') ? pathQ : '/' + pathQ}`;
    return new WebSocket(url);
}

const httpServer = http.createServer(async (req, res) => {
    const url = new URL(req.url || '/', 'http://127.0.0.1');
    if (url.pathname === '/health') {
        await refreshHealth();
        const detail = backends.map((be) => ({
            id: be.id,
            ...(backendState.get(be.id) || { ok: false }),
        }));
        const anyOk = detail.some((d) => d.ok);
        res.writeHead(anyOk ? 200 : 503, { 'Content-Type': 'application/json' });
        res.end(JSON.stringify({
            status: anyOk ? 'healthy' : 'degraded',
            role: 'gateway',
            port: PORT,
            backends: detail,
        }));
        return;
    }
    if (url.pathname === '/usage-tracker') {
        const be = pickBackend();
        const q = url.search || '';
        const proxy = http.request(
            {
                host: be.host,
                port: be.port,
                path: '/usage-tracker' + q,
                method: 'GET',
                headers: { Accept: 'application/json' },
                timeout: 45000,
            },
            (up) => {
                res.writeHead(up.statusCode || 502, { 'Content-Type': 'application/json' });
                up.pipe(res);
            }
        );
        proxy.on('error', () => {
            res.writeHead(502, { 'Content-Type': 'application/json' });
            res.end(JSON.stringify({ ok: false, error: 'backend_unavailable' }));
        });
        proxy.on('timeout', () => {
            proxy.destroy();
            res.writeHead(504, { 'Content-Type': 'application/json' });
            res.end(JSON.stringify({ ok: false, error: 'backend_timeout' }));
        });
        proxy.end();
        return;
    }
    if (url.pathname === '/models') {
        // Proxy models from first healthy backend
        const be = pickBackend();
        const proxy = http.request(
            { host: be.host, port: be.port, path: '/models', method: 'GET' },
            (up) => {
                res.writeHead(up.statusCode || 502, up.headers);
                up.pipe(res);
            }
        );
        proxy.on('error', () => {
            res.writeHead(502, { 'Content-Type': 'application/json' });
            res.end(JSON.stringify({ error: 'backend_unavailable' }));
        });
        proxy.end();
        return;
    }
    if (url.pathname === '/complete' && req.method === 'POST') {
        const be = pickBackend();
        const chunks = [];
        let size = 0;
        req.on('data', (c) => {
            size += c.length;
            if (size > 512 * 1024) {
                res.writeHead(413, { 'Content-Type': 'application/json' });
                res.end(JSON.stringify({ ok: false, error: 'body_too_large' }));
                req.destroy();
                return;
            }
            chunks.push(c);
        });
        req.on('end', () => {
            const body = Buffer.concat(chunks);
            const headers = {
                Accept: 'application/json',
                'Content-Type': req.headers['content-type'] || 'application/json',
                'Content-Length': String(body.length),
            };
            const proxy = http.request(
                {
                    host: be.host,
                    port: be.port,
                    path: '/complete',
                    method: 'POST',
                    headers,
                    timeout: 180000,
                },
                (up) => {
                    res.writeHead(up.statusCode || 502, { 'Content-Type': 'application/json' });
                    up.pipe(res);
                }
            );
            proxy.on('error', () => {
                res.writeHead(502, { 'Content-Type': 'application/json' });
                res.end(JSON.stringify({ ok: false, error: 'backend_unavailable' }));
            });
            proxy.on('timeout', () => {
                proxy.destroy();
                res.writeHead(504, { 'Content-Type': 'application/json' });
                res.end(JSON.stringify({ ok: false, error: 'backend_timeout' }));
            });
            if (body.length) proxy.write(body);
            proxy.end();
        });
        return;
    }
    // PHP usage self-heal: force CLI auth → storage/grok-auth.json via a root worker.
    if (url.pathname === '/sync-grok-auth') {
        const be = pickBackend();
        const q = url.search || '';
        const proxy = http.request(
            {
                host: be.host,
                port: be.port,
                path: '/sync-grok-auth' + q,
                method: req.method === 'POST' ? 'POST' : 'GET',
                headers: { Accept: 'application/json' },
                timeout: 10000,
            },
            (up) => {
                res.writeHead(up.statusCode || 502, { 'Content-Type': 'application/json' });
                up.pipe(res);
            }
        );
        proxy.on('error', () => {
            res.writeHead(502, { 'Content-Type': 'application/json' });
            res.end(JSON.stringify({ ok: false, error: 'backend_unavailable' }));
        });
        proxy.on('timeout', () => {
            proxy.destroy();
            res.writeHead(504, { 'Content-Type': 'application/json' });
            res.end(JSON.stringify({ ok: false, error: 'backend_timeout' }));
        });
        proxy.end();
        return;
    }
    // Device-code OAuth login (start / status / logout) — same path on any healthy worker.
    if (
        url.pathname === '/grok-login/start'
        || url.pathname === '/grok-login/status'
        || url.pathname === '/grok-login/logout'
    ) {
        const be = pickBackend();
        const q = url.search || '';
        const method = req.method === 'POST' ? 'POST' : 'GET';
        const proxy = http.request(
            {
                host: be.host,
                port: be.port,
                path: url.pathname + q,
                method,
                headers: { Accept: 'application/json' },
                timeout: 20000,
            },
            (up) => {
                res.writeHead(up.statusCode || 502, { 'Content-Type': 'application/json' });
                up.pipe(res);
            }
        );
        proxy.on('error', () => {
            res.writeHead(502, { 'Content-Type': 'application/json' });
            res.end(JSON.stringify({ ok: false, error: 'backend_unavailable' }));
        });
        proxy.on('timeout', () => {
            proxy.destroy();
            res.writeHead(504, { 'Content-Type': 'application/json' });
            res.end(JSON.stringify({ ok: false, error: 'backend_timeout' }));
        });
        proxy.end();
        return;
    }
    // Agent working directory (get / set / list) — any healthy worker (DB-backed).
    if (url.pathname === '/work-dir' || url.pathname === '/work-dir/list') {
        const be = pickBackend();
        const q = url.search || '';
        const method = req.method === 'POST' ? 'POST' : 'GET';
        const chunks = [];
        req.on('data', (c) => chunks.push(c));
        req.on('end', () => {
            const body = Buffer.concat(chunks);
            const headers = { Accept: 'application/json' };
            if (method === 'POST' && body.length) {
                headers['Content-Type'] = req.headers['content-type'] || 'application/json';
                headers['Content-Length'] = String(body.length);
            }
            const proxy = http.request(
                {
                    host: be.host,
                    port: be.port,
                    path: url.pathname + q,
                    method,
                    headers,
                    timeout: 10000,
                },
                (up) => {
                    res.writeHead(up.statusCode || 502, { 'Content-Type': 'application/json' });
                    up.pipe(res);
                }
            );
            proxy.on('error', () => {
                res.writeHead(502, { 'Content-Type': 'application/json' });
                res.end(JSON.stringify({ ok: false, error: 'backend_unavailable' }));
            });
            proxy.on('timeout', () => {
                proxy.destroy();
                res.writeHead(504, { 'Content-Type': 'application/json' });
                res.end(JSON.stringify({ ok: false, error: 'backend_timeout' }));
            });
            if (method === 'POST' && body.length) proxy.write(body);
            proxy.end();
        });
        return;
    }
    res.writeHead(404, { 'Content-Type': 'application/json' });
    res.end(JSON.stringify({ error: 'not_found', role: 'gateway' }));
});

const wss = new WebSocketServer({ noServer: true });

httpServer.on('upgrade', (request, socket, head) => {
    wss.handleUpgrade(request, socket, head, (ws) => {
        wss.emit('connection', ws, request);
    });
});

wss.on('connection', (client, req) => {
    let backend = null;
    let upstream = null;
    let closed = false;
    const pending = [];
    let routed = false;

    const cleanup = () => {
        if (closed) return;
        closed = true;
        try { if (upstream && upstream.readyState === WebSocket.OPEN) upstream.close(); } catch (_) {}
        try { if (client.readyState === WebSocket.OPEN) client.close(); } catch (_) {}
    };

    client.on('close', cleanup);
    client.on('error', cleanup);

    async function connectUpstream(be, reqUrl) {
        return new Promise((resolve, reject) => {
            const u = openBackendWs(be, reqUrl);
            const t = setTimeout(() => {
                try { u.terminate(); } catch (_) {}
                reject(new Error('upstream timeout'));
            }, 8000);
            u.on('open', () => {
                clearTimeout(t);
                resolve(u);
            });
            u.on('error', (err) => {
                clearTimeout(t);
                reject(err);
            });
        });
    }

    async function routeAndPipe(firstMsg) {
        if (routed) return;
        routed = true;

        await refreshHealth();

        // Prefer owner of session on reconnect
        let be = null;
        let sessionId = null;
        try {
            if (firstMsg) {
                const data = JSON.parse(firstMsg);
                if (data.type === 'reconnect' && data.session_id) {
                    sessionId = data.session_id;
                } else if (data.session_id) {
                    sessionId = data.session_id;
                }
            }
        } catch (_) {}

        if (sessionId && /^[a-f0-9]{32}$/.test(sessionId)) {
            be = await queryAgentOwner(sessionId);
        }
        if (!be) be = pickBackend();
        backend = be;

        const reqUrl = req.url || '/';
        try {
            upstream = await connectUpstream(be, reqUrl);
        } catch (err) {
            // Try the other backend
            const alt = backends.find((b) => b.id !== be.id);
            if (alt) {
                try {
                    upstream = await connectUpstream(alt, reqUrl);
                    backend = alt;
                } catch (err2) {
                    try {
                        client.send(JSON.stringify({
                            type: 'error',
                            content: 'All bridges offline — retry in a moment',
                        }));
                    } catch (_) {}
                    cleanup();
                    return;
                }
            } else {
                try {
                    client.send(JSON.stringify({
                        type: 'error',
                        content: 'Bridge offline — retry in a moment',
                    }));
                } catch (_) {}
                cleanup();
                return;
            }
        }

        upstream.on('message', (data, isBinary) => {
            if (client.readyState === WebSocket.OPEN) {
                try { client.send(data, { binary: !!isBinary }); } catch (_) {}
            }
        });
        upstream.on('close', () => {
            // Signal client so it reconnects (may land on other worker + recover agent)
            try {
                if (client.readyState === WebSocket.OPEN) {
                    client.send(JSON.stringify({
                        type: 'bridge_stopping',
                        reason: 'worker_restart',
                        backend: backend?.id,
                    }));
                }
            } catch (_) {}
            cleanup();
        });
        upstream.on('error', () => cleanup());

        // Flush first message + any buffered
        const toSend = [];
        if (firstMsg != null) toSend.push(firstMsg);
        while (pending.length) toSend.push(pending.shift());
        for (const m of toSend) {
            if (upstream.readyState === WebSocket.OPEN) {
                try { upstream.send(m); } catch (_) {}
            }
        }
    }

    client.on('message', (data, isBinary) => {
        const text = isBinary ? data : data.toString();
        if (!routed) {
            // Buffer until we pick backend (need first JSON for session routing)
            if (pending.length === 0 && !isBinary) {
                routeAndPipe(text).catch(() => cleanup());
            } else {
                pending.push(isBinary ? data : text);
                if (!routed) {
                    // Safety: if first frame wasn't JSON-friendly, still route
                    routeAndPipe(null).catch(() => cleanup());
                }
            }
            return;
        }
        if (upstream && upstream.readyState === WebSocket.OPEN) {
            try { upstream.send(data, { binary: !!isBinary }); } catch (_) {}
        }
    });

    // If client never sends (unusual), still attach to a backend after short wait
    setTimeout(() => {
        if (!routed && !closed) {
            routeAndPipe(null).catch(() => cleanup());
        }
    }, 500);
});

refreshHealth().then(() => {
    httpServer.listen(PORT, () => {
        log(`listening on :${PORT}, backends=${backends.map((b) => b.id).join(',')}`);
    });
});

setInterval(() => {
    refreshHealth().catch(() => {});
}, HEALTH_MS);

// Alternate preferred index so deploys can "flip" load during rolling restart
setInterval(() => {
    preferIndex = (preferIndex + 1) % Math.max(backends.length, 1);
}, 60 * 1000);

process.on('SIGTERM', () => {
    log('SIGTERM — closing gateway (workers keep agents)');
    try { httpServer.close(); } catch (_) {}
    setTimeout(() => process.exit(0), 200);
});
process.on('SIGINT', () => {
    try { httpServer.close(); } catch (_) {}
    setTimeout(() => process.exit(0), 200);
});
