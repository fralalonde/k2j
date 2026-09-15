package org.example.k2j.gradle

import org.gradle.api.GradleException
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Picks the compiled-classes root the conversion must survey.
 *
 * Gradle orders `main.output.classesDirs` as `[build/classes/java/main, build/classes/kotlin/main]`,
 * so taking the first existing directory silently surveys the *Java* output of any module that has
 * at least one Java source: the survey finds zero `kotlin/Metadata` classes and the run "succeeds"
 * with `converted 0 class(es)` while the Kotlin output sits right next to it. This type never
 * guesses: it inspects the candidates and only accepts a root that actually contains Kotlin classes.
 *
 * `ConversionRequest` takes a single `classesRoot`, so when more than one candidate carries Kotlin
 * classes the metadata-bearing roots are copied into one staging directory (relative package layout
 * preserved) and that directory is surveyed. (`ConversionRequest.classesRoots` now exists, but only
 * for the *compile gate's* classpath — see [compileClasspathRoots]; making the survey read a list is
 * still the change that would remove the staging copy, and it is not a correctness defect: every
 * Kotlin-bearing root is already merged into the surveyed root.)
 */
internal object ClassRoots {

    /** The chosen root, plus the roots it was merged from when a single root could not express the inputs. */
    data class Selection(
        val root: File,
        val mergedFrom: List<File> = emptyList()
    ) {
        val isMerged: Boolean get() = mergedFrom.isNotEmpty()
    }

    /** Candidates in wiring order, keeping only directories, de-duplicated, order preserved. */
    fun existingRoots(classesDirs: Iterable<File>): List<File> =
        classesDirs.filter { it.isDirectory }.distinct()

    /**
     * Every classes directory the compile gate must resolve against: **all** the declared classes
     * dirs of the source set, not just the one [select] chose for the survey.
     *
     * The survey needs one root (the frozen `ConversionRequest.classesRoot`), but resolution does
     * not: Gradle's `main.output.classesDirs` is `[build/classes/java/main, build/classes/kotlin/main]`
     * for any module with at least one Java source, and the module's *own* Java classes (annotations,
     * utilities) live in the first of those while every Kotlin class lives in the second. A generated
     * unit that references one of them is rejected by the compile gate with `cannot find symbol`
     * unless the sibling directory is on the classpath, so the task passes this list as
     * `ConversionRequest.classesRoots`.
     *
     * This is deliberately the same notion of "the module's classes dirs" that [select] inspects —
     * every declared directory, existing only, de-duplicated — so a Kotlin class compiled into *any*
     * of them is both a conversion target (via selection/merge) and resolvable by the gate.
     */
    fun compileClasspathRoots(classesDirs: Iterable<File>): List<File> = existingRoots(classesDirs)

    /** True when [dir] contains at least one `.class` file carrying `kotlin/Metadata`. */
    fun containsKotlinClass(dir: File): Boolean =
        dir.walkTopDown()
            .any { it.isFile && it.extension == "class" && KotlinClassDetector.hasKotlinMetadata(it) }

    /**
     * @param classesDirs the task's declared classes dirs, in Gradle's order
     * @param stagingDir factory for the merge directory; only consulted when more than one candidate
     *   carries Kotlin classes
     * @throws GradleException with a `k2j:`-prefixed, actionable message when no candidate can be used
     */
    fun select(classesDirs: Iterable<File>, stagingDir: (() -> File)? = null): Selection {
        val declared = classesDirs.toList()
        val candidates = existingRoots(declared)
        if (candidates.isEmpty()) {
            throw GradleException(noClassesMessage(declared))
        }
        val kotlinRoots = candidates.filter { containsKotlinClass(it) }
        if (kotlinRoots.isEmpty()) {
            throw GradleException(noKotlinClassesMessage(candidates))
        }
        if (kotlinRoots.size == 1) {
            return Selection(kotlinRoots.single())
        }
        val staging = stagingDir?.invoke()
            ?: throw GradleException(
                "k2j: ${kotlinRoots.size} classes directories contain Kotlin classes " +
                    "(${kotlinRoots.joinToString(", ") { it.absolutePath }}) but no staging directory was provided. " +
                    "Report this: k2j needs one classes root and could not merge the inputs."
            )
        mergeInto(staging, kotlinRoots)
        return Selection(staging, kotlinRoots)
    }

    /** Copies every `.class` file of [roots] into [staging], preserving the relative package path. */
    private fun mergeInto(staging: File, roots: List<File>) {
        staging.deleteRecursively()
        Files.createDirectories(staging.toPath())
        for (root in roots) {
            val rootPath = root.toPath()
            root.walkTopDown()
                .filter { it.isFile && it.extension == "class" }
                .forEach { source ->
                    val relative = rootPath.relativize(source.toPath())
                    val target = staging.toPath().resolve(relative)
                    if (Files.exists(target)) return@forEach // earlier root wins
                    Files.createDirectories(target.parent)
                    Files.copy(source.toPath(), target, StandardCopyOption.COPY_ATTRIBUTES)
                }
        }
    }

    private fun noClassesMessage(declared: List<File>): String = buildString {
        append("k2j: no compiled main classes found - is the Kotlin JVM plugin applied and did :classes run?")
        append("\nLooked for these classes directories (none exist): ")
        append(declared.joinToString(", ") { it.absolutePath }.ifEmpty { "<none declared - the Kotlin JVM plugin was never applied>" })
        append("\nCheck, in order:")
        append("\n  1. the plugin id 'org.jetbrains.kotlin.jvm' is applied to this project;")
        append("\n  2. the ':classes' task ran (the k2j task already dependsOn it) - an empty source set produces no classes directory;")
        append("\n  3. if an earlier run used 'deleteSources = true', it deleted the .kt sources it converted,")
        append("\n     so there is nothing left to convert - restore the sources (e.g. from VCS) before re-running k2j.")
    }

    private fun noKotlinClassesMessage(candidates: List<File>): String = buildString {
        append("k2j: no Kotlin classes (kotlin/Metadata) found in any compiled classes directory.")
        append("\nSearched: ")
        append(candidates.joinToString(", ") { it.absolutePath })
        append("\nk2j converts Kotlin bytecode; Java-only output has nothing to convert.")
        append("\nIf this module used to have Kotlin sources and an earlier run used 'deleteSources = true',")
        append(" those sources were already converted and deleted - restore them (e.g. from VCS) before re-running k2j.")
    }
}
