// Palette file: colors.json. The page fetches that file and passes the array here.
export function colorFor(name, colors) {
    const list = Array.isArray(colors) ? colors : [];
    if (!list.length) return "";
    let hash = 0;
    const text = name == null ? "" : String(name);
    for (let index = 0; index < text.length; index++) {
        hash = (hash + text.charCodeAt(index)) % list.length;
    }
    return list[hash];
}
