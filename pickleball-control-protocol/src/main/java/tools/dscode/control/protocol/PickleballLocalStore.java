package tools.dscode.control.protocol;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Materializes version-matched consumer guidance and relocatable Workbench
 * openers under {@code .pickleball}. JDK-only so {@code java -jar pickleball.jar}
 * can run it without Jackson.
 */
public final class PickleballLocalStore {
    public static final String GUIDANCE_ROOT = "META-INF/pickleball/guidance/";
    public static final String GUIDANCE_INDEX = "index.txt";
    private static final String PICKLEBALL_IGNORE_RULE = "/.pickleball/";
    private static final String AGENT_GUIDE_MARKER = "# Pickleball Consumer Agent Guide";
    private static final Pattern MANIFEST_FILE = Pattern.compile("\"((?:\\\\.|[^\"])*)\"");

    private PickleballLocalStore() {
    }

    public static int exportGuidance(Path target, PrintStream out, PrintStream err) throws IOException {
        Path root = target.toAbsolutePath().normalize();
        if (PickleballLocalLayout.isPickleballDirectory(root)) {
            ensureGuidanceIgnored(root, out, err);
            Path project = root.getParent() == null
                    ? Path.of("").toAbsolutePath().normalize()
                    : root.getParent();
            materialize(project, root, runningVersion(), false, out, err);
            return 0;
        }
        exportFlat(root, out, err);
        return 0;
    }

    public static void ensureQuietly(Path projectRoot) {
        try {
            ensurePresent(projectRoot);
        } catch (Exception ignored) {
            // Test runs and UI launch must not fail because guidance export could not complete.
        }
    }

    public static void ensurePresent(Path projectRoot) throws IOException {
        Path project = projectRoot == null
                ? PickleballLocalLayout.findProjectRoot(Path.of(""))
                : projectRoot.toAbsolutePath().normalize();
        Path pickleball = PickleballLocalLayout.root(project);
        String version = runningVersion();
        if (!PickleballLocalLayout.isSafeVersion(version)) return;
        if (alreadyCurrent(pickleball, version)) return;
        materialize(project, pickleball, version, true, new PrintStream(OutputStream.nullOutputStream()), new PrintStream(OutputStream.nullOutputStream()));
    }

    static boolean alreadyCurrent(Path pickleball, String version) {
        var current = PickleballLocalLayout.readCurrent(pickleball);
        if (current.isEmpty() || !current.get().usable()) return false;
        if (!version.equals(current.get().pickleballVersion())) return false;
        Path versionRoot = PickleballLocalLayout.versionRoot(pickleball, version);
        Path open = pickleball.resolve(PickleballLocalLayout.OPEN_DIRECTORY)
                .resolve(PickleballLocalLayout.OPEN_SCRIPT_UNIX);
        return Files.isRegularFile(versionRoot.resolve(PickleballLocalLayout.AGENT_GUIDE))
                && Files.isRegularFile(versionRoot.resolve(PickleballLocalLayout.GUIDANCE_MANIFEST))
                && Files.isRegularFile(pickleball.resolve(PickleballLocalLayout.AGENT_GUIDE))
                && Files.isRegularFile(open);
    }

    private static void materialize(
            Path project,
            Path pickleball,
            String version,
            boolean quiet,
            PrintStream out,
            PrintStream err
    ) throws IOException {
        Files.createDirectories(pickleball);
        Path lockFile = PickleballLocalLayout.materializeLock(pickleball);
        try (FileChannel channel = FileChannel.open(
                lockFile,
                StandardOpenOption.CREATE,
                StandardOpenOption.WRITE
        )) {
            FileLock lock = quiet ? channel.tryLock() : channel.lock();
            if (lock == null) return;
            try {
                writeVersionedTree(pickleball, version, out, err);
            } finally {
                lock.release();
            }
        } catch (java.nio.channels.OverlappingFileLockException ignored) {
            if (!quiet) throw new IOException("Could not lock " + pickleball + " for guidance export.");
        }
        out.println("Pickleball guidance exported to " + pickleball);
        out.println("Pickleball version: " + version);
        out.println("Manifest: " + pickleball.resolve(PickleballLocalLayout.GUIDANCE_MANIFEST));
        out.println("Read " + pickleball.resolve(PickleballLocalLayout.AGENT_GUIDE));
        out.println("NEXT: follow AGENT-GUIDE — run Workbench discover");
    }

    private static void writeVersionedTree(
            Path pickleball,
            String version,
            PrintStream out,
            PrintStream err
    ) throws IOException {
        cleanupLegacyGuidance(pickleball);
        Path versionRoot = PickleballLocalLayout.versionRoot(pickleball, version);
        Files.createDirectories(versionRoot);
        List<String> files = guidanceFiles();
        cleanupPreviousGuidance(versionRoot, files, err);
        copyGuidance(versionRoot, version, files);
        writeGuidanceManifest(versionRoot, version, files);
        writeOpenScripts(pickleball);
        Files.copy(
                versionRoot.resolve(PickleballLocalLayout.AGENT_GUIDE),
                pickleball.resolve(PickleballLocalLayout.AGENT_GUIDE),
                StandardCopyOption.REPLACE_EXISTING
        );
        Files.copy(
                versionRoot.resolve(PickleballLocalLayout.GUIDANCE_MANIFEST),
                pickleball.resolve(PickleballLocalLayout.GUIDANCE_MANIFEST),
                StandardCopyOption.REPLACE_EXISTING
        );
        PickleballLocalLayout.writeCurrent(pickleball, PickleballLocalLayout.CurrentPointer.completeNow(version));
    }

    private static void exportFlat(Path root, PrintStream out, PrintStream err) throws IOException {
        List<String> files = guidanceFiles();
        cleanupPreviousGuidance(root, files, err);
        Files.createDirectories(root);
        String version = runningVersion();
        copyGuidance(root, version, files);
        writeGuidanceManifest(root, version, files);
        out.println("Pickleball guidance exported to " + root);
        out.println("Pickleball version: " + version);
        out.println("Manifest: " + root.resolve(PickleballLocalLayout.GUIDANCE_MANIFEST));
        out.println("Read " + root.resolve(PickleballLocalLayout.AGENT_GUIDE));
        out.println("NEXT: follow AGENT-GUIDE — run Workbench discover");
    }

    private static void copyGuidance(Path root, String version, List<String> files) throws IOException {
        for (String relative : files) {
            Path target = currentGuidanceTarget(root, relative);
            if (target.getParent() != null) Files.createDirectories(target.getParent());
            if (PickleballLocalLayout.AGENT_GUIDE.equals(relative)) {
                writeExportedAgentGuide(target, version);
            } else {
                try (InputStream input = guidanceResource(relative)) {
                    Files.copy(input, target, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }

    private static void writeExportedAgentGuide(Path target, String version) throws IOException {
        String body;
        try (InputStream input = guidanceResource(PickleballLocalLayout.AGENT_GUIDE)) {
            body = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
        String header = """
                <!-- GENERATED BY PICKLEBALL. DO NOT EDIT THIS FILE IN THE CONSUMER PROJECT. -->

                > **Version-matched generated guidance**  
                > Exported from Pickleball `%s` by Workbench `export-guidance`.  
                > Canonical tree: `v/%s/`. Pointer: `current.json`. Relocatable Workbench openers: `open/`.  
                > Before relying on `.pickleball`, rerun the consumer bridge export command so this directory matches the currently resolved Pickleball Maven dependency.  
                > If export fails, treat any existing `.pickleball` contents as potentially stale. See `GUIDANCE-MANIFEST.json` for the completed export's version and managed-file list.

                """.formatted(version, version);
        Files.writeString(target, header + body, StandardCharsets.UTF_8);
    }

    private static void writeGuidanceManifest(Path root, String version, List<String> files) throws IOException {
        String artifact = PickleballVersion.runningArtifactFile(PickleballLocalStore.class);
        StringBuilder json = new StringBuilder();
        json.append("{\n");
        json.append("  \"schemaVersion\": 1,\n");
        json.append("  \"generated\": true,\n");
        json.append("  \"pickleballVersion\": \"").append(escapeJson(version)).append("\",\n");
        if (!artifact.isBlank()) {
            json.append("  \"pickleballArtifact\": \"").append(escapeJson(artifact)).append("\",\n");
        }
        json.append("  \"exportedAt\": \"").append(escapeJson(Instant.now().toString())).append("\",\n");
        json.append("  \"generatedBy\": \"PickleballWorkbenchLauncher export-guidance\",\n");
        json.append("  \"files\": [\n");
        for (int i = 0; i < files.size(); i++) {
            json.append("    \"").append(escapeJson(files.get(i))).append('"');
            json.append(i + 1 < files.size() ? ",\n" : "\n");
        }
        json.append("  ],\n");
        json.append("  \"staleSafety\": \"Rerun export-guidance from the currently resolved Pickleball dependency before using this guidance. If export fails, treat the existing directory as potentially stale.\"\n");
        json.append("}\n");
        Path target = root.resolve(PickleballLocalLayout.GUIDANCE_MANIFEST);
        Path temporary = root.resolve("." + PickleballLocalLayout.GUIDANCE_MANIFEST + ".tmp");
        if (target.getParent() != null) Files.createDirectories(target.getParent());
        Files.writeString(temporary, json.toString(), StandardCharsets.UTF_8);
        try {
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    static void writeOpenScripts(Path pickleball) throws IOException {
        Path open = pickleball.resolve(PickleballLocalLayout.OPEN_DIRECTORY);
        Files.createDirectories(open);
        Path sh = open.resolve(PickleballLocalLayout.OPEN_SCRIPT_UNIX);
        Files.writeString(sh, unixOpenScript(), StandardCharsets.UTF_8);
        try {
            Files.setPosixFilePermissions(sh, PosixFilePermissions.fromString("rwxr-xr-x"));
        } catch (UnsupportedOperationException ignored) {
            // Windows.
        }
        Files.writeString(
                open.resolve(PickleballLocalLayout.OPEN_SCRIPT_CMD),
                cmdOpenScript(),
                StandardCharsets.UTF_8
        );
        Files.writeString(
                open.resolve(PickleballLocalLayout.OPEN_SCRIPT_POWERSHELL),
                powershellOpenScript(),
                StandardCharsets.UTF_8
        );
    }

    static String unixOpenScript() {
        return """
                #!/usr/bin/env sh
                # Relocatable Pickleball Workbench opener. Does not bake in a project,
                # Maven, or version path. Walks up for a consumer project, finds Java,
                # then finds a pickleball-*.jar (pinned current.json, sibling, or Maven local).
                set -eu
                SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
                find_project() {
                  current=$1
                  while [ -n "$current" ]; do
                    if [ -d "$current/.pickleball" ] || [ -f "$current/pom.xml" ] \\
                        || [ -f "$current/build.gradle" ] || [ -f "$current/build.gradle.kts" ]; then
                      printf '%s\\n' "$current"
                      return 0
                    fi
                    parent=$(dirname -- "$current")
                    [ "$parent" = "$current" ] && break
                    current=$parent
                  done
                  return 1
                }
                PROJECT=$(find_project "$SCRIPT_DIR" || true)
                if [ -z "${PROJECT:-}" ]; then
                  PROJECT=$(find_project "$(pwd)" || true)
                fi
                if [ -z "${PROJECT:-}" ]; then
                  echo "Pickleball Workbench: could not find a consumer project (pom.xml, build.gradle, or .pickleball)." >&2
                  exit 1
                fi
                if [ -n "${JAVA_HOME:-}" ] && [ -x "$JAVA_HOME/bin/java" ]; then
                  JAVA="$JAVA_HOME/bin/java"
                elif [ -n "${JAVA_HOME:-}" ] && [ -x "$JAVA_HOME/bin/java.exe" ]; then
                  JAVA="$JAVA_HOME/bin/java.exe"
                else
                  JAVA=java
                fi
                CURRENT="$PROJECT/.pickleball/current.json"
                VERSION=""
                if [ -f "$CURRENT" ]; then
                  VERSION=$(sed -n 's/.*"pickleballVersion"[[:space:]]*:[[:space:]]*"\\([^"]*\\)".*/\\1/p' "$CURRENT" | head -n 1)
                fi
                M2="${HOME}/.m2/repository"
                if [ -f "${HOME}/.m2/settings.xml" ]; then
                  CONFIGURED=$(sed -n 's/.*<localRepository>\\([^<]*\\)<\\/localRepository>.*/\\1/p' "${HOME}/.m2/settings.xml" | head -n 1)
                  if [ -n "${CONFIGURED:-}" ]; then
                    CONFIGURED=$(printf '%s\\n' "$CONFIGURED" | sed "s#\\${user.home}#$HOME#; s#\\${USER_HOME}#$HOME#")
                    [ -n "$CONFIGURED" ] && M2="$CONFIGURED"
                  fi
                fi
                JAR=""
                if [ -n "$VERSION" ]; then
                  CANDIDATE="$M2/tools/dscode/pickleball/$VERSION/pickleball-$VERSION.jar"
                  [ -f "$CANDIDATE" ] && JAR="$CANDIDATE"
                  if [ -z "$JAR" ]; then
                    GRADLE="$HOME/.gradle/caches/modules-2/files-2.1/tools.dscode/pickleball/$VERSION"
                    if [ -d "$GRADLE" ]; then
                      JAR=$(find "$GRADLE" -name "pickleball-$VERSION.jar" 2>/dev/null | head -n 1 || true)
                    fi
                  fi
                fi
                if [ -z "$JAR" ]; then
                  for dir in "$SCRIPT_DIR" "$PROJECT" "$(pwd)"; do
                    for f in "$dir"/pickleball-*.jar; do
                      [ -f "$f" ] || continue
                      case $f in
                        *workbench*) continue ;;
                      esac
                      JAR=$f
                      break
                    done
                    [ -n "$JAR" ] && break
                  done
                fi
                if [ -z "$JAR" ]; then
                  ART="$M2/tools/dscode/pickleball"
                  if [ -d "$ART" ]; then
                    for dir in "$ART"/*/; do
                      ver=$(basename -- "$dir")
                      case $ver in
                        *SNAPSHOT*) continue ;;
                      esac
                      cand="$dir/pickleball-$ver.jar"
                      [ -f "$cand" ] && JAR=$cand
                    done
                    if [ -z "$JAR" ]; then
                      for dir in "$ART"/*/; do
                        ver=$(basename -- "$dir")
                        cand="$dir/pickleball-$ver.jar"
                        [ -f "$cand" ] && JAR=$cand
                      done
                    fi
                  fi
                fi
                if [ -z "$JAR" ] || [ ! -f "$JAR" ]; then
                  echo "Pickleball Workbench: no pickleball-*.jar found in Maven local, Gradle cache, or beside this script." >&2
                  echo "Install tools.dscode:pickleball or run: java -jar pickleball-<version>.jar ui \\"$PROJECT\\"" >&2
                  exit 1
                fi
                export PKB_OPEN_BOOTSTRAP=1
                exec "$JAVA" -jar "$JAR" ui "$PROJECT"
                """;
    }

    static String cmdOpenScript() {
        return """
                @echo off
                setlocal EnableExtensions EnableDelayedExpansion
                set "SCRIPT_DIR=%~dp0"
                set "PROJECT="
                set "WALK=%SCRIPT_DIR%"
                :walk
                if exist "%WALK%.pickleball\\" set "PROJECT=%WALK%" & goto found
                if exist "%WALK%pom.xml" set "PROJECT=%WALK%" & goto found
                if exist "%WALK%build.gradle" set "PROJECT=%WALK%" & goto found
                if exist "%WALK%build.gradle.kts" set "PROJECT=%WALK%" & goto found
                for %%I in ("%WALK%..") do set "PARENT=%%~fI\\"
                if /I "%PARENT%"=="%WALK%" goto cwd
                set "WALK=%PARENT%"
                goto walk
                :cwd
                set "WALK=%CD%\\"
                if exist "%WALK%.pickleball\\" set "PROJECT=%WALK%" & goto found
                if exist "%WALK%pom.xml" set "PROJECT=%WALK%" & goto found
                if exist "%WALK%build.gradle" set "PROJECT=%WALK%" & goto found
                if exist "%WALK%build.gradle.kts" set "PROJECT=%WALK%" & goto found
                echo Pickleball Workbench: could not find a consumer project. 1>&2
                exit /b 1
                :found
                if "!PROJECT:~-1!"=="\\" set "PROJECT=!PROJECT:~0,-1!"
                if defined JAVA_HOME (
                  if exist "%JAVA_HOME%\\bin\\java.exe" (set "JAVA=%JAVA_HOME%\\bin\\java.exe") else set "JAVA=java"
                ) else set "JAVA=java"
                set "M2=%USERPROFILE%\\.m2\\repository"
                set "JAR="
                set "VERSION="
                if exist "%PROJECT%\\.pickleball\\current.json" (
                  for /f "usebackq tokens=2 delims=:" %%A in (`findstr pickleballVersion "%PROJECT%\\.pickleball\\current.json"`) do (
                    set "VERSION=%%~A"
                  )
                )
                if defined VERSION (
                  set "VERSION=!VERSION:"=!"
                  set "VERSION=!VERSION:,=!"
                  set "VERSION=!VERSION: =!"
                  set "CANDIDATE=%M2%\\tools\\dscode\\pickleball\\!VERSION!\\pickleball-!VERSION!.jar"
                  if exist "!CANDIDATE!" set "JAR=!CANDIDATE!"
                  if not defined JAR (
                    set "GRADLE=%USERPROFILE%\\.gradle\\caches\\modules-2\\files-2.1\\tools.dscode\\pickleball\\!VERSION!"
                    if exist "!GRADLE!" (
                      for /r "!GRADLE!" %%F in (pickleball-!VERSION!.jar) do if exist "%%~F" set "JAR=%%~F"
                    )
                  )
                )
                if not defined JAR (
                  for %%F in ("%SCRIPT_DIR%pickleball-*.jar") do (
                    echo %%~nxF| findstr /i workbench >nul
                    if errorlevel 1 if exist "%%~F" set "JAR=%%~F"
                  )
                )
                if not defined JAR (
                  for %%F in ("%PROJECT%\\pickleball-*.jar") do (
                    echo %%~nxF| findstr /i workbench >nul
                    if errorlevel 1 if exist "%%~F" set "JAR=%%~F"
                  )
                )
                if not defined JAR (
                  for %%F in ("%CD%\\pickleball-*.jar") do (
                    echo %%~nxF| findstr /i workbench >nul
                    if errorlevel 1 if exist "%%~F" set "JAR=%%~F"
                  )
                )
                if not defined JAR if exist "%M2%\\tools\\dscode\\pickleball\\" (
                  for /d %%D in ("%M2%\\tools\\dscode\\pickleball\\*") do (
                    echo %%~nxD| findstr /i SNAPSHOT >nul
                    if errorlevel 1 if exist "%%D\\pickleball-%%~nxD.jar" set "JAR=%%D\\pickleball-%%~nxD.jar"
                  )
                  if not defined JAR (
                    for /d %%D in ("%M2%\\tools\\dscode\\pickleball\\*") do (
                      if exist "%%D\\pickleball-%%~nxD.jar" set "JAR=%%D\\pickleball-%%~nxD.jar"
                    )
                  )
                )
                if not defined JAR (
                  echo Pickleball Workbench: no pickleball-*.jar found. 1>&2
                  exit /b 1
                )
                set "PKB_OPEN_BOOTSTRAP=1"
                "%JAVA%" -jar "%JAR%" ui "%PROJECT%"
                """;
    }

    static String powershellOpenScript() {
        return """
                $ErrorActionPreference = 'Stop'
                function Find-Project([string]$start) {
                  $current = (Resolve-Path -LiteralPath $start).Path
                  while ($current) {
                    if ((Test-Path (Join-Path $current '.pickleball')) -or
                        (Test-Path (Join-Path $current 'pom.xml')) -or
                        (Test-Path (Join-Path $current 'build.gradle')) -or
                        (Test-Path (Join-Path $current 'build.gradle.kts'))) {
                      return $current
                    }
                    $parent = Split-Path -Parent $current
                    if ($parent -eq $current) { break }
                    $current = $parent
                  }
                  return $null
                }
                $scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
                $project = Find-Project $scriptDir
                if (-not $project) { $project = Find-Project (Get-Location) }
                if (-not $project) { throw 'Pickleball Workbench: could not find a consumer project.' }
                $java = if ($env:JAVA_HOME -and (Test-Path (Join-Path $env:JAVA_HOME 'bin/java.exe'))) {
                  Join-Path $env:JAVA_HOME 'bin/java.exe'
                } elseif ($env:JAVA_HOME -and (Test-Path (Join-Path $env:JAVA_HOME 'bin/java'))) {
                  Join-Path $env:JAVA_HOME 'bin/java'
                } else { 'java' }
                $m2 = Join-Path $HOME '.m2/repository'
                $jar = $null
                $currentFile = Join-Path $project '.pickleball/current.json'
                if (Test-Path $currentFile) {
                  $text = Get-Content -Raw $currentFile
                  if ($text -match '"pickleballVersion"\\s*:\\s*"([^"]+)"') {
                    $version = $Matches[1]
                    $candidate = Join-Path $m2 "tools/dscode/pickleball/$version/pickleball-$version.jar"
                    if (Test-Path $candidate) { $jar = $candidate }
                    if (-not $jar) {
                      $gradle = Join-Path $HOME ".gradle/caches/modules-2/files-2.1/tools.dscode/pickleball/$version"
                      if (Test-Path $gradle) {
                        $hit = Get-ChildItem -Path $gradle -Recurse -Filter ("pickleball-" + $version + '.jar') -ErrorAction SilentlyContinue |
                          Select-Object -First 1
                        if ($hit) { $jar = $hit.FullName }
                      }
                    }
                  }
                }
                if (-not $jar) {
                  foreach ($dir in @($scriptDir, $project, (Get-Location).Path)) {
                    $hit = Get-ChildItem -Path $dir -Filter 'pickleball-*.jar' -ErrorAction SilentlyContinue |
                      Where-Object { $_.Name -notmatch 'workbench' } |
                      Select-Object -First 1
                    if ($hit) { $jar = $hit.FullName; break }
                  }
                }
                if (-not $jar) {
                  $art = Join-Path $m2 'tools/dscode/pickleball'
                  if (Test-Path $art) {
                    $dirs = Get-ChildItem $art -Directory | Sort-Object Name
                    foreach ($dir in $dirs) {
                      if ($dir.Name -match 'SNAPSHOT') { continue }
                      $candidate = Join-Path $dir.FullName ("pickleball-" + $dir.Name + '.jar')
                      if (Test-Path $candidate) { $jar = $candidate }
                    }
                    if (-not $jar) {
                      foreach ($dir in $dirs) {
                        $candidate = Join-Path $dir.FullName ("pickleball-" + $dir.Name + '.jar')
                        if (Test-Path $candidate) { $jar = $candidate }
                      }
                    }
                  }
                }
                if (-not $jar -or -not (Test-Path $jar)) {
                  throw 'Pickleball Workbench: no pickleball-*.jar found in Maven local or beside this script.'
                }
                $env:PKB_OPEN_BOOTSTRAP = '1'
                & $java -jar $jar ui $project
                """;
    }

    static void ensureGuidanceIgnored(Path root, PrintStream out, PrintStream err) {
        Path consumerRoot = root.getParent();
        if (consumerRoot == null) return;

        List<String> failures = new ArrayList<>();
        Path consumerIgnore = consumerRoot.resolve(".gitignore");
        if (Files.isRegularFile(consumerIgnore)) {
            try {
                boolean added = ensureIgnoreRule(consumerIgnore, PICKLEBALL_IGNORE_RULE, ".pickleball", false);
                if (added) out.println("Added " + PICKLEBALL_IGNORE_RULE + " to " + consumerIgnore);
                return;
            } catch (IOException e) {
                failures.add(consumerIgnore + ": " + e.getMessage());
            }
        }

        GitLayout git = findGitLayout(consumerRoot);
        if (git == null) {
            if (!failures.isEmpty()) {
                err.println("WARNING: Could not add .pickleball to Git ignore rules; guidance export will continue. "
                        + String.join("; ", failures));
            }
            return;
        }

        Path repositoryIgnore = git.repositoryRoot.resolve(".gitignore");
        if (!repositoryIgnore.equals(consumerIgnore) && Files.isRegularFile(repositoryIgnore)) {
            String relative = git.repositoryRoot.relativize(root).toString().replace('\\', '/');
            String rule = "/" + relative + "/";
            try {
                boolean added = ensureIgnoreRule(repositoryIgnore, rule, relative, false);
                if (added) out.println("Added " + rule + " to " + repositoryIgnore);
                return;
            } catch (IOException e) {
                failures.add(repositoryIgnore + ": " + e.getMessage());
            }
        }

        Path exclude = git.excludeFile();
        String relative = git.repositoryRoot.relativize(root).toString().replace('\\', '/');
        String localRule = "/" + relative + "/";
        try {
            boolean added = ensureIgnoreRule(exclude, localRule, relative, true);
            if (added) out.println("Added " + localRule + " to local Git exclude " + exclude);
            return;
        } catch (IOException e) {
            failures.add(exclude + ": " + e.getMessage());
        }

        err.println("WARNING: Could not add .pickleball to Git ignore rules; guidance export will continue. "
                + String.join("; ", failures));
    }

    private static boolean ensureIgnoreRule(Path file, String rule, String normalizedTarget, boolean create) throws IOException {
        if (!create && !Files.isRegularFile(file)) return false;
        String existing = Files.isRegularFile(file) ? Files.readString(file, StandardCharsets.UTF_8) : "";
        if (isEffectivelyIgnored(existing, normalizedTarget)) return false;

        if (create && file.getParent() != null) Files.createDirectories(file.getParent());
        String prefix = existing.isEmpty() || existing.endsWith("\n") || existing.endsWith("\r")
                ? ""
                : System.lineSeparator();
        Files.writeString(
                file,
                prefix + rule + System.lineSeparator(),
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE,
                StandardOpenOption.APPEND
        );
        return true;
    }

    private static boolean isEffectivelyIgnored(String content, String normalizedTarget) {
        boolean ignored = false;
        String target = normalizeIgnorePattern(normalizedTarget);
        for (String raw : content.split("\\R")) {
            String line = raw.trim();
            if (line.isEmpty() || line.startsWith("#")) continue;
            boolean negated = line.startsWith("!");
            if (negated) line = line.substring(1).trim();
            if (normalizeIgnorePattern(line).equals(target)) {
                ignored = !negated;
            }
        }
        return ignored;
    }

    private static String normalizeIgnorePattern(String value) {
        String normalized = value == null ? "" : value.trim().replace('\\', '/');
        while (normalized.startsWith("./")) normalized = normalized.substring(2);
        while (normalized.startsWith("/")) normalized = normalized.substring(1);
        while (normalized.endsWith("/")) normalized = normalized.substring(0, normalized.length() - 1);
        return normalized;
    }

    private static GitLayout findGitLayout(Path start) {
        for (Path current = start; current != null; current = current.getParent()) {
            Path marker = current.resolve(".git");
            try {
                if (Files.isDirectory(marker)) {
                    return new GitLayout(current, marker, resolveCommonGitDirectory(marker));
                }
                if (Files.isRegularFile(marker)) {
                    String line = Files.readString(marker, StandardCharsets.UTF_8).trim();
                    if (!line.toLowerCase(Locale.ROOT).startsWith("gitdir:")) continue;
                    String value = line.substring("gitdir:".length()).trim();
                    Path gitDir = Path.of(value);
                    if (!gitDir.isAbsolute()) gitDir = current.resolve(gitDir);
                    gitDir = gitDir.toAbsolutePath().normalize();
                    return new GitLayout(current, gitDir, resolveCommonGitDirectory(gitDir));
                }
            } catch (Exception ignored) {
                // Git ignore handling is best effort and must never block guidance export.
            }
        }
        return null;
    }

    private static Path resolveCommonGitDirectory(Path gitDir) {
        Path common = gitDir.resolve("commondir");
        if (!Files.isRegularFile(common)) return gitDir;
        try {
            String value = Files.readString(common, StandardCharsets.UTF_8).trim();
            if (value.isEmpty()) return gitDir;
            Path resolved = Path.of(value);
            if (!resolved.isAbsolute()) resolved = gitDir.resolve(resolved);
            return resolved.toAbsolutePath().normalize();
        } catch (IOException ignored) {
            return gitDir;
        }
    }

    private static void cleanupPreviousGuidance(Path root, List<String> currentFiles, PrintStream err) throws IOException {
        if (!Files.isDirectory(root)) return;

        Path manifest = root.resolve(PickleballLocalLayout.GUIDANCE_MANIFEST);
        if (Files.isRegularFile(manifest)) {
            List<String> previous;
            try {
                previous = manifestFiles(Files.readString(manifest, StandardCharsets.UTF_8));
            } catch (Exception e) {
                err.println("WARNING: Could not read previous " + PickleballLocalLayout.GUIDANCE_MANIFEST
                        + "; rebuilding the recognized generated guidance tree. " + e.getMessage());
                cleanupLegacyGuidance(root);
                return;
            }
            Set<String> keep = new LinkedHashSet<>(currentFiles);
            for (String relative : previous) {
                if (relative == null || relative.isBlank() || keep.contains(relative)) continue;
                Path target = previousGuidanceTarget(root, relative, err);
                if (target == null) continue;
                if (InvestigationHandoff.isInvestigationsPath(root, target)) continue;
                Files.deleteIfExists(target);
            }
            removeEmptyDirectories(root);
            return;
        }

        cleanupLegacyGuidance(root);
    }

    private static void cleanupLegacyGuidance(Path root) throws IOException {
        Path guide = root.resolve(PickleballLocalLayout.AGENT_GUIDE);
        if (!Files.isRegularFile(guide)) return;
        String text = Files.readString(guide, StandardCharsets.UTF_8);
        if (!text.contains(AGENT_GUIDE_MARKER)) return;
        deleteTree(root.resolve("docs"));
        if (!PickleballLocalLayout.isPickleballDirectory(root)) {
            Files.deleteIfExists(guide);
        }
    }

    private static Path previousGuidanceTarget(Path root, String relative, PrintStream err) {
        try {
            Path target = root.resolve(relative).normalize();
            if (!target.startsWith(root) || target.equals(root)) {
                err.println("WARNING: Ignoring unsafe path in previous guidance manifest: " + relative);
                return null;
            }
            return target;
        } catch (Exception e) {
            err.println("WARNING: Ignoring invalid path in previous guidance manifest: " + relative);
            return null;
        }
    }

    private static Path currentGuidanceTarget(Path root, String relative) throws IOException {
        Path target = root.resolve(relative).normalize();
        if (!target.startsWith(root) || target.equals(root)) {
            throw new IOException("Invalid bundled guidance path: " + relative);
        }
        return target;
    }

    private static void removeEmptyDirectories(Path root) throws IOException {
        List<Path> directories;
        try (var paths = Files.walk(root)) {
            directories = paths
                    .filter(Files::isDirectory)
                    .filter(path -> !path.equals(root))
                    .filter(path -> !InvestigationHandoff.isInvestigationsPath(root, path))
                    .sorted(Comparator.reverseOrder())
                    .toList();
        }
        for (Path path : directories) {
            try (var children = Files.list(path)) {
                if (children.findAny().isEmpty()) Files.deleteIfExists(path);
            }
        }
    }

    private static void deleteTree(Path root) throws IOException {
        if (!Files.exists(root)) return;
        List<Path> entries;
        try (var paths = Files.walk(root)) {
            entries = paths.sorted(Comparator.reverseOrder()).toList();
        }
        for (Path path : entries) Files.deleteIfExists(path);
    }

    static List<String> manifestFiles(String json) {
        if (json == null || json.isBlank()) return List.of();
        int key = json.indexOf("\"files\"");
        if (key < 0) return List.of();
        int open = json.indexOf('[', key);
        int close = json.indexOf(']', open);
        if (open < 0 || close < 0) return List.of();
        Matcher matcher = MANIFEST_FILE.matcher(json.substring(open + 1, close));
        List<String> files = new ArrayList<>();
        while (matcher.find()) {
            files.add(matcher.group(1).replace("\\\"", "\"").replace("\\\\", "\\"));
        }
        return List.copyOf(files);
    }

    static List<String> guidanceFiles() throws IOException {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                guidanceResource(GUIDANCE_INDEX),
                StandardCharsets.UTF_8
        ))) {
            return reader.lines()
                    .map(String::trim)
                    .filter(line -> !line.isEmpty())
                    .toList();
        }
    }

    static InputStream guidanceResource(String relative) throws IOException {
        InputStream input = PickleballLocalStore.class.getClassLoader()
                .getResourceAsStream(GUIDANCE_ROOT + relative);
        if (input == null) {
            throw new IOException("Bundled Pickleball guidance is missing: " + relative);
        }
        return input;
    }

    static String runningVersion() {
        return PickleballVersion.running(PickleballLocalStore.class);
    }

    private static String escapeJson(String value) {
        if (value == null) return "";
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private record GitLayout(Path repositoryRoot, Path gitDirectory, Path commonGitDirectory) {
        Path excludeFile() {
            return commonGitDirectory.resolve("info").resolve("exclude");
        }
    }
}
