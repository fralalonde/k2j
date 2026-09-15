# k2j-core — implementation contract

Freeze these interfaces exactly as declared. Only replace `TODO()` bodies; private helpers are fine.
Read `AGENTS.md` first — it has the FernFlower API facts and the Java-version trap.

## Files you own

- `src/main/kotlin/com/example/k2j/core/AsmSurveyor.kt` (create) — implements `Surveyor`
- `src/main/kotlin/com/example/k2j/core/FernFlowerDecompiler.kt` (create) — implements `Decompiler`
- `src/main/kotlin/com/example/k2j/core/JavacValidator.kt` (create) — implements `JavaValidator`
- `src/main/kotlin/com/example/k2j/core/WildcardCaptureNormalizer.kt` (create) — declaration-site
  variance repair (see below)
- `src/main/kotlin/com/example/k2j/core/CompileChecker.kt` (create) — `CompileChecker` +
  `JavacCompileChecker`, the opt-in compile gate
- `src/main/kotlin/com/example/k2j/core/FileSystemWriter.kt` (create) — implements `OutputWriter`
- `src/main/kotlin/com/example/k2j/core/Main.kt` (create) — CLI
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
- `DefaultArgumentConstructorNormalizer` also repairs FernFlower's hoisted argument spills, where a
  delegation references undeclared `varN` locals and emits their declarations after the call. Inline
  one or several independently proven spill initializers in argument order and delete only those
  declarations. A cast around the spill is allowed; compound use is not. Preserve Kotlin's emitted
  `checkNotNull*(varN, "message")` behavior by wrapping the initializer in
  `java.util.Objects.requireNonNull(initializer, "message")`.
- Conservative: an unrecognised shape is left untouched so the parse gate reports it as a per-class
  failure. Never silently drop a class.

### Interface-default repair (class-file-backed, before the parse gate)
- `InterfaceDefaultSynthesis` restores compiler-generated delegating members FernFlower removes when
  the class file proves a pure delegation to a direct interface default.
- FernFlower can also lose the owner in a non-bridge interface default call, rendering bytecode
  `invokespecial IFace.m` as illegal `super.m()`. Restore `IFace.super.m()` only when ASM proves the
  owner is a direct superinterface and the bytecode/text occurrence counts agree. Already-qualified,
  overloaded, transitive, or ambiguous calls are unchanged.
- These are bytecode-backed repairs. Do not infer interface owners or bridge bodies from Kotlin source
  or javac wording.

### Enum normalization (post-transform, before the parse gate)
- Kotlin compiles an enum with constructor parameters into a class that declares the instance fields
  **before** the constants; FernFlower copies that member order, and javac rejects it
  (`enum constant expected here`) because a Java enum body must open with its constant list.
- Lift the whole constant list of every enum body in the unit (nested enums included) to the top of
  that body, immediately after its `{`, preserving the constants' text, order, commas, trailing `;`
  and line breaks as one block. Every other member keeps its relative order. A body whose constants
  are already first is not touched.
- Second, coupled defect: the class file declares `private static final T[] $VALUES` but FernFlower
  emits only the `$values()` accessor, so `$ENTRIES = EnumEntriesKt.enumEntries($VALUES)` dangles.
  Rewrite references to `$VALUES` as `$values()` when the unit declares no such field. Never invent
  a `$VALUES` field; never remove `getEntries()` or `$values()`. (This one *parses*, so the gate
  cannot see it — it only fails at `compileJava`.)
- Conservative, all-or-nothing per unit: an enum body that does not match a provable shape returns
  the unit unchanged for the gate to report. Never partially rewrite a unit.

### Failure dump (opt-in, all phases)
- A parse-gate failure names `Class.java:line:col` in text that was never written. `--dump-failures
  <dir>` (CLI), `k2j { dumpFailures }` / `--dump-failures` (Gradle) writes each failed unit's
  generated text to `<dir>/<Class>.java` plus `<dir>/<Class>.failure.txt` with the class, phase and
  reason. Off by default; a dump that cannot be written is a warning, and a dumped failure is still a
  failure.

### Declaration-site variance normalization (post-transform, before the parse gate)
- Kotlin's `Map<K, out V>` (declaration-site variance) makes FernFlower emit the *parameter* of the
  synthesized constructor — and of the `copy(...)` method — as `Map<String, ? extends Number>` while
  emitting the *field* invariant `Map<String, Number>`. `this.properties = properties;` then fails
  javac's wildcard-capture rules (`incompatible types: Map<String,CAP#1> cannot be converted to
  Map<String,Number> where CAP#1 extends Number from capture of ? extends Number`). **The text
  parses**, so the parse gate cannot see it — this is the defect the compile check exists for.
- `WildcardCaptureNormalizer` strips the wildcards from exactly the parameters the shape makes safe,
  by splicing the field's own declared type text over the parameter's type range. All of these must
  hold, or the unit is returned unchanged:
  1. the parameter's declared type contains a wildcard (`? extends X` / `? super X`);
  2. a member-level field of the **same simple name**, with no initializer, is declared in the **same
     class body** as the declaration carrying the parameter;
  3. that field's type contains no wildcard (it is invariant);
  4. the field's type and the parameter's type are **token-identical once the parameter's wildcards
     are replaced by their bounds**. One comparison proves the erasure, the arity and every type
     argument match, rules out a raw field, and refuses a bare `?` (nothing to substitute). A
     `? super X` is accepted only when substituting `X` reproduces the field exactly;
  5. a **constructor** parameter must be assigned to that field in the body
     (`this.<name> = <name>;`);
  6. a **`copy(...)`** parameter must be passed to that same class's constructor
     (`new <Class>(<name>)`), where the class declares a constructor parameter this pass stripped.
- The rewrite replaces one parameter's type and nothing else: the field, the getter, `component1`,
  the synthetic `copy$default`, the annotations, the bodies and the surrounding formatting are carried
  over byte for byte. A unit with no wildcard anywhere is returned as the same string.
- Never narrows an unrelated API: only the parameter that is provably assigned to (or passed to the
  constructor of) a field of the same name and same stripped type is touched.
- Conservative, per candidate: an unprovable shape is left as the decompiler emitted it, so the gate
  (parse, and with `--compile-check` the compiler) reports the unit as a per-class failure. A class is
  never silently half-rewritten and never silently dropped.

### Compile check (opt-in, before the write)
- The parse gate is syntax only. `--compile-check` (CLI) / `k2j { compileCheck = true }` (Gradle)
  adds semantic validation between parsing and writing.
- Compile the complete map of parse-clean generated sources in **one** `JavaCompiler` task. Map each
  diagnostic back to its `JavaFileObject` URI and then to the owning class. Diagnostics without a
  source are global and reject every candidate rather than being ignored.
- Do not compile once per unit and do not implement a repeated settle pass. On large modules that
  design creates thousands of compiler/file-manager/temp-directory lifecycles and recompiles the
  same failures. A batch also models the replacement build correctly: generated callers resolve
  generated callees from the same source surface immediately.
- After the first batch, `RawTypeFallback` may propose a repair only from javac's own supported
  diagnostics. Verify each proposal narrowly, revert rejected proposals, then run one authoritative
  final batch. Record every retained raw fallback in manifest warnings.
- A rejected unit becomes `ConversionFailure(className, "COMPILE", reason)` with javac's
  `file:line:column: message`; it is not written and its source cannot be deleted.
- The classpath is `ConversionRequest.compileClasspath`, the decompiler classpath, and every module
  classes directory (`classesRoots`). This includes Java peers beside Kotlin output.
- Keep the gate off by default because it requires a complete dependency classpath. The compiler
  itself is no longer the volume bottleneck; stock FernFlower dominates measured integration time.
  Emit phase timings at info level so survey, decompile, normalize/parse, batch compile, and total
  time are observable.

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
  [--source-root dir]... [--dump-failures dir] [--compile-check] [--compile-classpath path]...
  [--decompiler-jar path] [--runtime-java-home path]`
  Every flag also accepts `--flag=value`. `--compile-check` is a boolean switch; `--compile-classpath`
  is repeatable and only read when the switch is on.
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
  - wildcard-capture normalization turns the `accept.variance.VarianceProps` decompiler output into
    text that compiles (and leaves a name-mismatched, differently-erased or wildcarded-field shape
    alone, so the gate reports it),
  - the compile check reports a unit javac rejects as a `COMPILE` failure with javac's own
    file/line/column/message, writes nothing and keeps its source non-deletable, and stays off by
    default,
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
