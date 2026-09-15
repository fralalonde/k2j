# k2j

Decompiles compiled Kotlin classes to Java source. Runs outside IntelliJ: CLI or Gradle task.

## What it is

- `core/` — plain JVM library + CLI. No Gradle API, no IntelliJ API.
- `gradle-plugin/` — Gradle plugin `org.example.k2j`, task `k2j`. Consumes the build's existing
  compiled classes; compiles nothing.
- `corpus/app/` — Kotlin subject project used as the test input.

Replaces the abandoned IDE plugin at `D:\Work\k2j-ij`. Do not route new work through that.

## What it does

1. Surveys the classes directory. Selects classes carrying the `kotlin/Metadata` annotation;
   ignores Java classes. Maps each class file to its `.kt` source via the `SourceFile` attribute.
2. Decompiles each converted unit with FernFlower (the `java-decompiler.jar` IntelliJ ships), in a
   forked JVM.
3. Normalizes decompiled text: constructor delegation ordering, enum member order / the synthetic
   `$VALUES` reference, and Kotlin's declaration-site variance (`Map<K, out V>`), which emits a
   wildcarded parameter that the invariant field rejects (see *How it works*).
4. Parse-gates every generated unit with `javax.tools`. A unit that does not parse is reported as a
   per-class failure and not written. With `--compile-check` that gate becomes a real `javac` compile
   against the project's classpath, and a unit the compiler rejects is a per-class failure with
   javac's own diagnostic instead of a silent pass.
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
    id("org.example.k2j")
}
```

`build.gradle`:
```groovy
plugins {
    id 'org.jetbrains.kotlin.jvm' version '2.4.0'
    id 'org.example.k2j'
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
    dumpFailures.set(layout.buildDirectory.dir("k2j-failures"))  // default: no dump
    compileCheck.set(true)                    // default false: parse-only gate
    compileClasspath.from(configurations.someExtra)              // extra deps for the compile check
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
    dumpFailures.set(layout.buildDirectory.dir('k2j-failures'))   // default: no dump
    compileCheck.set(true)                     // default false: parse-only gate
    compileClasspath.from(configurations.someExtra)               // extra deps for the compile check
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
     [--dump-failures <dir>] \
     [--compile-check [--compile-classpath <path>...]] \
     [--decompiler-jar <jar>] [--runtime-java-home <java-home>]
```

Every flag also accepts `<flag>=<value>`, e.g. `--dump-failures=build/k2j-failures`.

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
| `dumpFailures` | `--dump-failures` | off | Write the generated text of every unit that fails a phase into this directory (one `.java` per class, plus a `.failure.txt` sidecar with the phase and the reason) |
| `compileCheck` | `--compile-check` | `false` | Compile each generated unit with `javac` (via `javax.tools`) before writing it; a unit that does not compile is a per-class failure carrying javac's diagnostic and is not written |
| `compileClasspath` | `--compile-classpath` (repeatable) | empty | Extra dependencies for the compile check. Added to the module's own compile classpath; core always adds the classes root and the decompiler classpath |
| `decompilerJar` | `--decompiler-jar` | vendored jar in the plugin; dev-machine path in the CLI | Override the decompiler |
| `decompilerJarSha256` | — | plugin pin | Verify the override's digest |
| `runtimeJavaHome` | `--runtime-java-home` | probe | Override the Java 25 runtime |
| — | `--classes` | required | Compiled classes root |
| — | `--source-root` (repeatable) | empty | `.kt` roots, needed for source mapping and deletion |
| — | `--classpath` (repeatable) | empty | Jars the classes were compiled against |

### Diagnosing a rejected unit

A parse-gate failure is reported as `ClassName.java:line:col: message`, but the unit was never
written, so that position refers to text nobody can see. `--dump-failures <dir>` (or
`k2j { dumpFailures = ... }`) writes it out on the spot — one `<ClassName>.java` per failed unit,
exactly as the gate saw it (after the text normalizers), plus a `<ClassName>.failure.txt` sidecar
carrying the class name, the phase and the reason. The failing run's error message names the
directory. Nothing is written when the flag is unset, and a failing unit is still a failure: the
dump is a diagnostic, not a way to pass.

```bash
# CLI
core --classes build/classes/kotlin/main --out build/k2j --dump-failures build/k2j-failures
# Gradle
cmd.exe /c gradlew.bat k2j --dump-failures=build/k2j-failures --no-configuration-cache
```

The same dump covers a unit the **compile** gate rejected: the phase reads `COMPILE` instead of
`VALIDATE`, and the reason is javac's own diagnostic (file, line, column, message). That is the only
way to read a unit that is valid Java syntax and still not accepted by the compiler:

```bash
core --classes build/classes/kotlin/main --out build/k2j \
     --compile-check --compile-classpath libs/kotlin-stdlib.jar \
     --dump-failures build/k2j-failures
```

```text
failures: 1
  [COMPILE] com.example.VarianceProps: VarianceProps.java:25:27: incompatible types: java.util.Map<java.lang.String,capture#1 of ? extends java.lang.Number> cannot be converted to java.util.Map<java.lang.String,java.lang.Number>
```

### The compile check

`--compile-check` (CLI) / `k2j { compileCheck = true }` (Gradle) adds a real semantic gate after parsing. All parse-clean generated units are compiled **together** in one `javac` task. This matters for both correctness and runtime: generated callers see generated callees immediately, and a module does not pay for one compiler/file-manager/temp-directory lifecycle per class. Diagnostics are mapped back to their source unit; rejected units are removed, and one final batch proves the accepted tree compiles cleanly.

The gate has one narrow second chance: when javac itself identifies a supported generic-return clash, `RawTypeFallback` makes only that named declaration raw and recompiles it. The repair is retained only if it compiles; otherwise the original diagnostic is reported. Every accepted fallback is recorded in manifest warnings.

A rejected unit is **not** written. The run's success, manifest, and deletion proof therefore remain fail-closed. `--compile-classpath` is repeatable; the Gradle plugin adds the module's compile classpath, and core adds the classes root and decompiler classpath.

It is off by default because it needs a complete dependency classpath, not because javac should be the normal inner loop. Use parse-only runs to inspect decompiler output, focused corpus tests while developing a repair, and the compile gate for package or milestone acceptance.

```bash
# CLI: the corpus, against the two libraries the generated units import
core --classes corpus/app/build/classes/kotlin/main --out build/accept/java \
     --compile-check \
     --compile-classpath <kotlin-stdlib-2.4.0.jar> \
     --compile-classpath <annotations-13.0.jar>
# Gradle: the module's own classpath is already wired
gradle k2j --compile-check --no-configuration-cache
```

### Manifest

`k2j-manifest.json` in the output root. Keys: `converted` (`className`, `outputPath`), `failures`
(`className`, `phase`, `message`), `warnings`, `deletableSources`, `deletedSources`, `sources` —
one entry per surveyed `.kt` with `source`, `deletable` and `reason` either way — and `success`.

## What it doesn't do

- Does not guarantee the generated Java compiles. The default gate is a **parse**, not a compile: no
  classpath, no attribution. Unresolved types and semantic errors pass it. `--compile-check` upgrades
  it to a real `javac` compile against the classpath you give it (off by default, because it is
  slower and needs those dependencies to resolve) — that, or compiling the output yourself, is how
  you find out.
- Does not type-check, refactor, or make the Java idiomatic. Output is stock FernFlower text plus a
  chain of conservative, evidence-driven repairs. Text-only repairs refuse ambiguous shapes;
  interface-default repairs use the originating class file as authority; raw-type fallback is driven
  by javac diagnostics and is accepted only after recompilation.
- Does not parse Kotlin source. The survey is bytecode plus a textual scan for `typealias`.
  A classless declaration kind other than `typealias` would not be detected as unrepresented.
- Does not do Lombok. Out of scope.
- Does not handle a classes root split across several directories in `core` — `ConversionRequest`
  takes one. The plugin merges multiple Kotlin-bearing roots into a staging directory.
- Does not touch any source set but `main`.
- Not cross-platform-verified. Developed and verified on Windows.

## How it works

Pipeline, in order: survey → decompile → normalize/repair → parse gate → optional batch compile gate → write → deletion decision.

- **Survey.** ASM reads each `.class`. Present `kotlin/Metadata` ⇒ Kotlin. `SourceFile` ⇒ owning
  `.kt`. Nested, inner, local and anonymous classes are grouped with their top-level class and fed
  to one decompiler context, so the outer unit declares them.
- **Decompile.** FernFlower with default options, in a forked JVM (class-version 69 ⇒ Java 25).
  Class and library paths cross to the child on stdin, not argv.
- **`rbr`.** FernFlower's `rbr` (remove bridge methods) defaults to 1. The Kotlin bytecode viewer
  forces `rbr=0` to show bytecode faithfully; that setting alone is why IDE-converted output is
  full of `// $FF: bridge method` duplicates, and why the old IDE plugin needed a PSI pruner.
  Standalone, `rbr=1` removes both bridge shapes (erasure-to-`Object`, covariant returns).
- **Normalize.** Three conservative text transforms, all applied before the gate, all returning the
  unit unchanged when they cannot prove a shape (so the gate reports it, never a silent half-repair).
  - *Constructors.* Kotlin emits a constructor's parameter null-check *before* the `super()` call, so
    faithful output is illegal Java:
    `Intrinsics.checkNotNullParameter(id, "id"); super(); this.id = id;`. The transform hoists
    `super(...)`/`this(...)` to the first statement.
  - *Enums.* Kotlin compiles an enum with constructor parameters into a class whose instance fields
    are declared before the constants, and FernFlower copies that member order — but a Java enum body
    must open with its constant list (`enum constant expected here`). The transform lifts the whole
    constant list (verbatim: text, order, commas, trailing `;`, line breaks) to the top of its own
    enum body, nested enums included, leaving every other member in its original order. The same
    pass repairs the coupled defect: the class file declares `private static final T[] $VALUES` and
    FernFlower emits only the `$values()` accessor, so `$ENTRIES = EnumEntriesKt.enumEntries($VALUES)`
    dangles. References to `$VALUES` become `$values()` when the unit declares no such field — the
    field is never invented, and `getEntries()`/`$values()` are never dropped. (`$VALUES` parses, so
    the gate cannot see this one; it fails at `compileJava`.)
  - *Declaration-site variance.* Kotlin's `Map<K, out V>` makes FernFlower emit the **parameter** of
    the synthesized constructor (and of `copy(...)`) as `Map<String, ? extends Number>` while emitting
    the **field** invariant, so `this.properties = properties;` fails javac's capture rules
    (`incompatible types: Map<String,CAP#1> cannot be converted to Map<String,Number>`) — in text
    that parses. The transform splices the field's own declared type over such a parameter, but only
    when every part of the shape is proven: the parameter carries a wildcard, a member-level field of
    the **same simple name** sits in the **same class body**, that field is itself invariant, and the
    two types are token-identical once the parameter's wildcards are replaced by their bounds (which
    is what proves the erasure, the arity and every argument match, and that no unrelated API is
    narrowed). A constructor parameter additionally has to be assigned to that field
    (`this.<name> = <name>;`); the `copy(...)` parameter has to be passed to that same class's
    constructor (`new C(<name>)`). Everything else, including the field, the getter, `component1` and
    `copy$default`, is carried over byte for byte.
- **Parse gate** (`javax.tools`, no classpath): a unit that does not parse is a per-class failure and
  is never written.
- **Compile gate** (opt-in, `--compile-check`): the same treatment for a unit javac rejects, with
  javac's own diagnostic. It runs after the parse gate and before the write, so a unit that fails it
  is not written, the run reports failure, and its source is not deletable.
- **Deletion proof.** A source is deletable only when every class file it produced was converted
  and written, **and** no unrepresented declaration remains in it (a `typealias` has no member),
  **and** no ambiguity exists about which source a class belongs to. Otherwise it is kept, with the
  reason recorded.

## Incremental migration workflow

Do not use a whole-module “census” as the edit loop. On the current Windows/JBR 25 setup, a measured 44-unit package spends about 40 seconds in FernFlower, under one second in normalization, and under two seconds in batch javac. Full-module latency is therefore dominated by decompilation volume, not compilation or a deadlock.

Use this bounded sequence:

1. Reproduce one diagnostic family from a saved failure dump; do not rerun the target module while designing the repair.
2. Add the smallest real Kotlin shape to `corpus/app/src/main/kotlin/accept/` and prove stock FernFlower still emits the defect.
3. Add focused fail-before/pass-after and refusal tests. A repair must be idempotent and fail closed.
4. Run only the affected corpus package with `--compile-check`.
5. Run one representative target package read-only. Compare manifests programmatically and require zero newly failing units.
6. Run the full module only after several packages/categories have closed or before a source-replacement milestone.

Every conversion logs phase timings (`survey`, `decompile`, `normalize+parse`, optional `batch compile`, and `total`) at info level. Use Gradle `--info` when diagnosing runtime. A quiet failure-dump directory is not a progress signal: successful units do not create dumps.

On Windows, invoke Gradle through `gradlew.bat` from PowerShell and use JBR 21 for k2j's build/tests. Use JBR 25 for FernFlower and the target module.

## Tests and verification

```powershell
$env:JAVA_HOME = 'C:/Users/FrancisLalonde/.rsdk/tools/java/21.0.8-jbr'
cmd.exe /c 'gradlew.bat :core:test :gradle-plugin:test --no-configuration-cache --rerun'
```

Test counts are parsed from `core/build/test-results/test/TEST-*.xml` and
`gradle-plugin/build/test-results/test/TEST-*.xml`; do not infer them from Gradle task status.
`BUILD SUCCESSFUL` alone can mean `UP-TO-DATE`, so use `--rerun` when the execution itself is evidence.

Useful checks beyond the tests: the corpus rebuild (`javac` over the generated tree, classpath
`kotlin-stdlib-2.4.0.jar` + `annotations-13.0.jar`; generated units import `kotlin.Metadata` and
`@NotNull`), the same corpus run with `--compile-check` (it fails the run instead of converting a unit
javac rejects), and a `--delete-sources` round trip on a source copy.

## License

No LICENSE file in this repository. Treat as proprietary/unlicensed until one is added; add one
before distributing or sharing outside the organisation.

Repository state: not under version control as of this writing.
