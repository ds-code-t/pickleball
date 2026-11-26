import http from "node:http";
import fs from "node:fs";
import path from "node:path";

const token = process.env.PKB_UI_TOKEN || "";
const root = process.env.PKB_UI_ROOT || import.meta.dirname;
const hostFile = process.env.PKB_UI_HOST || "";
const bridgePort = process.env.PKB_UI_BRIDGE_PORT || "";
const project = process.env.PKB_UI_PROJECT || "";
const pageFile = path.join(root, "pages/index.html");

function same(left, right) {
    const a = Buffer.from(String(left));
    const b = Buffer.from(String(right));
    const length = Math.max(a.length, b.length);
    let diff = a.length === b.length ? 0 : 1;
    for (let index = 0; index < length; index++) {
        diff |= (a[index] || 0) ^ (b[index] || 0);
    }
    return diff === 0 && a.length > 0;
}

function hostOk(header) {
    const value = String(header || "");
    const name = value.split(":")[0];
    return name === "127.0.0.1";
}

function presentedToken(req, url) {
    const query = url.searchParams.get("t") || "";
    const header = req.headers["x-pkb-ui-token"] || "";
    if (query && same(query, token)) return true;
    if (header && same(header, token)) return true;
    return false;
}

function contentType(file) {
    const ext = path.extname(file).toLowerCase();
    if (ext === ".js" || ext === ".mjs") return "application/javascript; charset=utf-8";
    if (ext === ".css") return "text/css; charset=utf-8";
    if (ext === ".json" || ext === ".map") return "application/json; charset=utf-8";
    if (ext === ".svg") return "image/svg+xml";
    if (ext === ".ttf") return "font/ttf";
    if (ext === ".woff") return "font/woff";
    if (ext === ".woff2") return "font/woff2";
    return "application/octet-stream";
}

function send(res, status, body, type) {
    const bytes = Buffer.from(body);
    res.writeHead(status, {
        "content-type": type || "text/plain; charset=utf-8",
        "content-length": bytes.length,
        "cache-control": "no-store"
    });
    res.end(bytes);
}

function forward(verb, args, requestToken) {
    const payload = JSON.stringify({ verb: verb, args: args });
    return new Promise((resolve) => {
        const request = http.request({
            host: "127.0.0.1",
            port: Number(bridgePort),
            method: "POST",
            path: "/",
            headers: {
                "content-type": "application/json",
                "content-length": Buffer.byteLength(payload),
                "x-pkb-ui-token": requestToken
            }
        }, (response) => {
            const chunks = [];
            response.on("data", (chunk) => chunks.push(chunk));
            response.on("end", () => resolve(Buffer.concat(chunks).toString("utf8")));
        });
        request.on("error", (failure) => {
            resolve(JSON.stringify({ code: 1, out: "", err: "error: " + failure.message + "\nfix: pickleball ui\n" }));
        });
        request.end(payload);
    });
}

function safePart(value) {
    return /^[A-Za-z0-9._-]+$/.test(value);
}

function sessionAuthorized(presented) {
    if (!project || !presented) return false;
    const dir = path.join(project, ".pickleball", "workbench-ui", "sessions");
    let names;
    try {
        names = fs.readdirSync(dir);
    } catch (failure) {
        return false;
    }
    for (const name of names) {
        if (!name.endsWith(".json")) continue;
        try {
            const node = JSON.parse(fs.readFileSync(path.join(dir, name), "utf8"));
            if (node && same(String(node.token || ""), presented)) return true;
        } catch (failure) {
            // A bad session file is not a token.
        }
    }
    return false;
}

function storeAuthorized(req, url) {
    const query = url.searchParams.get("t") || "";
    const header = String(req.headers["x-pkb-ui-token"] || "");
    if (query && same(query, token)) return true;
    if (header && same(header, token)) return true;
    if (query && sessionAuthorized(query)) return true;
    if (header && sessionAuthorized(header)) return true;
    return false;
}

function serveStore(req, res, url) {
    if (!storeAuthorized(req, url)) {
        send(res, 401, "unauthorized\n");
        return;
    }
    const chunks = [];
    req.on("data", (chunk) => chunks.push(chunk));
    req.on("end", async () => {
        let args = [];
        let verb = "store";
        try {
            const body = JSON.parse(Buffer.concat(chunks).toString("utf8") || "{}");
            if (Array.isArray(body.args)) args = body.args.map((item) => String(item));
            if (body && typeof body.verb === "string" && body.verb.trim()) verb = body.verb.trim();
        } catch (failure) {
            send(res, 400, JSON.stringify({ code: 2, out: "", err: "error: ui body is not json\nfix: send args\n" }));
            return;
        }
        const result = await forward(verb, args, token);
        send(res, 200, result, "application/json; charset=utf-8");
    });
}

function serveGenerated(req, res, url, name) {
    const prefix = "/ui/";
    if (req.method !== "GET" || !name.startsWith(prefix)) return false;
    const parts = name.slice(prefix.length).split("/");
    if (parts.length !== 2 || !safePart(parts[0]) || !safePart(parts[1])) {
        send(res, 404, "not found\n");
        return true;
    }
    const session = parts[0];
    const page = parts[1];
    let expected = "";
    if (project) {
        const sessionFile = path.join(project, ".pickleball", "workbench-ui", "sessions", session + ".json");
        try {
            const node = JSON.parse(fs.readFileSync(sessionFile, "utf8"));
            expected = String(node.token || "");
        } catch (failure) {
            expected = "";
        }
    }
    const query = url.searchParams.get("t") || "";
    const header = req.headers["x-pkb-ui-token"] || "";
    const presented = query || String(header);
    if (!expected || !same(presented, expected)) {
        send(res, 401, "unauthorized\n");
        return true;
    }
    const file = path.resolve(path.join(project, ".pickleball", "workbench-ui", "generated", session, page + ".html"));
    const base = path.resolve(path.join(project, ".pickleball", "workbench-ui", "generated", session));
    if (file !== base && !file.startsWith(base + path.sep)) {
        send(res, 404, "not found\n");
        return true;
    }
    if (!fs.existsSync(file) || !fs.statSync(file).isFile()) {
        send(res, 404, "not found\n");
        return true;
    }
    send(res, 200, fs.readFileSync(file, "utf8"), "text/html; charset=utf-8");
    return true;
}

const server = http.createServer((req, res) => {
    const url = new URL(req.url || "/", "http://127.0.0.1");
    if (!hostOk(req.headers.host)) {
        send(res, 403, "forbidden\n");
        return;
    }
    const name = url.pathname;
    if (serveGenerated(req, res, url, name)) return;
    if (req.method === "POST" && name === "/store") {
        serveStore(req, res, url);
        return;
    }
    if (!presentedToken(req, url)) {
        send(res, 401, "unauthorized\n");
        return;
    }
    if (req.method === "GET" && (name === "/" || name === "/scenarios" || name === "/globals" || name === "/data" || name === "/sql")) {
        send(res, 200, fs.readFileSync(pageFile), "text/html; charset=utf-8");
        return;
    }
    const assetPrefix = "/node_modules/";
    if (req.method === "GET" && name.startsWith(assetPrefix)) {
        const relative = name.slice(1);
        if (relative.split("/").includes("..")) {
            send(res, 404, "not found\n");
            return;
        }
        const file = path.resolve(root, relative);
        const base = path.resolve(root);
        if (file !== base && !file.startsWith(base + path.sep)) {
            send(res, 404, "not found\n");
            return;
        }
        if (!fs.existsSync(file) || !fs.statSync(file).isFile()) {
            send(res, 404, "not found\n");
            return;
        }
        const bytes = fs.readFileSync(file);
        res.writeHead(200, {
            "content-type": contentType(file),
            "content-length": bytes.length,
            "cache-control": "no-store"
        });
        res.end(bytes);
        return;
    }
    send(res, 404, "not found\n");
});

server.listen(0, "127.0.0.1", () => {
    const address = server.address();
    const port = address && typeof address === "object" ? address.port : 0;
    if (!hostFile) return;
    const directory = hostFile.replace(/[\\/][^\\/]*$/, "");
    fs.mkdirSync(directory, { recursive: true });
    fs.writeFileSync(hostFile, JSON.stringify({ port: port, token: token, state: "open" }) + "\n");
});
