# k2j-gradle — implementation contract

A thin Gradle plugin exposing a `k2j` task. It wires no compilation: the Kotlin plugin has already
run by the time the task executes, and the task consumes the build's own outputs.

## Files you own

- `build.gradle.kts`
- `src/main/kotlin/com/example/k2j/gradle/K2jPlugin.kt`
- `src/main/kotlin/com/example/k2j/gradle/K2jTask.kt`
- `src/main/kotlin/com/example/k2j/gradle/K2jExtension.kt`
- `src/main/kotlin/com/example/k2j/gradle/ClassRoots.kt` (classes-root selection)
- `src/main/kotlin/com/example/k2j/gradle/KotlinClassDetector.kt` (constant-pool Metadata sniff)
- `src/main/kotlin/com/example/k2j/gradle/DecompilerArtifact.kt` (vendored jar + pin + verification)
- `src/main/kotlin/com/example/k2j/gradle/JavaRuntime.kt` (ordered Java 25 probe)
- `src/main/resources/k2j/java-decompiler.jar` (the vendored artifact)
- `src/test/kotlin/...`, `src/test/java/...`

Do NOT edit: anything under `core/` (read its frozen interfaces from
`core/src/main/kotlin/com/example/k2j/core/*.kt`), `corpus/`, or the root build files.

## Skeleton

```kotlin
plugins {
    kotlin("jvm")
    `java-gradle-plugin`
}

// k2j-core is consumed as an included-project dependency:
dependencies {
    implementation(project(":core"))
}
```

## Requirements

### Extension `k2j { }`

- `packages` (ListProperty&lt;String&gt;, default empty)
- `outputRoot` (DirectoryProperty, default `layout.buildDirectory.dir("k2j")`)
- `deleteSources` (Property&lt;Boolean&gt;, default `false`)
- `decompilerJar` (RegularFileProperty — **override only**). Unset means "use the artifact vendored
  inside this plugin". It is *not* a path into a Gradle cache or an IntelliJ installation.
- `decompilerJarSha256` (Property&lt;String&gt; — optional). When set, the resolved jar is verified
  against it.
- `runtimeJavaHome` (DirectoryProperty — **override only**). Unset means "probe" (see below).
- `surveyor` / `decompiler` / `validator` / `writer` (Property, optional collaborator overrides).

> Corrected: this document previously specified `decompilerJar (RegularFileProperty, default = the
> vendored IntelliJ path from AGENTS.md)`. AGENTS.md contains no decompiler-jar path, and a default
> pointing at one developer's Gradle transform cache is not a contract anyone else can satisfy.
> The jar is now a real artifact of this plugin, pinned by SHA-256 (see *Decompiler pinning*).

### Task `k2j`

- For each Kotlin JVM source set's classes task: wire to the `KotlinJvmPlugin`'s `classes` target of
  the main source set, so `k2j` depends on `classes`.
- **Kotlin plugin absent**: the task must fail with a `k2j:`-prefixed, actionable message naming
  `org.jetbrains.kotlin.jvm`. The plugin cannot fail at apply time (plugin order is not guaranteed),
  so `K2jPlugin` records plugin presence via `plugins.withId(...)` in the `kotlinPluginApplied`
  input and `K2jTask` refuses to run without it.

  > Corrected: this document previously said "if the project has no Kotlin plugin applied, fail with
  > a clear message" without saying *when*, and the implementation failed on the first classes dir
  > instead — with Gradle's internal `NoSuchElementException` and no `k2j` prefix.

- **Classes root**: never take the first existing directory of `main.output.classesDirs`. Gradle
  orders that collection as `[build/classes/java/main, build/classes/kotlin/main]`, so a mixed
  Java+Kotlin module's Java output is examined first and the run reports `converted 0 class(es)`
  with `"success": true` while the Kotlin classes sit next to it. `ClassRoots` walks the candidates
  and selects one that actually contains a `.class` carrying `kotlin/Metadata`; when several do, the
  metadata-bearing roots are staged into one merged directory (the frozen `ConversionRequest` takes a
  single `classesRoot`); when none does, or when no candidate directory exists, the task fails with a
  `k2j:`-prefixed message.
- **Compile gate classpath**: the gate resolves against *every* classes directory of the main source
  set, not just the selected root (`ClassRoots.compileClasspathRoots` →
  `ConversionRequest.classesRoots`). The survey needs one root; resolution does not. A module's own
  Java classes (annotations, utilities) are compiled into `build/classes/java/main` while every Kotlin
  class lands in `build/classes/kotlin/main`, so a unit referencing one of them used to be rejected
  with `cannot find symbol` although the `.class` file was on disk. A single-classes-dir project is
  unaffected: the list de-duplicates against the survey root.
- **Repeatable**: Gradle does not wipe a declared `@OutputDirectory` between executions and core's
  writer refuses to overwrite (`FileSystemWriter.kt:19`), so the task deletes its own output at the
  start of the action (via injected `FileSystemOperations`). Stale `.java` files for classes that
  disappeared must not survive. *(Deletion happens after input validation, not literally before it,
  so a misconfigured run never destroys the previous good output; the guarantee is what matters.)*
- **Inputs**: classes dirs of the main source set, compile classpath, the extension values, the
  decompiler jar (when overridden), its expected SHA-256, and the Java-25 runtime's launcher +
  version. `sourceRoots` is scoped to the *Kotlin* source dirs (`main.allJava.srcDirs` minus
  `main.java.srcDirs`) — `allJava` alone also contains `src/main/java` and would make any Java edit
  invalidate `k2j` for no benefit.
- **Action**: build a `ConversionRequest` and call `K2j(...)` with a `RunLog` that forwards to the
  Gradle logger. After the run, if `!manifest.success`, fail the task with a summary of the failures
  (class + phase + message each) — do NOT swallow them.
- **Manifest before failure**: the manifest must exist on disk before the task fails, at the exact
  path the failure message names. Core writes it on the normal path but returns *before* writing it
  when decompilation fails for the whole target set (`K2j.kt:48-58`), so the task writes it itself
  through the same `OutputWriter` when it is missing, then throws. The path in the message is built
  with `File.resolve` so separators never mix (`build\k2j\k2j-manifest.json`).
- `@TaskAction` must not run on the configuration cache-hostile path: use `providers`/`files`
  properly.
- Register the task in `K2jPlugin` applied via the `gradle-plugin` block with id `org.example.k2j`.
- The plugin must NOT add any dependency on the user's project beyond what `classes` provides.

## Decompiler pinning

The plugin vendors `java-decompiler.jar` from IntelliJ IDEA 2026.2.1 (class-version 69 → requires a
Java 25 runtime) as the classpath resource `k2j/java-decompiler.jar`.

**Canonical pin** (authoritative, and the value a core/ CLI default should adopt):

```
resource : k2j/java-decompiler.jar   (IntelliJ IDEA 2026.2.1)
sha256   : c93a37aeac40b9017838e005fce21230c82ddbb7fbb83b07da9d3f52a10fee94
```

`DecompilerArtifact.resolveBundled` resolves that resource to a real file (directly when the plugin
is loaded from a directory, extracted into the Gradle user home cache when it is loaded from a jar)
and re-verifies the checksum at execution time; a mismatch fails with the expected digest, the actual
digest and this pin. `DecompilerArtifactTest` additionally guards, at source level, that no main
source contains a Gradle transform-cache path or a user-specific absolute path.

### Java 25 runtime

`JavaRuntime.probe` is an explicit, ordered probe:

1. `k2j { runtimeJavaHome = ... }` (always wins when it is a usable JDK);
2. IDE JBRs discovered under `%LOCALAPPDATA%` (`Programs/IntelliJ IDEA*/jbr`, then JetBrains
   Toolbox `apps/*/ch-*/*/jbr`, newest build first);
3. `JAVA_HOME`.

Candidates whose `release` file reports a version &lt; 25 are de-prioritised, and the task rejects a
resolved runtime below 25 with a message naming the requirement. The launcher (`bin/java[.exe]`) and
its reported major version are task inputs; the whole home directory is deliberately *not* hashed.

## Tests

- Unit (`K2jTaskRunTest`, `KotlinClassDetectorTest`, `DecompilerArtifactTest`, `ProjectBuilder`-based):
  classes-root selection over real Kotlin and Java test classes dirs, the missing-classes failure,
  the no-Kotlin-plugin failure, output cleaning across two runs with a stale unit, manifest-on-failure,
  the jar/checksum/runtime guards, the detector itself, and the absence of machine-specific defaults.
- Functional (`GradleRunner`, `K2jSubjectFunctionalTest`): a subject with **both** a `.kt` and a
  `.java` source — the shape that used to hide BLOCKER 1 — run twice with a source edit in between
  and a stale generated unit in place; plus the no-Kotlin-plugin, no-classes, failed-conversion
  (manifest present) and checksum-mismatch failure modes.
- `K2jCorpusIntegrationTest`: the corpus sources built and converted by `k2j`.

## Known limitations

- **Configuration cache with composite consumption.** When this plugin is consumed through
  `pluginManagement { includeBuild(...) }`, Gradle's configuration cache fails with
  `Class K2jTask not found in class loader`. That was isolated to the included-build plugin
  classloader, not to the plugin's serialized task state: a control using a plain buildscript
  classpath with the configuration cache **stored, reused and correctly reported UP-TO-DATE**.
  Do not try to fix this in Kotlin code here; pass `--no-configuration-cache` when consuming the
  plugin as an included build. The TestKit tests do exactly that.
- **Partially done, `core/` side**: `ConversionRequest` now takes `classesRoots: List<Path>` (default
  empty, so every existing caller keeps the old classpath) and the compile gate resolves against it.
  The *survey* still reads the single `classesRoot`, so the staging merge stays: making the survey
  read a list is the remaining core change, and it would only remove the staging copy, not a defect.
- `sourceRoots` scoping assumes KGP registers Kotlin source dirs in `allJava`. If a project keeps
  `.kt` files under a Java source dir, the dirs cannot be separated and the plugin falls back to all
  of `allJava` (logged at info level).

## Verification

```bash
export JAVA_HOME='C:/Users/FrancisLalonde/.rsdk/tools/java/21.0.8-jbr'
cmd.exe /c gradlew.bat :gradle-plugin:test --no-configuration-cache
```

The `k2j` task itself needs a Java 25 runtime to fork the decompiler; the build/toolchain stays on 21.

## Report back

Files created, test names, exact command + result lines, deviations with reasons.
