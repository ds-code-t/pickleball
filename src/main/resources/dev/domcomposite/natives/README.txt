Optional Rust override: dom-composite 2.4.0 native layout.

Place the built `composite` binary (or `composite.exe` on Windows) at:

  dev/domcomposite/natives/<classifier>/composite

Classifiers: linux-x64, linux-aarch64, windows-x64, macos-aarch64, macos-x64.

The same bytes may ship as a Maven classifier jar
dev.domcomposite:composite-native:2.4.0:<classifier>
with the binary at the jar root. Nothing is published until Dan says so.
Pickleball extracts the matching classifier into the temp directory on first use.
Explicit pkb_composite_bin (property, then pkb_runvars.pkb_composite_bin, then
env COMPOSITE_BIN, then env pkb_composite_bin) is first. Unless
pkb_composite_engine=rust, the in-repo Java engine (dev.domengine, shipped
inside the Pickleball jar) is next. This native layout is reached only after
that, and after COMPOSITE_HOME or a sibling dom-composite checkout
(target/debug/composite, then target/release/composite). pkb_composite=on
also accepts composite on PATH after the classifier jar.
