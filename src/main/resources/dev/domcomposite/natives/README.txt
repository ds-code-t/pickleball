dom-composite 2.4.0 native layout.

Place the built `composite` binary (or `composite.exe` on Windows) at:

  dev/domcomposite/natives/<classifier>/composite

Classifiers: linux-x64, linux-aarch64, windows-x64, macos-aarch64, macos-x64.

The same bytes may ship as a Maven classifier jar
dev.domcomposite:composite-native:2.4.0:<classifier>
with the binary at the jar root. Nothing is published until Dan says so.
Pickleball extracts the matching classifier into the temp directory on first use.
COMPOSITE_BIN, COMPOSITE_HOME, pkb_composite_bin, and a sibling dom-composite
checkout are checked first.
