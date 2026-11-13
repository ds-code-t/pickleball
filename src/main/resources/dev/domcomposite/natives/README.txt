dom-composite 2.4.0 native layout.

Place the built `composite` binary (or `composite.exe` on Windows) at:

  dev/domcomposite/natives/<classifier>/composite

Classifiers: linux-x64, linux-aarch64, windows-x64, macos-aarch64, macos-x64.

The same bytes may ship as a Maven classifier jar
dev.domcomposite:composite-native:2.4.0:<classifier>
with the binary at the jar root. Nothing is published until Dan says so.
Pickleball extracts the matching classifier into the temp directory on first use.
pkb_composite_bin and COMPOSITE_BIN are checked first. In 3.0.0 the in-repo Java
engine (dev.domengine, shipped inside the Pickleball jar) is used next, so this
native layout is only reached when pkb_composite_engine=rust or the Java engine
is not on the classpath. COMPOSITE_HOME and a sibling dom-composite checkout are
checked before the classifier natives.
