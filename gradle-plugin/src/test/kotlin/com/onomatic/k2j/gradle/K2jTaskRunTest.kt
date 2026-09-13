package com.onomatic.k2j.gradle

import com.onomatic.k2j.core.AsmSurveyor
import com.onomatic.k2j.core.ConversionRequest
import com.onomatic.k2j.core.Decompiler
import com.onomatic.k2j.core.FileSystemWriter
import com.onomatic.k2j.core.JavaValidator
import com.onomatic.k2j.core.Surveyor
import com.onomatic.k2j.core.SurveyResult
import org.gradle.api.GradleException
import org.gradle.api.Project
import org.gradle.testfixtures.ProjectBuilder
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Unit tests of [K2jTask]'s own action logic, over synthetic classes dirs and output roots.
 *
 * These construct the real task (ProjectBuilder) and call its action, so the code the old
 * action test never touched is covered: classes-root selection, the missing-classes failure,
 * output cleaning between runs, manifest-on-failure and the runtime/jar guards.
 * Unlike `:core`'s suite, nothing here exercises core's orchestrator through fakes.
 */
class K2jTaskRunTest {

    /** The compiled Kotlin classes of this very test source set — real class files with kotlin/Metadata. */
    private val kotlinClassesDir: File = codeSourceOf(K2jTaskRunTest::class.java)

    /** A compiled Java-only directory — real class files without kotlin/Metadata. */
    private val javaClassesDir: File =
        // Loaded reflectively: KGP compiles Kotlin before Java, so Kotlin cannot reference the
        // Java-only fixture in the same source set.
        codeSourceOf(Class.forName("com.onomatic.k2j.gradle.JavaOnlyFixture"))

    // -- BLOCKER 1: mixed Java+Kotlin modules ------------------------------------------------

    @Test
    fun multipleKotlinRootsAreMergedIntoASingleStagingRoot() {
        // A module whose Kotlin output is split across two directories: the frozen ConversionRequest
        // takes one classesRoot, so the task must stage a merged copy rather than silently convert
        // only the first root's classes.
        val rootA = tempDir("k2j-root-a")
        val rootB = tempDir("k2j-root-b")
        copyClass(kotlinClassesDir, rootA, "com/onomatic/k2j/gradle/K2jTaskRunTest.class")
        copyClass(kotlinClassesDir, rootB, "com/onomatic/k2j/gradle/KotlinClassDetectorTest.class")
        val stagingParent = tempDir("k2j-staging-parent")
        val staging = File(stagingParent, "k2j-classes-staging")

        val (_, task) = taskFor(tempDir("k2j-project"))
        val outputRoot = tempDir("k2j-out")
        val surveyor = RecordingSurveyor()
        task.extension.get().surveyor.set(surveyor)
        task.extension.get().decompiler.set(MarkerDecompiler(surveyor, "v1"))
        task.extension.get().validator.set(AcceptingValidator())
        task.extension.get().writer.set(FileSystemWriter())
        task.outputRoot.set(outputRoot)
        task.classesDirs.from(javaClassesDir, rootA, rootB)

        // ClassRoots only consults the staging factory when several roots carry Kotlin classes.
        val selection = ClassRoots.select(task.classesDirs.files) { staging }
        assertTrue(selection.isMerged, "expected a merged selection, got ${selection.root}")
        assertEquals(setOf(rootA, rootB), selection.mergedFrom.toSet())
        assertTrue(File(staging, "com/onomatic/k2j/gradle/K2jTaskRunTest.class").isFile)
        assertTrue(File(staging, "com/onomatic/k2j/gradle/KotlinClassDetectorTest.class").isFile)

        // And the task converts from the staged root: both roots' classes become targets.
        task.run()
        assertEquals(
            "k2j-classes-staging",
            surveyor.lastRequest.classesRoot.toFile().name,
            "the task must survey its own staged merge root, not one of the inputs"
        )
        val names = surveyor.lastResult.targets.map { it.className }.toSet()
        assertTrue(names.contains("com.onomatic.k2j.gradle.K2jTaskRunTest"), names.toString())
        assertTrue(names.contains("com.onomatic.k2j.gradle.KotlinClassDetectorTest"), names.toString())
        assertTrue(generatedJava(outputRoot).size == surveyor.lastResult.targets.size)
    }

    @Test
    fun mixedRoots() {
        val outputRoot = tempDir("k2j-out")
        val (_, task) = taskFor(tempDir("k2j-project"))
        val surveyor = RecordingSurveyor()
        val decompiler = MarkerDecompiler(surveyor, "v1")
        task.extension.get().surveyor.set(surveyor)
        task.extension.get().decompiler.set(decompiler)
        task.extension.get().validator.set(AcceptingValidator())
        task.extension.get().writer.set(FileSystemWriter())
        task.outputRoot.set(outputRoot)
        // Gradle orders main.output.classesDirs as [java, kotlin]; the java dir is the trap.
        task.classesDirs.from(javaClassesDir, kotlinClassesDir)

        assertTrue(javaClassesDir.isDirectory, "fixture: java classes dir should exist ($javaClassesDir)")
        assertFalse(
            KotlinClassDetector.hasKotlinMetadata(javaClassesDir.listFiles()!!.first()),
            "fixture: the Java-only dir must not contain kotlin/Metadata classes"
        )

        task.run()

        assertEquals(
            kotlinClassesDir.absolutePath,
            surveyor.lastRequest.classesRoot.toFile().absolutePath,
            "k2j must survey the classes root that actually contains Kotlin classes"
        )
        assertTrue(
            surveyor.lastResult.targets.isNotEmpty(),
            "the Kotlin classes dir must yield conversion targets"
        )
        assertTrue(
            generatedJava(outputRoot).isNotEmpty(),
            "the Kotlin classes must actually be converted (>0), not silently reported as 0"
        )
        assertEquals(
            surveyor.lastResult.targets.size,
            generatedJava(outputRoot).size,
            "every surveyed Kotlin class should produce one unit"
        )
    }

    @Test
    fun javaOnlyRootsFailLoudly() {
        val (_, task) = taskFor(tempDir("k2j-project"))
        task.outputRoot.set(tempDir("k2j-out"))
        task.classesDirs.from(javaClassesDir)

        val failure = assertFailsWithK2j(task)

        assertTrue(
            failure.message!!.contains("no Kotlin classes"),
            "expected a 'no Kotlin classes' k2j failure, got: ${failure.message}"
        )
        assertFalse(failure.message!!.contains("NoSuchElement"))
        assertFalse(failure.message!!.contains("no element matching the predicate"))
    }

    // -- MAJOR 3: no classes dir at all -------------------------------------------------------

    @Test
    fun missingClassesDirsExplainWhatToDo() {
        val (project, task) = taskFor(tempDir("k2j-project"))
        task.outputRoot.set(tempDir("k2j-out"))
        val missing = File(project.projectDir, "build/classes/kotlin/main")
        task.classesDirs.from(missing)

        val failure = assertFailsWithK2j(task)
        val message = failure.message!!

        assertTrue(
            message.contains("no compiled main classes found - is the Kotlin JVM plugin applied and did :classes run?"),
            "expected the actionable no-classes message, got: $message"
        )
        assertTrue(message.contains("deleteSources"), "message must explain the deleteSources interaction: $message")
        assertTrue(message.contains(missing.absolutePath), "message must name the directory it looked in: $message")
        assertFalse(message.contains("NoSuchElement"))
        assertFalse(message.contains("no element matching the predicate"))
    }

    @Test
    fun noClassesDirsAtAllExplainWhatToDo() {
        val (_, task) = taskFor(tempDir("k2j-project"))
        task.outputRoot.set(tempDir("k2j-out"))
        // No classesDirs wired at all: what an unapplied Kotlin plugin produces today.

        val failure = assertFailsWithK2j(task)

        assertTrue(failure.message!!.contains("no compiled main classes found"))
        assertTrue(failure.message!!.contains("never applied"))
    }

    @Test
    fun kotlinPluginAbsentIsReportedExplicitly() {
        val (_, task) = taskFor(tempDir("k2j-project"))
        task.outputRoot.set(tempDir("k2j-out"))
        task.classesDirs.from(kotlinClassesDir)
        task.kotlinPluginApplied.set(false)

        val failure = assertFailsWithK2j(task)

        assertTrue(
            failure.message!!.contains("Kotlin JVM plugin is not applied"),
            "expected the explicit plugin check, got: ${failure.message}"
        )
        assertTrue(failure.message!!.contains("org.jetbrains.kotlin.jvm"))
    }

    // -- BLOCKER 2: the task must be repeatable ----------------------------------------------

    @Test
    fun secondRunSucceedsAndReplacesStaleOutput() {
        val projectDir = tempDir("k2j-project")
        val outputRoot = tempDir("k2j-out")
        val (_, task) = taskFor(projectDir)
        val surveyor = RecordingSurveyor()
        val decompiler = MarkerDecompiler(surveyor, "v1")
        task.extension.get().surveyor.set(surveyor)
        task.extension.get().decompiler.set(decompiler)
        task.extension.get().validator.set(AcceptingValidator())
        task.extension.get().writer.set(FileSystemWriter())
        task.outputRoot.set(outputRoot)
        task.classesDirs.from(javaClassesDir, kotlinClassesDir)

        task.run()
        val firstOutput = generatedJava(outputRoot)
        assertTrue(firstOutput.isNotEmpty())
        assertTrue(firstOutput.all { it.readText().contains("// v1") })

        // A source edit between the runs, plus a stale unit from a class that disappeared.
        decompiler.marker = "v2"
        val stale = File(outputRoot, "accept/gone/Stale.java")
        stale.parentFile.mkdirs()
        stale.writeText("// stale\nclass Stale {}\n")

        task.run() // would previously die with 'refusing to overwrite existing file'

        assertFalse(stale.isFile, "a .java from a class that disappeared must not survive: ${stale.absolutePath}")
        val secondOutput = generatedJava(outputRoot)
        assertTrue(secondOutput.isNotEmpty())
        assertTrue(
            secondOutput.all { it.readText().contains("// v2") },
            "every generated unit must reflect the edit, not the previous run"
        )
    }

    // -- MAJOR 4: a failed run still leaves the manifest it points at -------------------------

    @Test
    fun failedRunWritesTheManifestItNames() {
        val outputRoot = tempDir("k2j-out")
        val (_, task) = taskFor(tempDir("k2j-project"))
        task.extension.get().surveyor.set(RecordingSurveyor())
        task.extension.get().decompiler.set(ThrowingDecompiler())
        task.extension.get().validator.set(AcceptingValidator())
        task.extension.get().writer.set(FileSystemWriter())
        task.outputRoot.set(outputRoot)
        task.classesDirs.from(javaClassesDir, kotlinClassesDir)

        val failure = assertFailsWithK2j(task)
        val manifestPath = Regex("""manifest: ([^)]+)\)""").find(failure.message!!)?.groupValues?.get(1)

        assertTrue(manifestPath != null, "failure message must name a manifest: ${failure.message}")
        val manifest = File(manifestPath)
        assertTrue(
            manifest.isFile,
            "the manifest named in the failure must exist on disk: ${manifest.absolutePath}"
        )
        assertTrue(manifest.readText().contains("\"success\": false"), manifest.readText())
        assertTrue(
            !failure.message!!.contains("\\k2j/k2j-manifest.json"),
            "the manifest path must not mix separators: ${failure.message}"
        )
        assertEquals(File(outputRoot, K2jTask.MANIFEST_NAME).absolutePath, manifest.absolutePath)
    }

    @Test
    fun successfulRunWritesTheManifestNextToTheSources() {
        val outputRoot = tempDir("k2j-out")
        val (_, task) = taskFor(tempDir("k2j-project"))
        val surveyor = RecordingSurveyor()
        task.extension.get().surveyor.set(surveyor)
        task.extension.get().decompiler.set(MarkerDecompiler(surveyor, "v1"))
        task.extension.get().validator.set(AcceptingValidator())
        task.extension.get().writer.set(FileSystemWriter())
        task.outputRoot.set(outputRoot)
        task.classesDirs.from(javaClassesDir, kotlinClassesDir)

        task.run()

        val manifest = File(outputRoot, K2jTask.MANIFEST_NAME)
        assertTrue(manifest.isFile, "expected the manifest at ${manifest.absolutePath}")
        assertTrue(manifest.readText().contains("\"success\": true"))
    }

    // -- MAJOR 5: artifact/runtime guards -----------------------------------------------------

    @Test
    fun missingExplicitDecompilerJarIsReportedNotForked() {
        val (project, task) = taskFor(tempDir("k2j-project"))
        task.outputRoot.set(tempDir("k2j-out"))
        task.classesDirs.from(kotlinClassesDir)
        task.decompilerJar.from(File(project.projectDir, "definitely-not-here.jar"))

        val failure = assertFailsWithK2j(task)

        assertTrue(failure.message!!.contains("decompiler jar not found"))
        assertTrue(failure.message!!.contains("definitely-not-here.jar"))
    }

    @Test
    fun checksumMismatchStopsTheRunWithTheExpectedAndActualDigest() {
        val (_, task) = taskFor(tempDir("k2j-project"))
        task.outputRoot.set(tempDir("k2j-out"))
        task.classesDirs.from(kotlinClassesDir)
        task.decompilerJarSha256.set("0000000000000000000000000000000000000000000000000000000000000000")

        val failure = assertFailsWithK2j(task)

        assertTrue(
            failure.message!!.contains("decompiler jar checksum mismatch"),
            "expected a checksum failure, got: ${failure.message}"
        )
        assertTrue(failure.message!!.contains("expected sha256"))
        assertTrue(failure.message!!.contains("actual   sha256"))
    }

    @Test
    fun runtimeBelowJava25IsRejectedWithTheReason() {
        val (_, task) = taskFor(tempDir("k2j-project"))
        task.outputRoot.set(tempDir("k2j-out"))
        task.classesDirs.from(kotlinClassesDir)
        // The test JVM runs on the build toolchain (Java 21), whose `release` file is a real
        // fixture: no machine-specific path is involved.
        val buildRuntime = File(System.getProperty("java.home"))
        val buildMajor = JavaRuntime.majorVersion(buildRuntime) ?: return
        if (buildMajor >= K2jExtension.REQUIRED_JAVA_MAJOR) return
        task.runtimeJavaHome.set(buildRuntime)

        val failure = assertFailsWithK2j(task)

        assertTrue(
            failure.message!!.contains("needs Java 25"),
            "expected a Java-25 requirement message, got: ${failure.message}"
        )
    }

    // -- helpers ------------------------------------------------------------------------------

    private fun taskFor(projectDir: File): Pair<Project, K2jTask> {
        val project = ProjectBuilder.builder().withProjectDir(projectDir).build()
        project.extensions.create("k2j", K2jExtension::class.java)
        val task = project.tasks.register("k2j", K2jTask::class.java).get()
        task.extension.set(project.extensions.getByType(K2jExtension::class.java))
        task.packages.set(emptyList())
        task.deleteSources.set(false)
        return project to task
    }

    private fun assertFailsWithK2j(task: K2jTask): GradleException {
        val thrown = try {
            task.run()
            null
        } catch (t: GradleException) {
            t
        }
        assertTrue(thrown != null, "expected the task to fail, but it succeeded")
        assertTrue(
            thrown.message!!.startsWith("k2j: "),
            "every k2j failure must be k2j-prefixed, got: ${thrown.message}"
        )
        return thrown
    }

    private fun generatedJava(outputRoot: File): List<File> =
        outputRoot.walkTopDown().filter { it.isFile && it.extension == "java" }.toList()

    private fun codeSourceOf(type: Class<*>): File =
        File(type.protectionDomain.codeSource.location.toURI()).canonicalFile

    /** Copies one compiled class into [targetRoot] under its package path, creating parents. */
    private fun copyClass(sourceRoot: File, targetRoot: File, relativePath: String) {
        val source = File(sourceRoot, relativePath)
        assertTrue(source.isFile, "fixture missing: ${source.absolutePath}")
        val target = File(targetRoot, relativePath)
        target.parentFile.mkdirs()
        source.copyTo(target, overwrite = true)
    }

    private fun tempDir(prefix: String): File = Files.createTempDirectory(prefix).toFile().canonicalFile

    /** Delegates to the real ASM surveyor but records the request/result the task built. */
    private class RecordingSurveyor(
        private val delegate: Surveyor = AsmSurveyor()
    ) : Surveyor {
        lateinit var lastRequest: ConversionRequest
        lateinit var lastResult: SurveyResult

        override fun survey(request: ConversionRequest): SurveyResult {
            lastRequest = request
            return delegate.survey(request).also { lastResult = it }
        }
    }

    /** Produces one unit per surveyed target, stamped with a marker that changes between runs. */
    private class MarkerDecompiler(
        private val surveyor: RecordingSurveyor,
        var marker: String
    ) : Decompiler {
        override fun decompile(classFiles: List<Path>, classpath: List<Path>): Map<String, String> =
            surveyor.lastResult.targets.associate { target ->
                target.className to "// $marker\nclass ${target.className.substringAfterLast('.')} {}\n"
            }
    }

    private class ThrowingDecompiler : Decompiler {
        override fun decompile(classFiles: List<Path>, classpath: List<Path>): Map<String, String> =
            throw IllegalStateException("forked decompiler exited 1")
    }

    private class AcceptingValidator : JavaValidator {
        override fun validate(fileName: String, javaSource: String) = emptyList<String>()
    }
}
