function api() {
    const bridge = globalThis.__pkb;
    if (!bridge) throw new Error("step host bridge is not installed");
    return bridge;
}

export function defineStep(pattern, fn) {
    return api().defineStep(pattern, fn);
}

export function Given(pattern, fn) {
    return api().defineStep(pattern, fn);
}

export function When(pattern, fn) {
    return api().defineStep(pattern, fn);
}

export function Then(pattern, fn) {
    return api().defineStep(pattern, fn);
}

export function And(pattern, fn) {
    return api().defineStep(pattern, fn);
}

export function But(pattern, fn) {
    return api().defineStep(pattern, fn);
}

export function resolveText(text) {
    return api().resolveText(text);
}

export function saveValue(name, value) {
    return api().saveValue(name, value);
}

export function getValue(query) {
    return api().getValue(query);
}

export function softFail(message) {
    return api().softFail(message);
}

export function secret(name) {
    return api().secret(name);
}

export function log(message) {
    return api().log(message);
}

export function entityGet(source) {
    return api().entityGet(source);
}

export function call(fn, args) {
    return api().call(fn, args);
}
