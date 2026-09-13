package com.onomatic.k2j.core

import java.nio.file.Path

/**
 * One conversion run's inputs and knobs. Everything is a path or a flag — no IDE, no Gradle types.
 *
 * @param classesRoot directory of compiled `.class` files (the build's own output)
 * @param classpath jars the classes were compiled against (decompiler needs them to resolve signatures)
 * @param outputRoot where generated `.java` files are written
 * @param packages restrict targets to these packages; empty means every Kotlin class under [classesRoot]
 * @param deleteSources when true, survey sources whose classes were all converted+validated are deleted
 */
data class ConversionRequest(
    val classesRoot: Path,
    val classpath: List<Path>,
    val outputRoot: Path,
    val packages: List<String> = emptyList(),
    val deleteSources: Boolean = false,
    val sourceRoots: List<Path> = emptyList()
)

/** The run's verdict, written as JSON next to [ConversionRequest.outputRoot]. */
data class ConversionManifest(
    val converted: List<ConvertedClass>,
    val failures: List<ConversionFailure>,
    val deletableSources: List<String>,
    val deletedSources: List<String>,
    val warnings: List<String>,
    /**
     * One entry per source the survey mapped to a target, whether or not it was deletable. Every
     * source that was NOT deleted carries the [SourceDecision.reason] that blocked it, so a user can
     * always see why a `.kt` file survived.
     */
    val sources: List<SourceDecision> = emptyList()
) {
    val success: Boolean get() = failures.isEmpty()
}

data class ConvertedClass(val className: String, val outputPath: String)

data class ConversionFailure(val className: String, val phase: String, val message: String)

/**
 * The deletion verdict for one source file.
 *
 * @param source the `.kt` path considered for deletion
 * @param deletable true only when every declaration of [source] is represented in the written,
 *   validated output (see `Surveyor` for the invariant)
 * @param reason human-readable justification; for a deletable source it records the positive proof,
 *   for a non-deletable one the specific blocker
 */
data class SourceDecision(val source: String, val deletable: Boolean, val reason: String)
