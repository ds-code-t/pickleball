# Playwright backend and attaching to a running browser

Playwright for Java 1.65.1 (Apache-2.0) and its `driver-bundle` (Apache-2.0, with a bundled Node.js under the Node.js license) are normal dependencies of pickleball. They are not inside pickleball's jar. Pickleball never downloads Playwright browsers: Playwright is created with `PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD=1`, `install` is never run, and Playwright only joins a browser that is already running, with `chromium().connectOverCDP(...)`. Configuration is in [Configuration](configuration.md#playwright-backend).

## Attach to a running browser

`attach` is the one entry point. `attach --target=browser:cdp=<ws>` works as before. The route flags below resolve to a browser websocket and then run that same join, so the attached browser is a normal live target: pass the printed `--target=browser:cdp=<ws>` to `snap`, `find`, `act`, `panel-open`, and the collab verbs. Routes, in the order to try them:

1. **Channel.** `attach --cdp-endpoint=chrome` (also `chrome-beta`, `chrome-dev`, `chrome-canary`, `msedge`, `msedge-beta`, `msedge-dev`, `msedge-canary`). In the running browser, open `chrome://inspect/#remote-debugging` and enable "Allow remote debugging for this browser instance". No launch flags are needed. Pickleball reads that browser's `DevToolsActivePort` file in your own profile dir (port, then the browser path) and joins `ws://127.0.0.1:<port><path>`, the same way Playwright resolves a channel name.
2. **Playwright Extension.** `attach --extension[=chrome|msedge] [--profile-dir-name=<dir>]`. Installing the [Playwright Extension](https://chromewebstore.google.com/detail/playwright-extension/mmlmfjhmonkocbjadbfplnigmagldckm) is your action in your browser. Approve the connect page, or put the token from the extension's Manage clients page in `PLAYWRIGHT_MCP_EXTENSION_TOKEN`. The token is a secret: pickleball reads it from the environment, passes it only to the child process environment, and never prints, logs, or stores it. The extension route exists only in the Node Playwright CLI, so pickleball runs `cli.js cli -s=pkb-extension attach --extension=<channel>` with the Node inside `driver-bundle`, located by Playwright's own driver resolution. No user Node install and no npm. The extension speaks a relay, not a CDP port, so `snap`, `find`, and `act` do not run on an extension session; drive it with the Playwright CLI session `pkb-extension`.
3. **Explicit endpoints and discovery.** `attach --cdp-endpoint=http://127.0.0.1:9222` (any Chromium with a debugging port, Edge, Electron, or a `ws://` browser URL). `attach --selenium=<host:port>` takes a Selenium session's `goog:chromeOptions.debuggerAddress` (or its `se:cdp` URL). `attach --endpoint=ws://...` connects to a Playwright server; that speaks the Playwright protocol, not CDP, so it serves the Playwright verbs only. `attach --discover` lists candidates from the `DevToolsActivePort` files in your own Chrome and Edge profile dirs and from ports 9222, 9223, 9224, 9229, and 9333 on 127.0.0.1 (`/json/version`). `attach --discover --candidate=<n>` joins one.
4. **Relaunch, last resort.** `relaunch-with-debug --browser=chrome|msedge [--port=9222] --confirm` closes your running browser (a normal close of processes you own) and reopens it with the same profile and `--remote-debugging-port`. In-memory state of unsaved tabs and forms is lost. It refuses without `--confirm`. Chrome 136 and later ignore `--remote-debugging-port` on the default profile; use the channel route there.

`doctor` prints `attachRoutes=channel:yes|no,extension:...,endpoints:...,selenium:...,relaunch:...` from files only. `doctor --attach` also probes 127.0.0.1 and prints `attach=[...]` with each route's state and the exact `fix:` for a missing one.

Firefox attaches only over WebDriver BiDi when it was started with its remote agent; there is no channel or extension route. WebKit has no generic attach.

Safety: no injection into other processes. Discovery reads only `DevToolsActivePort` and endpoint metadata in the current user's own browser profile dirs, never cookies, passwords, or another OS user's files, and probes only 127.0.0.1. A non-local host is used only when you pass it explicitly. Every attach is logged to `.pickleball/logs/attach.log` with the endpoint redacted (query tokens and user info become `<redacted>`).

## Selenium and Playwright on the same browser

`BackendHandoff.toPlaywright(driver)` returns the Playwright `Page` for the WebDriver's current URL over the driver's own CDP endpoint. `BackendHandoff.toSelenium(connection, page)` attaches a ChromeDriver with `debuggerAddress` and switches to that page's window; `BackendHandoff.release(driver)` ends it and the browser keeps running. Nothing restarts. Carried over: the browser process, tabs and pages, cookies, storage, and the default context. Not carried over: Playwright route handlers, init scripts, bindings, and listeners (they live in the Playwright client), and Selenium implicit waits, timeouts, and window-handle bookkeeping (they live in the WebDriver session).

## Playwright verbs

`pw <area> <action> [--flags]` runs one Playwright step on the current page of the browser it attaches to: `--target=browser:cdp=<ws>`, `--cdp-endpoint=<channel|url>`, `pkb_cdp_endpoint`, or else this project's live browser (its own profile's `DevToolsActivePort`). With none, the reply is `error: no CDP endpoint for Playwright ...` with a `fix:`. `cdp --method=<Domain.method> [--params=<json>]` sends one raw CDP command to that page. Both are control-tier verbs. The MCP forwarders are `workbench_pw` (area, action, flags, steps, target) and `workbench_cdp` (method, params, target). Replies are JSON with `ok`, `op`, `area`, `action`, and the page `url`; failures are `error:` and `fix:`. Each call is logged to `.pickleball/logs/playwright.log` with the endpoint redacted.

State that lives in the Playwright client (routes, tracing, the dialog policy, a new context with emulation, HAR replay) lasts for one call. `pw run --steps=<file or JSON array>` runs several steps on one connection, for example `[{"area":"trace","action":"start"},{"area":"page","action":"goto","url":"..."},{"area":"trace","action":"stop","out":"trace.zip"}]`. Selectors are Playwright selectors (`css=`, `xpath=`, `text=`, `role=button[name="Save"]`, `>> nth=0`). The `xpath` on a snap row works as `--selector=xpath=<xpath>`; refs from `snap` are not accepted directly by `pw`.

| Area | Actions and flags |
|---|---|
| page | `list`, `new [--url=]`, `goto --url=`, `close`, `front`, `reload`, `title`, `eval --expr=`; `--page-url=<part>` picks the page |
| context | `list`, `new [emulation flags] [--url=]`, `close` (not the default context) |
| storage | `save --path=<json>`, `load --path=<json> [--url=]` (a new context with that storage state) |
| route | `fulfill --pattern=<glob> [--body= --code= --content-type=]`, `abort --pattern= [--error=]`, `continue --pattern= [--header=name:value]`, `clear --pattern=`; `--url=` navigates after routing |
| har | `record --path=<har> --url=` (a new context records it), `replay --path=<har> [--url=]` |
| wait | `--for=load\|domcontentloaded\|networkidle\|url\|response\|request\|selector --match=<value> [--wait-ms=] [--click=<selector>\|--url=]` |
| locator | `count`, `click`, `fill --text=`, `text`, `value`, `hover`, `check`, `select --text=` with `--selector=` (auto-waiting Playwright locators) |
| trace | `start`, `stop --out=<zip>` (in one `pw run`) |
| video | `--dir= --url=` |
| screenshot | `--out=<png> [--full-page=true] [--selector=]` |
| download | `--click=<selector> --dir=<dir>` |
| upload | `--selector=<input> --path=<file[,file]>` |
| dialog | `accept [--prompt-text=]`, `dismiss`, with `--click=<selector>` to open it; the reply lists `dialogs` |
| emulate | `--viewport=WxH`, `--device=<Playwright device name>`, `--geolocation=lat,lon`, `--locale=`, `--timezone=`, `--color-scheme=dark\|light\|no-preference`, `--offline`, `--user-agent=`, `--permissions=a,b`, `--url=`; the reply has `observed` values read from the page |
| tabs | `list`, `switch --index=<n>` |
| frames | `list`, `switch --frame-name=<name or URL part> [--selector=]` |
| keyboard | `press --keys=Control+A`, `type --text=`, `down`, `up`, with optional `--selector=` to focus first |
| mouse | `move --x --y`, `down`, `up`, `click --x --y`, `wheel --dx --dy`, `drag --from=<selector> --to=<selector>` or `--x --y --x2 --y2` |
| run | `--steps=<file or JSON array>` |

Limits over `connectOverCDP`: emulation, storage state, and HAR recording apply to a new context that pickleball creates in the attached browser, not to the page you already had open, and that context closes when the call ends. Video recording needs Playwright's ffmpeg build, which pickleball never downloads, so `pw video` returns `error: video recording is not available over connectOverCDP here ...` with a fix pointing to `pw trace` and `pw screenshot`. Device names come from the playwright-core inside the driver-bundle, read with its bundled Node.
