import http from "node:http";
import fs from "node:fs";

const token = process.env.PKB_UI_TOKEN || "";
const root = process.env.PKB_UI_ROOT || import.meta.dirname;
const hostFile = process.env.PKB_UI_HOST || "";
const bridgePort = process.env.PKB_UI_BRIDGE_PORT || "";
const pageFile = root + "/" + "pages/index.html";

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

const server = http.createServer((req, res) => {
    const url = new URL(req.url || "/", "http://127.0.0.1");
    if (!hostOk(req.headers.host)) {
        send(res, 403, "forbidden\n");
        return;
    }
    if (!presentedToken(req, url)) {
        send(res, 401, "unauthorized\n");
        return;
    }
    const name = url.pathname;
    if (req.method === "GET" && (name === "/" || name === "/scenarios" || name === "/globals" || name === "/data" || name === "/sql")) {
        send(res, 200, fs.readFileSync(pageFile), "text/html; charset=utf-8");
        return;
    }
    if (req.method === "POST" && name === "/store") {
        const chunks = [];
        req.on("data", (chunk) => chunks.push(chunk));
        req.on("end", async () => {
            let args = [];
            try {
                const body = JSON.parse(Buffer.concat(chunks).toString("utf8") || "{}");
                if (Array.isArray(body.args)) args = body.args.map((item) => String(item));
            } catch (failure) {
                send(res, 400, JSON.stringify({ code: 2, out: "", err: "error: ui body is not json\nfix: send args\n" }));
                return;
            }
            const result = await forward("store", args, token);
            send(res, 200, result, "application/json; charset=utf-8");
        });
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
