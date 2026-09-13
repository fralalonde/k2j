# k2j

Decompiles compiled Kotlin classes to Java source. Runs outside IntelliJ: CLI or Gradle task.

## What it is

- `core/` — plain JVM library + CLI. No Gradle API, no IntelliJ API.
- `gradle-plugin/` — Gradle plugin `com.onomatic.k2j`, task `k2j`. Consumes the build's existing
  compiled classes; compiles nothing.
- `corpus/app/` — Kotlin subject project used as the test input.

Replaces the abandoned IDE plugin at `D:\Work\k2j-ij`. Do not route new work through that.

## What it does

1. Surveys the classes directory. Selects classes carrying the `kotlin/Metadata` annotation;
   ignores Java classes. Maps each class file to its `.kt` source via the `SourceFile` attribute.
2. Decompiles each converted unit with FernFlower (the `java-decompiler.jar` IntelliJ ships), in a
   forked JVM.
3. Normalizes decompiled constructors (see *What it doesn't do* and *How it works*).
4. Parse-gates every generated unit with `javax.tools`. A unit that does not parse is reported as a
   per-class failure and not written.
5. Writes `.java` files under the output root, plus a JSON manifest.
6. Optionally deletes `.kt` sources — only those where every declaration is accounted for in the
   written output. Every source reported with a reason, deleted or kept.

Bridge methods are removed by the decompiler itself. No post-processing pruner exists or is needed.

## Requirements

- Java 21 to build and to run the CLI/plugin.
- A Java 25 runtime to fork the decompiler. The vendored jar is class-version 69. The CLI/plugin
  probes for one: explicit override, then IDE JBRs under `%LOCALAPPDATA%`, then `JAVA_HOME`.
- Gradle 9 (wrapper included) for the plugin.

## How to install

Not published to any repository; consume it as an included build.

`pluginManagement {}` is legal only in a settings script. In a build script Gradle fails at startup
with `Only Settings scripts can contain a pluginManagement {} block.`

`settings.gradle.kts`:

```kotlin
pluginManagement {
    includeBuild("D:/Work/k2j")
}
```

`settings.gradle`:

```groovy
pluginManagement {
    includeBuild 'D:/Work/k2j'
}
```

`build.gradle.kts`:
```kotlin
plugins {
    kotlin("jvm") version "2.4.0"
    id("com.onomatic.k2j")
}
```

`build.gradle`:
```groovy
plugins {
    id 'org.jetbrains.kotlin.jvm' version '2.4.0'
    id 'com.onomatic.k2j'
}
```

With composite consumption you must pass `--no-configuration-cache`. Gradle's configuration cache
fails on the second run (`Class K2jTask not found in class loader`) when a plugin comes from an
included build. That is Gradle's included-build plugin classloader, not this plugin's task state.

CLI, without Gradle:

```bash
cd D:/Work/k2j
export JAVA_HOME='C:/Users/FrancisLalonde/.rsdk/tools/java/21.0.8-jbr'
cmd.exe /c gradlew.bat :core:installDist --no-configuration-cache
# -> core/build/install/core/bin/core
```

## How to use

Gradle:

```bash
cmd.exe /c gradlew.bat k2j --no-configuration-cache
```

Output goes to `build/k2j`, manifest to `build/k2j/k2j-manifest.json`. With `deleteSources = true`
the first run deletes the sources it converted, so a later run finds no Kotlin classes and fails
saying so.

Extension, `build.gradle.kts`:

```kotlin
k2j {
    packages.set(listOf("com.example.app"))   // default: every Kotlin class in the classes root
    outputRoot.set(layout.buildDirectory.dir("generated/k2j"))
    deleteSources.set(true)                   // default false
    // decompilerJar.set(layout.projectDirectory.file("custom-java-decompiler.jar"))
    // decompilerJarSha256.set("...")         // verified when set
    // runtimeJavaHome.set(layout.projectDirectory.dir("..."))  // default: probe
}
```

Extension, `build.gradle`:

```groovy
k2j {
    packages.set(['com.example.app'])          // default: every Kotlin class in the classes root
    outputRoot.set(layout.buildDirectory.dir('generated/k2j'))
    deleteSources.set(true)                    // default false
    // decompilerJar.set(layout.projectDirectory.file('custom-java-decompiler.jar'))
    // decompilerJarSha256.set('...')          // verified when set
    // runtimeJavaHome.set(layout.projectDirectory.dir('...'))   // default: probe
}
```

CLI:

```bash
core --classes <classes-dir> --out <java-root> \
     --source-root <kt-root>[ --source-root <kt-root>...] \
     [--package <pkg>...] [--classpath <jar>...] [--delete-sources] \
     [--decompiler-jar <jar>] [--runtime-java-home <java-home>]
```

Worked example:

```bash
cmd.exe /c 'D:/Work/k2j/gradlew.bat' -p D:/Work/k2j/corpus/app classes --no-configuration-cache
./core/build/install/core/bin/core \
  --classes corpus/app/build/classes/kotlin/main \
  --source-root corpus/app/src/main/kotlin \
  --out build/accept/java
```

`--delete-sources` deletes files. Point it at a copy, never at `corpus/app`:

```bash
mkdir -p build/acc/src/main && cp -r corpus/app/src/main/kotlin build/acc/src/main/kotlin
```

Exit code 0 iff the manifest reports `success`.

### Options and defaults

| Gradle | CLI | Default | Effect |
|---|---|---|---|
| `packages` | `--package` (repeatable) | empty = all | Restrict conversion to these packages |
| `outputRoot` | `--out` | `build/k2j` (Gradle) | Where `.java` and the manifest are written |
| `deleteSources` | `--delete-sources` | `false` | Delete sources whose declarations are all represented in the written output |
| `decompilerJar` | `--decompiler-jar` | vendored jar in the plugin; dev-machine path in the CLI | Override the decompiler |
| `decompilerJarSha256` | — | plugin pin | Verify the override's digest |
| `runtimeJavaHome` | `--runtime-java-home` | probe | Override the Java 25 runtime |
| — | `--classes` | required | Compiled classes root |
| — | `--source-root` (repeatable) | empty | `.kt` roots, needed for source mapping and deletion |
| — | `--classpath` (repeatable) | empty | Jars the classes were compiled against |

### Manifest

`k2j-manifest.json` in the output root. Keys: `converted` (`className`, `outputPath`), `failures`
(`className`, `phase`, `message`), `warnings`, `deletableSources`, `deletedSources`, `sources` —
one entry per surveyed `.kt` with `source`, `deletable` and `reason` either way — and `success`.

## What it doesn't do

- Does not guarantee the generated Java compiles. The gate is a **parse**, not a compile: no
  classpath, no attribution. Unresolved types and semantic errors pass it. Compile the output to
  check; that is how defects in this pipeline have been found.
- Does not type-check, refactor, or make the Java idiomatic. Output is decompiler text plus one
  narrow normalization (constructor delegation ordering).
- Does not parse Kotlin source. The survey is bytecode plus a textual scan for `typealias`.
  A classless declaration kind other than `typealias` would not be detected as unrepresented.
- Does not do Lombok. Out of scope.
- Does not handle a classes root split across several directories in `core` — `ConversionRequest`
  takes one. The plugin merges multiple Kotlin-bearing roots into a staging directory.
- Does not touch any source set but `main`.
- Not cross-platform-verified. Developed and verified on Windows.

## How it works

Pipeline, in order: survey → decompile → normalize → parse gate → write → deletion decision.

- **Survey.** ASM reads each `.class`. Present `kotlin/Metadata` ⇒ Kotlin. `SourceFile` ⇒ owning
  `.kt`. Nested, inner, local and anonymous classes are grouped with their top-level class and fed
  to one decompiler context, so the outer unit declares them.
- **Decompile.** FernFlower with default options, in a forked JVM (class-version 69 ⇒ Java 25).
  Class and library paths cross to the child on stdin, not argv.
- **`rbr`.** FernFlower's `rbr` (remove bridge methods) defaults to 1. The Kotlin bytecode viewer
  forces `rbr=0` to show bytecode faithfully; that setting alone is why IDE-converted output is
  full of `// $FF: bridge method` duplicates, and why the old IDE plugin needed a PSI pruner.
  Standalone, `rbr=1` removes both bridge shapes (erasure-to-`Object`, covariant returns).
- **Normalize.** Kotlin emits a constructor's parameter null-check *before* the `super()` call, so
  faithful output is illegal Java:
  `Intrinsics.checkNotNullParameter(id, "id"); super(); this.id = id;`. A conservative text
  transform hoists `super(...)`/`this(...)` to the first statement. Unrecognized shapes are left
  alone and surface as parse failures.
- **Deletion proof.** A source is deletable only when every class file it produced was converted
  and written, **and** no unrepresented declaration remains in it (a `typealias` has no member),
  **and** no ambiguity exists about which source a class belongs to. Otherwise it is kept, with the
  reason recorded.

## Tests and verification

```bash
export JAVA_HOME='C:/Users/FrancisLalonde/.rsdk/tools/java/21.0.8-jbr'
cmd.exe /c gradlew.bat :core:test :gradle-plugin:test --no-configuration-cache   # --rerun to force
```

65 tests: 36 core, 29 plugin. Counts per class in `*/build/test-results/test/*.xml`.
`BUILD SUCCESSFUL` alone can mean `UP-TO-DATE` — pass `--rerun`.

Useful checks beyond the tests: the corpus rebuild (`javac` over the generated tree, classpath
`kotlin-stdlib-2.4.0.jar` + `annotations-13.0.jar`; generated units import `kotlin.Metadata` and
`@NotNull`), and a `--delete-sources` round trip on a source copy.

## License

No LICENSE file in this repository. Treat as proprietary/unlicensed until one is added; add one
before distributing or sharing outside the organisation.

Repository state: not under version control as of this writing.
