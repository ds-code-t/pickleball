# Desktop drivers (opt-in)

Pickleball's desktop control works with nothing extra installed. It uses PowerShell UI Automation on Windows, `osascript` Accessibility on macOS, and AT-SPI plus `xdotool` on Linux (X11 or Xvfb; see [Configuration](configuration.md#linux-desktop-control)). You can also plug in a WebDriver desktop driver, locally or on a Selenium Grid, and the same `desktop attach --live`, query, click, and type calls go through it. Drivers talk W3C WebDriver over HTTP with the JDK HTTP client, so pickleball adds no dependency for them.

| Driver | OS | Order under `auto` | Version pinned | License | Source |
|--------|----|----------|----------------|---------|--------|
| FlaUI.WebDriver | Windows | 1 | v0.4.0 (`FlaUI.WebDriver.exe`, SHA-256 `14eb97a7…c667`) | MIT | https://github.com/FlaUI/FlaUI.WebDriver |
| NovaWindows (Appium) | Windows | 2 | 1.5.0 | Apache-2.0 | https://github.com/AutomateThePlanet/appium-novawindows-driver |
| WinAppDriver | Windows | 3 | v1.2.1 (`WindowsApplicationDriver_1.2.1.msi`, SHA-256 `a76a8f4e…57fe`) | MIT | https://github.com/microsoft/WinAppDriver |
| Appium Mac2 | macOS | 1 | 5.0.7 | Apache-2.0 | https://github.com/appium/appium-mac2-driver |

Linux has no Grid desktop driver.

## Selecting a driver

- `pkb_desktop_driver` (system property or environment) is `native` (the default, which probes nothing), `auto`, or a driver name: `flaui`, `novawindows`, `winappdriver`, `mac2`.
- `auto` asks `GET <url>/status` of each driver for this OS in the order above and uses the first that answers. With no driver running it falls back to the native backend and logs one `WARN desktop` line.
- A named driver that is not running, or that belongs to another OS, also falls back with one warning.
- Any other value is `error: pkb_desktop_driver must be auto, flaui, novawindows, winappdriver, or mac2`.
- `pkb_desktop_driver_url` points at a running driver or a remote Grid. The default is `http://127.0.0.1:4723`. Start FlaUI.WebDriver with `FlaUI.WebDriver.exe --urls=http://localhost:4723/`.

## How captures and actions work through a driver

A capture opens a session (`appium:app=Root`, or for FlaUI `appium:appTopLevelWindowTitleMatch` for a named window), reads the page source, and turns it into the same canonical desktop tree the native backends produce. Each node keeps its role, name, AutomationId or identifier, RuntimeId, and bounds, plus its driver XPath. Click and type re-find that node by its driver XPath in a fresh session. A capture that fails falls back to the native backend once, with a warning. Queries always run against the canonical tree. Driver XPath is never exposed as a query language.

## Installing

Nothing installs implicitly. `setup desktop-driver --driver=<name>` prints the license, source, pinned version, and checksum, and stops. Add `--confirm` to install:

- **FlaUI and WinAppDriver.** The pinned file is downloaded to a temporary file and its SHA-256 is checked. Only then does it land in `--dir`, which defaults to `~/.pickleball/opt-in/desktop-drivers/<name>/<version>`, next to a `LICENSE-NOTICE.txt`. Install the WinAppDriver `.msi` yourself, and turn on Windows Developer Mode (Settings > For developers).
- **NovaWindows and Mac2.** These run your own `appium driver install ...` command. They need Node and Appium (`npm i -g appium`). Mac2 also needs the Xcode command line tools and Accessibility permission for the terminal that runs Appium (System Settings > Privacy & Security > Accessibility).

## Checking

`doctor --desktop` prints `desktopDriver=<selected>` and a `desktop=` JSON object. It lists each driver for this OS with its version, license, URL, `running`, `missing` (node, appium, xcode, macos-accessibility, windows-developer-mode), and a `fix` line. Plain `doctor` does not probe drivers.
