package org.example.k2j.core

import java.nio.file.Path

/**
 * The orchestrator. Wires survey → decompile → normalise → validate → write, and derives the
 * deletable-source list from the survey manifest rather than from a heuristic. Per-class failure
 * isolation: one bad class never aborts the run, it becomes a failure entry with its reason.
 *
 * [ConversionRequest.compileCheck] inserts an optional compile gate between the parse gate and the
 * writer: the same per-class failure treatment, with javac's own diagnostic, for the semantic
 * defects a parse cannot see.
 */
interface Converter {
    fun convert(request: ConversionRequest): ConversionManifest
}

/** Where the run's own diagnostics go (CLI prints, tests assert). */
interface RunLog {
    fun info(message: String)
    fun warn(message: String)
    fun error(message: String, cause: Throwable? = null)
}

/**
 * Entry point used by both the CLI and the Gradle plugin.
 *
 * Deletion is fail-closed and rests on declarations, not just class files: a source is deletable
 * only when every class file it produced was written and validated *and* the survey recorded no
 * declaration the output cannot represent (see [Surveyor]). Every source the survey mapped gets a
 * [SourceDecision] in the manifest, so a surviving `.kt` always carries a reason.
 */
class K2j(
    private val surveyor: Surveyor,
    private val decompiler: Decompiler,
    private val validator: JavaValidator,
    private val writer: OutputWriter,
    private val log: RunLog,
    private val compileChecker: CompileChecker = JavacCompileChecker()
) : Converter {

    var lastResult: ConversionResult? = null
        private set

    override fun convert(request: ConversionRequest): ConversionManifest {
        val runStarted = System.nanoTime()
        val surveyStarted = System.nanoTime()
        val survey = surveyor.survey(request)
        log.info("k2j timing: survey ${elapsedSeconds(surveyStarted)}s (${survey.targets.size} units)")
        if (survey.targets.isEmpty()) {
            log.warn("no Kotlin classes matched ${request.packages.ifEmpty { listOf("<all>") }} under ${request.classesRoot}")
        }
        val failures = mutableListOf<ConversionFailure>()
        val outputs = linkedMapOf<String, String>()
        val rawTypeRepairs = mutableListOf<String>()
        val dumper = request.dumpFailures?.let { FailureDumper(it, log) }

        // Decompile in one FernFlower context per run: the decompiler resolves cross-class signatures
        // better with the whole target set, and one context is one deterministic pass. Every class
        // file of a unit (outer + `Outer$Nested` siblings) is fed so the generated unit contains the
        // nested types.
        val decompileStarted = System.nanoTime()
        val decompiled = try {
            decompiler.decompile(survey.targets.flatMap { it.classFiles }, request.classpath)
        } catch (t: Throwable) {
            log.error("decompilation failed for the whole target set", t)
            val reason = t.message ?: t.javaClass.simpleName
            dumper?.dump("k2j-run", "DECOMPILE", "decompilation failed for the whole target set: $reason", null)
            val failedManifest = ConversionManifest(
                converted = emptyList(),
                failures = listOf(ConversionFailure("*", "DECOMPILE", reason)),
                deletableSources = emptyList(),
                deletedSources = emptyList(),
                warnings = decompilerWarnings(),
                sources = survey.sources.map {
                    SourceDecision(it.source.toString(), false, "decompilation failed for the whole target set")
                }
            )
            lastResult = ConversionResult(failedManifest, survey)
            return failedManifest
        }

        log.info("k2j timing: decompile ${elapsedSeconds(decompileStarted)}s (${decompiled.size} units)")
        val normalizeStarted = System.nanoTime()
        for (target in survey.targets) {
            val raw = decompiled[target.className]
            if (raw == null) {
                failures += ConversionFailure(target.className, "DECOMPILE", "the decompiler produced no unit for this class")
                dumper?.dump(
                    target.className,
                    "DECOMPILE",
                    "the decompiler produced no unit for this class",
                    null
                )
                continue
            }
            // Six text repairs run before the gate, in this order: constructors (Kotlin emits the
            // parameter null-check before the super()/this(...) delegation), default-argument
            // constructors (FernFlower delegates `this(varN)` before declaring varN), enums
            // (FernFlower emits the instance fields before the constant list, and references a
            // `$VALUES` field it never emits), declaration-site variance (the constructor/copy
            // parameter carries the wildcard of `Map<K, out V>` while the field it is assigned to is
            // invariant), raw entry sets (an inlined `filter` renders its receiver from the erased
            // LocalVariableTable, so the for-each element reads as Object), and leaked parameter
            // names (`<set-?>`, the setter parameter FernFlower could not name). All six are
            // conservative: an unrecognized shape is left alone so the gate reports it below rather
            // than the transform guessing.
            //
            // A seventh repair is applied last, to the text all six produced: a Kotlin declaration
            // whose *name* is a Java reserved word (`default`, the second component of every
            // `com.example.app.properties.*Value` property class) does not parse as Java, and
            // the parse gate would reject the unit before the compile gate ever saw it. See
            // [ReservedWordNormalizer].
            val text = PlaceholderParameterNormalizer.normalize(
                RawEntrySetNormalizer.normalize(
                    WildcardCaptureNormalizer.normalize(
                        EnumNormalizer.normalize(
                            DefaultArgumentConstructorNormalizer.normalize(
                                ConstructorNormalizer.normalize(raw)
                            )
                        )
                    )
                )
            ).let { ReservedWordNormalizer.normalize(it) }
                .let { InterfaceDefaultSynthesis.synthesize(target.classFile, request.classesRoot, it) }
            val errors = validator.validate(fileNameFor(target.className), text)
            if (errors.isNotEmpty()) {
                val reason = errors.joinToString("; ")
                failures += ConversionFailure(target.className, "VALIDATE", reason)
                dumper?.dump(target.className, "VALIDATE", reason, text)
                continue
            }
            outputs[target.className] = text
        }

        // Compile the complete generated surface together. This is both the faithful build model
        // (generated callees are visible as source immediately) and the performance boundary: one
        // javac task replaces thousands of per-unit compiler startups and settle-pass retries.
        log.info("k2j timing: normalize+parse ${elapsedSeconds(normalizeStarted)}s (${outputs.size} candidates)")
        if (request.compileCheck && outputs.isNotEmpty()) {
            val compileStarted = System.nanoTime()
            applyCompileGate(request, outputs, failures, rawTypeRepairs, dumper)
            log.info("k2j timing: batch compile ${elapsedSeconds(compileStarted)}s (${outputs.size} accepted)")
        }

        val runWarnings = decompilerWarnings()
        runWarnings.forEach { log.warn("decompiler: $it") }

        val written = writer.write(outputs, request.outputRoot)
        val writtenNames = written.map { it.className }.toSet()
        val convertedClassFiles = survey.targets
            .filter { it.className in writtenNames }
            .flatMap { it.classFiles }
            .toSet()

        val decisions = survey.sources.map { mapping ->
            decisionFor(mapping, convertedClassFiles)
        }
        val deletable = decisions.filter { it.deletable }.map { it.source }
        val deleted = if (request.deleteSources) writer.deleteVerifiedSources(deletable.map { Path.of(it) }) else emptyList()

        val manifest = ConversionManifest(
            converted = written,
            failures = failures,
            deletableSources = deletable,
            deletedSources = deleted,
            warnings = runWarnings + rawTypeRepairs,
            sources = decisions
        )
        val manifestPath = writer.writeManifest(manifest, request.outputRoot)
        log.info("manifest: $manifestPath")
        log.info("k2j timing: total ${elapsedSeconds(runStarted)}s")
        lastResult = ConversionResult(manifest, survey)
        return manifest
    }

    /**
     * The deletion rule, in one place: deletable iff the source has at least one class file, all of
     * them were converted into written outputs, and the survey recorded no blocker (an unrepresented
     * declaration such as a `typealias`, or an ambiguous source mapping).
     */
    private fun decisionFor(mapping: SourceMapping, convertedClassFiles: Set<Path>): SourceDecision {
        val missing = mapping.classFiles.filter { it !in convertedClassFiles }
        val deletable = mapping.deletionBlockers.isEmpty() && mapping.classFiles.isNotEmpty() && missing.isEmpty()
        val reason = when {
            mapping.deletionBlockers.isNotEmpty() -> mapping.deletionBlockers.joinToString("; ")
            mapping.classFiles.isEmpty() -> "no class file of this source was mapped to a conversion target"
            missing.isNotEmpty() -> "not all class files were converted and written: " +
                missing.joinToString(", ") { it.fileName.toString() }
            else -> "every class file of this source was converted and written, and no unrepresented declaration remains"
        }
        return SourceDecision(mapping.source.toString(), deletable, reason)
    }

    private fun decompilerWarnings(): List<String> =
        (decompiler as? WarningReporter)?.warnings ?: emptyList()

    /**
     * What the compile check resolves against, in classpath order: the generated tree of this run
     * ([ConversionRequest.outputRoot]) and the trees an earlier run wrote
     * ([ConversionRequest.writtenRoots]) first, then the dependencies the caller supplied
     * (`--compile-classpath` / `k2j { compileClasspath }`), the jars the decompiler used to resolve
     * signatures, and finally the classes root — the compiled classes the units were derived from,
     * which is where a generated unit's siblings (the classes of its own module, including the ones
     * converted in this very run) actually live.
     *
     * The generated roots come first because they *are* the compilation surface a real build has: a
     * converted class is compiled from its generated `.java` there, not read back from the Kotlin class
     * file it came from — and the class file is precisely where a synthetic member (Kotlin's
     * default-argument constructor) is invisible to javac's overload resolution. The checker passes
     * `-Xprefer:source`, so the generated `.java` wins over any `.class` of the same type that a later
     * classpath entry still holds; without that flag javac would prefer the class file whatever the
     * order, and this list would be a no-op. During the main loop [ConversionRequest.outputRoot] is
     * empty for this run (the writer runs after the loop), so nothing there can shadow a class file;
     * [ConversionRequest.writtenRoots] does participate, which is what lets a caller resolve a type a
     * *previous* run already converted.
     *
     * [ConversionRequest.classesRoots] adds *every* classes directory of the module's main source
     * set. The survey needs exactly one root, but a build routinely splits the module's own classes
     * across several output directories (`build/classes/java/main` beside
     * `build/classes/kotlin/main`), and javac resolves against all of them: without the sibling
     * directory, a generated unit that references a class compiled there was rejected with
     * `cannot find symbol` although the `.class` file existed on disk.
     *
     * A [LinkedHashSet] de-duplicates and preserves order, so a caller that supplies only the single
     * `classesRoot` classpath — the CLI, or any request built before `classesRoots` existed — gets the
     * exact list it got before, apart from its own (empty) output root.
     */
    private fun compileClasspathFor(request: ConversionRequest): List<Path> {
        val paths = LinkedHashSet<Path>()
        paths.addAll(request.writtenRoots)
        paths.add(request.outputRoot)
        paths.addAll(request.compileClasspath)
        paths.addAll(request.classpath)
        paths.add(request.classesRoot)
        paths.addAll(request.classesRoots)
        return paths.toList()
    }

    /**
     * Compile every parse-clean unit together, then retry only javac-named raw-type candidates.
     *
     * The final batch is authoritative. A failed proposal is reverted before that batch, and a unit
     * remains in [outputs] only when javac reports no error for its source. Original diagnostics are
     * retained for units whose raw-type proposal did not prove itself.
     */
    private fun applyCompileGate(
        request: ConversionRequest,
        outputs: MutableMap<String, String>,
        failures: MutableList<ConversionFailure>,
        rawTypeRepairs: MutableList<String>,
        dumper: FailureDumper?
    ) {
        val classpath = compileClasspathFor(request)
        val originalText = outputs.toMap()
        val initialErrors = compileChecker.checkAll(originalText, classpath)
        val proposals = linkedMapOf<String, RawTypeFallback.Repair>()

        for ((className, errors) in initialErrors) {
            if (errors.isEmpty()) continue
            RawTypeFallback.repair(fileNameFor(className), originalText.getValue(className), errors)
                ?.let { proposals[className] = it }
        }

        val trial = LinkedHashMap(originalText)
        proposals.forEach { (className, repair) -> trial[className] = repair.text }
        val trialErrors = if (proposals.isEmpty()) initialErrors else compileChecker.checkAll(trial, classpath)

        // A proposal is accepted only when javac reports no error for that unit in the complete tree.
        // Revert failed proposals before the authoritative pass so no other unit can pass against an
        // unaccepted API surface.
        val acceptedRepairs = proposals.filterKeys { trialErrors[it].orEmpty().isEmpty() }
        val finalText = LinkedHashMap(originalText)
        acceptedRepairs.forEach { (className, repair) -> finalText[className] = repair.text }
        val finalErrors = when {
            proposals.isEmpty() -> initialErrors
            acceptedRepairs.size == proposals.size -> trialErrors
            else -> compileChecker.checkAll(finalText, classpath)
        }

        outputs.clear()
        for ((className, text) in finalText) {
            val errors = finalErrors[className].orEmpty()
            if (errors.isEmpty()) {
                outputs[className] = text
                acceptedRepairs[className]?.let { repair ->
                    val first = initialErrors[className].orEmpty().firstOrNull().orEmpty().substringBefore('\n')
                    val note = "raw-type fallback: $className: ${repair.notes.joinToString("; ")}" +
                        " (javac rejected the unit first: $first)"
                    rawTypeRepairs += note
                    log.warn(note)
                }
            } else {
                val reported = if (className in proposals && className !in acceptedRepairs) {
                    initialErrors[className].orEmpty()
                } else {
                    errors
                }
                val reason = reported.joinToString("; ")
                failures += ConversionFailure(className, "COMPILE", reason)
                dumper?.dump(className, "COMPILE", reason, originalText.getValue(className))
            }
        }
    }

    private fun elapsedSeconds(started: Long): String =
        "%.3f".format(java.util.Locale.ROOT, (System.nanoTime() - started) / 1_000_000_000.0)

    private fun fileNameFor(className: String): String = className.substringAfterLast('.') + ".java"
}

/** Manifest plus the survey it was derived from, for callers that want the target detail. */
data class ConversionResult(val manifest: ConversionManifest, val survey: SurveyResult)
