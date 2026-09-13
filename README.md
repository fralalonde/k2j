# k2j — Kotlin-to-Java translation outside the IDE

Compose the Kotlin compiler ecosystem and the bundled FernFlower decompiler as a plain JVM
pipeline, driven from the command line and from Gradle. No IntelliJ required for translation
work; the IDE plugin becomes a thin shell over the same engine.

## Layout

- `core/` — `k2j-core`, plain JVM library + CLI. Survey → ASM metadata filter → FernFlower →
  constructor normalisation → javac parse gate → write + manifest (JSON). No Gradle APIs, no
  IntelliJ.
- `gradle-plugin/` — `k2j-gradle`, thin plugin adding a `k2j` task that depends on `classes`
  and consumes the build's own compiled classes and classpath. No compilation happens here.
- `corpus/app/` — Gradle + Kotlin subject project used to test decompilation and rebuild.
  Fixtures: plain classes, erasure bridges, covariant bridges, typealias-only files, and a
  nested/inner/local/anonymous-class file.
- `tools/` — throwaway experiments (kept for reference, not shipped).

## The finding that shaped this

FernFlower's `rbr` option (remove bridge methods) defaults to **1**. The Kotlin bytecode viewer
forces `rbr=0` to show bytecode faithfully — which is why IDE-converted output is full of
`// $FF: bridge method` duplicates. Running FernFlower standalone with defaults drops both
bridge shapes (erasure-to-`Object` and covariant supertype returns) with no post-processing.
Measured on the corpus: `rbr=1` → 0 bridges, `rbr=0` → 4 bridge artifacts across 10 classes.

Consequence: the IDE plugin's PSI-based `SyntheticMethodPruner` is not needed in this pipeline.

## Loop

```bash
cd corpus/app
gradle build                 # builds the app normally
gradle k2j                   # decompiles Kotlin classes from the compiled output
```

The task reads the project's own compiled classes (single source of truth for the toolchain),
filters `.class` files by the presence of `kotlin/Metadata` (Kotlin-only by definition), runs
the pinned FernFlower, runs each generated unit through a **parse gate** (see below), and
writes a JSON manifest next to the output: converted, warnings, failures-with-reasons, and the
survey-derived list of `.kt` sources that are safe to delete.

## Two-stage safety

- **Parse gate.** Every generated unit is fed to `javax.tools` and accepted only if it *parses*
  as Java (`JavacTask.parse()`). This is deliberately not a compile: there is no classpath and
  no attribution, so it cannot detect resolution or semantic errors (e.g. an unresolved type, or
  `super()` appearing before another statement). Call it a parse gate, not "validation".
- **Constructor normalisation.** Kotlin bytecode runs a constructor's parameter null-check
  *before* the `super()`/`this(...)` delegation, which FernFlower copies; javac rejects it. A
  conservative text transform hoists the delegation to the first statement before the parse gate.
  A shape it cannot recognise is left untouched and reported as a per-class failure — never
  silently dropped.
- **Deletion proof.** A source `.kt` is deletable only when *every declaration it contains* is
  represented in the written output: every class file the source produced (outer, nested, inner,
  local, anonymous) was converted, written and parsed, **and** the survey found no declaration the
  generated Java cannot represent (a `typealias` has no member) and no ambiguity about which source
  a class belongs to. The manifest lists every source with a reason, so a surviving `.kt` always
  says why.

## Decompiler pinning

The `java-decompiler.jar` is **not vendored** in this repository and is **not checksum-pinned**.
It is class-version 69 (needs a Java 25 runtime; the IDE's bundled JBR 25 supplies one) and lives
at a hardcoded path into the local Gradle transform cache
(`core/src/main/kotlin/com/onomatic/k2j/core/Main.kt`, `DEFAULT_DECOMPILER_JAR`). That constant is
a **development-machine default** only; production callers pass `--decompiler-jar`, and the Gradle
plugin resolves the jar from the project's dependencies.

## Status

- `rbr` experiment: verified (see `tools/RbrExperiment.java`).
- `core`: survey → decompile → normalise → parse-gate → write → deletion proof, with the corpus
  verified to recompile cleanly with javac (kotlin-stdlib 2.4.0 + org.jetbrains:annotations 13.0).
- `gradle-plugin`: scaffolded.
