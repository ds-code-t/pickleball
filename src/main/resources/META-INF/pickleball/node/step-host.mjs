import fs from "node:fs";
import { pathToFileURL } from "node:url";

// One JSON object per line. Callbacks use readSync so a call does not deadlock on a buffered stdin.
let pending = Buffer.alloc(0);
const registry = new Map();

function write(message) {
    fs.writeSync(1, JSON.stringify(message) + "\n");
}

function readLine() {
    while (true) {
        const newline = pending.indexOf(0x0a);
        if (newline >= 0) {
            const line = pending.subarray(0, newline).toString("utf8").replace(/\r$/, "");
            pending = pending.subarray(newline + 1);
            return line;
        }
        const chunk = Buffer.alloc(8192);
        const count = fs.readSync(0, chunk, 0, chunk.length, null);
        if (count === 0) {
            if (pending.length === 0) return null;
            const line = pending.toString("utf8");
            pending = Buffer.alloc(0);
            return line;
        }
        pending = Buffer.concat([pending, chunk.subarray(0, count)]);
    }
}

let nextId = 1;

function call(fn, args) {
    const id = nextId++;
    write({ id, op: "call", fn, args: args || [] });
    while (true) {
        const line = readLine();
        if (line == null) throw new Error("step host closed");
        if (!line.trim()) continue;
        const message = JSON.parse(line);
        if (message.id !== id) continue;
        if (message.ok === false) throw new Error(message.error || "call failed");
        return message.value;
    }
}

function normalize(pattern) {
    let text = pattern instanceof RegExp ? pattern.source : String(pattern);
    const slash = text.startsWith("/") ? text.lastIndexOf("/") : -1;
    if (slash > 0) text = text.slice(1, slash);
    if (text.startsWith("^")) text = text.slice(1);
    if (text.endsWith("$")) text = text.slice(0, -1);
    return text;
}

const bridge = {
    defineStep() {
    },
    resolveText(text) {
        return call("resolveText", [text]);
    },
    saveValue(name, value) {
        return call("saveValue", [name, value]);
    },
    getValue(query) {
        return call("getValue", [query]);
    },
    softFail(message) {
        return call("softFail", [message]);
    },
    secret(name) {
        return call("secret", [name]);
    },
    log(message) {
        return call("log", [message]);
    },
    entityGet(source) {
        return call("entityGet", [source]);
    },
    call(fn, args) {
        return call(fn, args || []);
    }
};
globalThis.__pkb = bridge;

function select(found, pattern) {
    const wanted = normalize(pattern || "");
    const match = found.find((item) => item.pattern === wanted);
    if (match) return match.fn;
    if (found.length === 1) return found[0].fn;
    return null;
}

async function load(message) {
    const found = [];
    bridge.defineStep = (pattern, fn) => {
        found.push({ pattern: normalize(pattern), fn });
    };
    try {
        const url = pathToFileURL(message.file).href + "?h=" + encodeURIComponent(message.hash || String(Date.now()));
        await import(url);
        const fn = select(found, message.pattern);
        if (!fn) throw new Error("no defineStep for " + message.definitionId);
        registry.set(message.definitionId, fn);
        write({ id: message.id, ok: true });
    } catch (failure) {
        write({ id: message.id, ok: false, error: String(failure && failure.message ? failure.message : failure) });
    } finally {
        bridge.defineStep = () => {
        };
    }
}

async function invoke(message) {
    const fn = registry.get(message.definitionId);
    if (!fn) {
        write({ id: message.id, ok: false, error: "definition not loaded" });
        return;
    }
    try {
        const args = Array.isArray(message.args) ? message.args : [];
        const value = await fn(...args);
        write({ id: message.id, ok: true, value: value === undefined ? null : value });
    } catch (failure) {
        write({ id: message.id, ok: false, error: String(failure && failure.message ? failure.message : failure) });
    }
}

async function main() {
    while (true) {
        const line = readLine();
        if (line == null) return;
        if (!line.trim()) continue;
        let message;
        try {
            message = JSON.parse(line);
        } catch (failure) {
            fs.writeSync(2, String(failure && failure.message ? failure.message : failure) + "\n");
            continue;
        }
        if (message.op === "load") await load(message);
        else if (message.op === "invoke") await invoke(message);
        else write({ id: message.id, ok: false, error: "unknown op" });
    }
}

main();
