# Optional installs

Pickleball can use three open-source tools that it never bundles: axe-core (accessibility rules), Lighthouse (page reports) and chrome-devtools-mcp (Chrome DevTools as an MCP server). Each is installed only when you run `optional-install <name> --confirm`. Nothing downloads at build, test, CI or default runtime, and the jar does not change.

| Name | npm package | Pinned version | License | Used by |
|------|-------------|----------------|---------|---------|
| `axe` | `axe-core` | 4.14.1 | MPL-2.0 (weak copyleft) | `audit --axe` |
| `lighthouse` | `lighthouse` | 13.6.0 | Apache-2.0 | `audit --lighthouse` |
| `devtools-mcp` | `chrome-devtools-mcp` | 1.13.0 | Apache-2.0 | `bridge --devtools-mcp` |

## Install, list, remove

```
optional-install axe                 # prints the plan: registry URL, tarball, SHA-512, size, license, target dir; downloads nothing
optional-install axe --confirm       # downloads, verifies, unpacks, records the lock
optional-install axe --version=4.15.0 --integrity=sha512-... --confirm   # another version needs its registry integrity
optional-list
optional-uninstall axe
```

- Source: `https://registry.npmjs.org/` only. The registry's `dist.integrity` must equal the pinned (or passed) SHA-512, and the tarball is hashed again before it is unpacked. A mismatch installs nothing.
- Location: `pkb_optional_dir`, else the user cache dir (`%LOCALAPPDATA%\pickleball\optional` on Windows, `$XDG_CACHE_HOME/pickleball/optional`, else `~/.cache/pickleball/optional`). Never the repository. `optional.lock.json` there records package, version, integrity, tarball and install time.
- Node: the Node inside Playwright's driver bundle (`PLAYWRIGHT_NODEJS_PATH` overrides). No user Node install is needed.
- Lighthouse has npm dependencies, so it is installed with npm (`--ignore-scripts --omit=dev`) and the lock's integrity for `lighthouse` is checked. Playwright's bundle has no npm; set `pkb_optional_npm=<npm or npm-cli.js>`. axe-core and chrome-devtools-mcp have no dependencies and are unpacked from their tarballs directly.
- `doctor` prints `optional=axe:<version|not-installed> ...` and that installing is an external download.

## Using them

- `audit --axe [--scope=<css>|--ref=<ref>] [--rules=<id,id>] [--out=<file.json>]` injects the installed `axe.min.js`, unmodified, into an isolated world (CDP `Page.createIsolatedWorld`; Playwright evaluates in the page when CDP is not available) and runs `axe.run`. Violations become `kind=audit subkind=a11y` rows (`method` = rule id, `level` error for critical/serious, `count` = nodes) and the full result is written to the JSON file.
- `audit --lighthouse [--categories=performance,accessibility,best-practices,seo] [--url=] [--out=<dir>]` runs the installed Lighthouse against this session's CDP port (Chromium only, localhost only) with Lighthouse's default throttling (simulated, mobile form factor) and writes `lighthouse.report.json` and `.html`.
- `bridge --devtools-mcp [--endpoint=]` prints the MCP client config that starts the installed chrome-devtools-mcp with the bundled Node against this session's endpoint, always with `--no-performance-crux` and `--no-usage-statistics` (and `CHROME_DEVTOOLS_MCP_NO_USAGE_STATISTICS=1`), flags as documented in its README and configuration guide. Localhost only. The MCP client owns the process.

Not installed: each verb fails with `error: <name> is not installed` and `fix: pickleball optional-install <name>`.

## Licenses

axe-core is MPL-2.0: it is used as an unmodified, separate file in the user's cache, never shaded or distributed with pickleball; its source is at https://github.com/dequelabs/axe-core. Lighthouse (https://github.com/GoogleChrome/lighthouse) and chrome-devtools-mcp (https://github.com/ChromeDevTools/chrome-devtools-mcp) are Apache-2.0. See THIRD-PARTY-NOTICES.md.
