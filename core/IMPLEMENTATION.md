# k2j-core — implementation contract

Freeze these interfaces exactly as declared. Only replace `TODO()` bodies; private helpers are fine.
Read `AGENTS.md` first — it has the FernFlower API facts and the Java-version trap.

## Files you own

- `src/main/kotlin/com/onomatic/k2j/core/AsmSurveyor.kt` (create) — implements `Surveyor`
- `src/main/kotlin/com/onomatic/k2j/core/FernFlowerDecompiler.kt` (create) — implements `Decompiler`
- `src/main/kotlin/com/onomatic/k2j/core/JavacValidator.kt` (create) — implements `JavaValidator`
- `src/main/kotlin/com/onomatic/k2j/core/FileSystemWriter.kt` (create) — implements `OutputWriter`
- `src/main/kotlin/com/onomatic/k2j/core/Main.kt` (create) — CLI
- `src/test/kotlin/...` (create) — tests

The *interface signatures* are frozen: `Surveyor.survey`, `Decompiler.decompile`,
`JavaValidator.validate`, `OutputWriter.write/writeManifest/deleteVerifiedSources`,
`Converter.convert` must keep their names, parameters and return types. Their supporting data
types and the orchestrator were deliberately extended to make deletion provably safe:
`ClassTarget.classFiles`, `SourceMapping.deletionBlockers`, `ConversionManifest.sources`,
`WarningReporter`, and the `K2j` deletion/decision logic. Do NOT edit anything under
`gradle-plugin/` (owned elsewhere); `corpus/` is the test subject — extend it with fixtures when
a rule needs one, but never edit it destructively at run time (deletion tests run on a copy).

## Requirements

### AsmSurveyor
- Walk `request.classesRoot` for `.class` files. Read each with ASM (`ClassReader`, no classloading)
  and accept only classes whose runtime-visible annotations include `kotlin/Metadata`.
- Skip `module-info`, `package-info`. Nested/inner/local/anonymous classes (`Foo$Bar`, `Foo$1`) are
  NOT separate targets — a target is one top-level class, but it must carry **every** class file of
  its unit on `ClassTarget.classFiles`. All of them are handed to the decompiler together, so the
  generated `Foo.java` contains the nested types. Feeding only `Foo.class` produces text that
  references types it never declares (and the source was still deleted) — that is the defect this
  rule exists to prevent.
- Do not exclude a class for being memberless: an empty interface/facade still produces a class file
  and decompiles to `public interface IEntityId {}`. Excluding it hid the class from the deletion
  guard.
- Package filter: `request.packages` empty = all; otherwise the class's binary name must start with
  `pkg + "."` for some pkg.
- className = binary name from ASM (`com.foo.Bar`), never derived from the file path.
- Source mapping: for each accepted class file, the sibling source is found by mapping the class's
  package + simple name back to `request.sourceRoots`: `com/foo/Bar.kt` or `com/foo/Bar.kt` variants
  for facades (`BarKt` -> `Bar.kt`). Facade rule: a class named `XxxKt` maps to `Xxx.kt`. Lambdas and
  anonymous classes (`Foo$1`) map to their outer's source. Record **every** class file of a source,
  including ones excluded from conversion, in `SourceMapping.classFiles`. When no source root
  matches, the class simply has no source mapping (never fails the run). When **more than one** root
  matches, fail closed: no mapping, and a recorded reason (never "first root wins").
- `SourceMapping.deletionBlockers` must record declarations the generated Java cannot represent: a
  `typealias` has no member in the output, so any source declaring one is never deletable.
- Deterministic order: sort targets by className.

### FernFlowerDecompiler
- Load the vendored-out FernFlower classes **by reflection from an explicit jar path**, NOT from the
  compile classpath: the jar is class-version 69 and this module compiles with Java 21, so it cannot
  be a compile dependency. Constructor takes `decompilerJar: Path` and `runtimeJavaHome: Path`
  (a Java 25+ JRE). Run the decompilation in a **forked JVM** using `runtimeJavaHome/bin/java` with
  `-cp decompilerJar:<forked Main class>`.
- **Both** the class list and the library list go over **stdin**, never argv: Windows caps a command
  line near 32,767 characters and a real translation exceeds that (measured: `CreateProcess
  error=206`). argv carries only the report file path. stdin is two labelled sections
  (`#CLASSES`, `#LIBRARIES`), one path per line, terminated by `#END`.
- Fork hygiene: discard stdout (an undrained pipe deadlocks a chatty child), drain stderr on a
  thread, wait with a **bounded timeout** and `destroyForcibly()` on overrun, and wrap a stdin-write
  `IOException` so the child's stderr is not lost.
- Options: default map, i.e. `rbr=1` (remove bridges) — the viewer's `rbr=0` is the known-bad setting.
  Pass explicitly so the behaviour is pinned: `mapOf("rbr" to "1")`.
- Feed each class file via `IBytecodeProvider` (read bytes yourself) and `BaseDecompiler.addSource`.
  Add the `classpath` jars via `addLibrary` so signatures resolve.
- Collect output through `IResultSaver.saveClassEntry`/`saveClassFile`; key by the qualifiedName
  argument FernFlower passes (that is the binary name).
- One `decompileContext()` per call. Deterministic: sort inputs.
- Expose collected warnings via `WarningReporter.warnings` (do not change `Decompiler.decompile`).
  The `#RESULT` summary fields must be escaped with the same `escape()` as the payload: a warning or
  failure message containing a tab or newline must not blank a field or shift columns.

### Constructor normalization (post-transform, before the parse gate)
- Kotlin emits a constructor's parameter null-check *before* the `super()`/`this(...)` delegation;
  FernFlower copies it and javac rejects the result ("call to super must be first statement").
- Hoist an explicit `super(...)`/`this(...)` to the first statement of its constructor body,
  preserving every other statement in order. Cover `super()`, `super(args)`, `this(...)`,
  already-leading constructors (no change), constructors with no explicit delegation (Java's
  implicit `super()` is already correct), and constructors inside nested/local classes.
- Conservative: an unrecognised shape is left untouched so the parse gate reports it as a per-class
  failure. Never silently drop a class.

### JavacValidator
- `javax.tools` parser-only check: `JavaCompiler.getTask` with `StandardJavaFileManager`,
  a single in-memory `JavaFileObject` per unit, `options = listOf("-proc:none")`, and
  `task.parse()` semantics via `call()` is NOT enough (it compiles). Use `JavacTool`/`JavacTask`
  and `task.parse()` (returns `Iterable<CompilationUnitTree>`); collect `Diagnostic.Kind.ERROR`
  diagnostics with line:column. Return `file:line:col: message` strings.
- The classpath for parsing is NOT required — parse-only. Keep it that way for speed.

### FileSystemWriter
- `write`: one `<outputRoot>/<package path>/<SimpleName>.java` per entry; refuse to overwrite an
  existing file (return it as… no — the contract is: if the target exists, that is a failure; but
  `write` only receives already-validated outputs, so throw `IllegalStateException` with the path).
  Return `ConvertedClass(className, outputPath)`.
- `writeManifest`: JSON at `<outputRoot>/k2j-manifest.json`, pretty-printed, using kotlinx or
  hand-rolled (hand-rolled is fine — the shape is small and stable; escape strings properly).
- `deleteVerifiedSources`: delete each file if it still exists; return the paths actually deleted.
  Never delete a directory, never follow a path outside the given list.

### Main.kt (CLI)
- Args: `--classes <dir> --out <dir> [--package p]... [--classpath jar]... [--delete-sources]
  [--source-root dir]... [--decompiler-jar path] [--runtime-java-home path]`
- Defaults: decompiler jar and runtime java home are **development-machine defaults** (a hardcoded
  path into the local Gradle transform cache and the installed IDE JBR). They are NOT vendored or
  checksum-pinned; production callers pass `--decompiler-jar`/`--runtime-java-home`. Print the
  manifest summary (including every source's decision + reason) to stdout; exit code 0 iff
  `manifest.success`.

### Tests
- JUnit 5 (`kotlin("test")` is already wired). Test the survey against the corpus classes under
  `D:/Work/k2j/corpus/app/build/classes/kotlin/main` IF present; otherwise build a tiny fixture in
  `@TempDir` by copying pre-written `.class` bytes from `src/test/resources`. At minimum:
  - survey accepts Kotlin classes, rejects a Java-compiled class (compile one with javax.tools at
    test time, or ship one tiny `.class` resource),
  - package filter works,
  - `XxxKt` facade maps to `Xxx.kt`,
  - nested/inner/local/anonymous class files are grouped onto the outer target and appear in the
    generated unit's text,
  - memberless interfaces/facades convert (they are not excluded),
  - a `typealias`-bearing source carries a deletion blocker, and an ambiguous source mapping
    (two roots) yields no mapping plus a reason,
  - decompiler produces text for `accept.plain.Single` and the text contains `class Single`,
  - decompiler warnings reach the caller,
  - the class list travels over stdin (a list longer than the Windows command-line cap converts),
  - validator accepts the decompiled text and rejects `class Broken {`,
  - writer refuses to overwrite,
  - constructor normalization turns a Kotlin-ordered constructor into compilable Java,
  - end-to-end `K2j.convert` on the corpus produces a manifest with `success == true` and
    `deletableSources` containing `Single.kt`, `Bridge.kt`, `CancelActivityLike.kt`, `Outer.kt`
    and NOT `TypeAliases.kt` or `Mixed.kt`.

**The deletion invariant** (this supersedes any earlier wording): a source is deletable only when
*every declaration it contains is represented in the written, validated output*. `TypeAliases.kt` is
non-deletable not because "it produces no classes" (it produces `Holder.class` and
`TypeAliasesKt.class`, both of which decompile fine) but because it declares `typealias Text` /
`typealias Classes`, which have no member in the generated Java.
- The decompiler test needs the Java 25 runtime; skip gracefully (assume-true) if
  `--runtime-java-home` default path does not exist, and print why.

## Verification

```bash
export JAVA_HOME='C:/Users/FrancisLalonde/.rsdk/tools/java/21.0.8-jbr'
cmd.exe /c gradlew.bat :core:test --no-configuration-cache
```

All tests must pass with nothing skipped silently (a skipped decompiler test must print its reason).

## Report back

Files created, test names, the exact command + result lines, and any deviation from this contract
with the reason. Do not weaken an assertion to make a test pass — report the failure instead.
