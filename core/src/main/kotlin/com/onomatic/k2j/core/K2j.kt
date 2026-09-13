package com.onomatic.k2j.core

import java.nio.file.Path

/**
 * The orchestrator. Wires survey → decompile → normalise → validate → write, and derives the
 * deletable-source list from the survey manifest rather than from a heuristic. Per-class failure
 * isolation: one bad class never aborts the run, it becomes a failure entry with its reason.
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
    private val log: RunLog
) : Converter {

    var lastResult: ConversionResult? = null
        private set

    override fun convert(request: ConversionRequest): ConversionManifest {
        val survey = surveyor.survey(request)
        if (survey.targets.isEmpty()) {
            log.warn("no Kotlin classes matched ${request.packages.ifEmpty { listOf("<all>") }} under ${request.classesRoot}")
        }
        val failures = mutableListOf<ConversionFailure>()
        val outputs = linkedMapOf<String, String>()

        // Decompile in one FernFlower context per run: the decompiler resolves cross-class signatures
        // better with the whole target set, and one context is one deterministic pass. Every class
        // file of a unit (outer + `Outer$Nested` siblings) is fed so the generated unit contains the
        // nested types.
        val decompiled = try {
            decompiler.decompile(survey.targets.flatMap { it.classFiles }, request.classpath)
        } catch (t: Throwable) {
            log.error("decompilation failed for the whole target set", t)
            val failedManifest = ConversionManifest(
                converted = emptyList(),
                failures = listOf(ConversionFailure("*", "DECOMPILE", t.message ?: t.javaClass.simpleName)),
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

        for (target in survey.targets) {
            val raw = decompiled[target.className]
            if (raw == null) {
                failures += ConversionFailure(target.className, "DECOMPILE", "the decompiler produced no unit for this class")
                continue
            }
            // Kotlin emits the constructor null-check before the super()/this(...) delegation, which
            // javac rejects; hoist the delegation before the parse gate so the unit is valid Java.
            val text = ConstructorNormalizer.normalize(raw)
            val errors = validator.validate(fileNameFor(target.className), text)
            if (errors.isNotEmpty()) {
                failures += ConversionFailure(target.className, "VALIDATE", errors.joinToString("; "))
                continue
            }
            outputs[target.className] = text
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
            warnings = runWarnings,
            sources = decisions
        )
        val manifestPath = writer.writeManifest(manifest, request.outputRoot)
        log.info("manifest: $manifestPath")
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

    private fun fileNameFor(className: String): String = className.substringAfterLast('.') + ".java"
}

/** Manifest plus the survey it was derived from, for callers that want the target detail. */
data class ConversionResult(val manifest: ConversionManifest, val survey: SurveyResult)
