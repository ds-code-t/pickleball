# Browser protocols

Pickleball controls and listens to a browser through four backends. The event stream is in [Browser events](browser-events.md), Playwright attach in [Playwright](playwright.md), and the opt-in axe, Lighthouse and chrome-devtools-mcp add-ons in [Optional installs](optional-installs.md).


| Backend | What it is | Browsers |
|---------|------------|----------|
| `playwright` | Playwright for Java 1.65.1 attached with `connectOverCDP` (D12). Never launches or downloads a browser. | Chrome, Edge, Chromium |
| `bidi` | WebDriver BiDi over pickleball's own JDK WebSocket client (`BidiModules`). | Firefox, Chrome and Edge through chromedriver/msedgedriver |
| `cdp` | The Chrome DevTools Protocol over the same client (`RpcChannel`, `CdpAdapter`). | Chrome, Edge, Chromium. Never Firefox. |
| `classic` | Selenium WebDriver classic commands. | Every Selenium browser |

## Fallback order

`BrowserProtocols` keeps, per session, which backends are live and resolves each capability in one order:

1. Playwright, when the session is Playwright-attached (`pkb_protocol=playwright`, or `--via=playwright`).
2. WebDriver BiDi: always on Firefox; on Chromium when a BiDi socket exists (Selenium sessions ask for one by default, `pkb_bidi=false` turns that off).
3. CDP: Chromium only. On a Chrome session with only a BiDi socket, chromedriver's `goog:cdp.sendCommand` is the bridge.
4. Selenium classic.
5. `error: unsupported: <capability> on <browser>/<protocol>` with a `fix:` line. Nothing fails silently.

Capabilities are probed lazily and cached per session. An "unknown command" or "unsupported operation" reply marks that capability unavailable on that backend and the next one is tried. Chrome documents CDP as unsupported for third-party use, so experimental domains are wrapped in the same probe and degrade the same way.

## `--via`

Every protocol verb accepts `--via=auto|playwright|bidi|cdp|classic`. `auto` follows the order above. A named backend is used alone: when it cannot serve the capability the reply is `error: unsupported:` and the fix names the backends that can. Every reply says which backend served it, for example `"via":"bidi"`.

## What is live

```
doctor --protocols [--cdp-endpoint=<http/ws url|chrome|msedge>] [--bidi-endpoint=<ws url>]
```

prints `protocols=chrome:playwright,bidi,cdp,classic`, then `protocol.backend.<id>=yes <version>|no` and `protocol.capability.<name>=<backend> (also ...)`, `via <backend> only`, or `unsupported`. Plain `doctor` prints the `protocols=` line only. With no flag, doctor uses `pkb_cdp_endpoint` / `pkb_bidi_endpoint`, then this project's live browser. It probes 127.0.0.1 endpoints only.

## Support by browser

As observed by the `doctor --protocols` probes and the live tests on this release's CI box (Chrome 156, Playwright 1.65.1, Selenium 4.54). Firefox is not installed there, so its column comes from the BiDi paths and the rules below, not a live probe.

| Capability | Chrome / Edge | Firefox | Safari |
|------------|---------------|---------|--------|
| Event stream (net, console, error, nav, dialog) | Playwright, BiDi, CDP | BiDi | no |
| Network control, HAR, route, auth | Playwright, BiDi, CDP | BiDi | no |
| Emulation, clock, permissions | Playwright, BiDi, CDP | BiDi (subset) | no |
| Contexts, tabs, storage, downloads, uploads, dialogs | Playwright, BiDi, CDP | BiDi | no |
| Input chains, `find --role` | Playwright, BiDi, CDP | BiDi | no |
| `aria-snap`, `aria-act` | Playwright | no | no |
| WebAuthn | Playwright, CDP | no | no |
| Extensions | BiDi, CDP (needs `--enable-unsafe-extension-debugging`) | BiDi | no |
| WebMCP | Playwright where the page has it | no | no |
| Vitals, metrics | Playwright, BiDi, CDP | BiDi | no |
| Traces, heap snapshot | Playwright, CDP | no | no |
| Security, audits, service workers, DOM storage rows | CDP | no | no |
| Screencast | Playwright (webm needs its ffmpeg), CDP frames | BiDi where implemented | no |
| `share-browser` | Playwright | no | no |
| `audit --axe` | CDP isolated world, Playwright | no | no |
| `audit --lighthouse`, `bridge --devtools-mcp` | CDP port, localhost | no | no |

## Browser rules

- **Chrome 136 and later** ignore `--remote-debugging-port` on the default profile. Use a separate `--user-data-dir`, or the Chrome 144+ route: enable "Allow remote debugging for this browser instance" at `chrome://inspect/#remote-debugging` and attach with `attach --cdp-endpoint=chrome` (see [Playwright](playwright.md)).
- **Firefox** 141 and later have no CDP. Pickleball never tries CDP on Firefox; every Firefox capability goes through BiDi or classic.
- **Safari** is not supported by these protocol verbs. safaridriver has no BiDi or CDP endpoint pickleball can use.
- **CDP** is documented by Chrome as not supported for third-party use; domains can change between Chrome versions. Pickleball probes and degrades instead of failing a run.

## Security

A debugging endpoint gives full control of the browser: anything that can reach it can read cookies and drive pages. Pickleball only discovers endpoints on 127.0.0.1, never opens one to the network, logs every attach with tokens redacted, and refuses `relaunch-with-debug` without `--confirm`. Do not expose `--remote-debugging-port` or a BiDi socket beyond localhost. `share-browser` binds Playwright's server to a local pipe or 127.0.0.1 only, and anything on the machine that can reach it can drive the browser. `bridge --devtools-mcp` and `audit --lighthouse` refuse non-loopback endpoints. Secrets (auth credentials, WebAuthn keys, storage values) are redacted in replies, logs and rows; `browser-events --reveal` (control tier) shows storage values from host memory only. Optional add-ons download only from `optional-install <name> --confirm`, from registry.npmjs.org, against pinned SHA-512 integrity.
