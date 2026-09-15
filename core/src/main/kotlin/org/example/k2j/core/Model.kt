package org.example.k2j.core

import java.nio.file.Path

/**
 * One conversion run's inputs and knobs. Everything is a path or a flag — no IDE, no Gradle types.
 *
 * @param classesRoot directory of compiled `.class` files (the build's own output)
 * @param classpath jars the classes were compiled against (decompiler needs them to resolve signatures)
 * @param outputRoot where generated `.java` files are written
 * @param packages restrict targets to these packages; empty means every Kotlin class under [classesRoot]
 * @param deleteSources when true, survey sources whose classes were all converted+validated are deleted
 * @param sourceRoots `.kt` roots the survey maps class files back to (deletion needs them)
 * @param dumpFailures when set, the generated text of every unit that failed a phase is written into
 *   this directory (one file per class plus a `.failure.txt` sidecar naming the phase and reason).
 *   Null — the default — writes nothing.
 * @param compileCheck when true, every unit that passes the parse gate is also compiled with
 *   `javax.tools` before it is written; a unit javac rejects becomes a per-class failure with its
 *   javac diagnostic and is not written. False — the default — keeps the gate parse-only.
 * @param compileClasspath dependencies the compile check resolves against (`--compile-classpath`).
 *   Only read when [compileCheck] is true.
 * @param classesRoots every compiled-classes directory of the module being converted — Gradle's
 *   `main.output.classesDirs`, i.e. the Java output *and* the Kotlin output. The survey still reads
 *   the single [classesRoot], but the compile gate resolves against all of these as well: a module's
 *   classes are routinely split across several output directories, and a unit that references a type
 *   sitting in a sibling directory (a Java annotation in `build/classes/java/main` referenced from
 *   the Kotlin output in `build/classes/kotlin/main`) used to be rejected with `cannot find symbol`
 *   for a class file that was right there on disk. Empty — the default — leaves the compile gate's
 *   classpath exactly as it was: [compileClasspath] plus [classpath] plus [classesRoot].
 * @param writtenRoots roots that already hold units this pipeline wrote in an *earlier* run — another
 *   module converted before this one, or a tree a previous invocation left behind. The compile gate
 *   resolves a reference against the generated `.java` in these roots rather than against the class
 *   file the type was derived from, which is the surface a real build compiles. [outputRoot] — the
 *   tree *this* run writes — is always on that list, so a caller can also be checked against it.
 *   Empty — the default — adds nothing.
 */
data class ConversionRequest(
    val classesRoot: Path,
    val classpath: List<Path>,
    val outputRoot: Path,
    val packages: List<String> = emptyList(),
    val deleteSources: Boolean = false,
    val sourceRoots: List<Path> = emptyList(),
    val dumpFailures: Path? = null,
    val compileCheck: Boolean = false,
    val compileClasspath: List<Path> = emptyList(),
    val classesRoots: List<Path> = emptyList(),
    val writtenRoots: List<Path> = emptyList()
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
