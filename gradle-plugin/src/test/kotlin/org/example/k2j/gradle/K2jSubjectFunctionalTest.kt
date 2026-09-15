package org.example.k2j.gradle

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Functional tests over real subject projects, run through Gradle TestKit.
 *
 * The subject in [mixedKotlinJavaSubjectConvertsAndIsRepeatable] deliberately contains a `.java`
 * source next to the `.kt` source: that makes `build/classes/java/main` exist and makes Gradle
 * order `main.output.classesDirs` as `[java, kotlin]`, which is exactly the shape that used to
 * produce a silent `converted 0 class(es)` success (BLOCKER 1) and a run-once task (BLOCKER 2).
 */
class K2jSubjectFunctionalTest {

    private val pin = K2jExtension.VENDORED_DECOMPILER_JAR_SHA256

    private fun mixedBuildScript() = """
        plugins {
            kotlin("jvm") version "2.4.0"
            id("org.example.k2j")
        }
        repositories { mavenCentral() }
        kotlin { jvmToolchain(21) }
        k2j { }
        ${TestKitSupport.printWiringTask}
    """.trimIndent()

    private fun singleKt(version: String) = """
        package accept.plain

        class Single {
            fun label(): String = "$version"
        }
    """.trimIndent()

    @Test
    fun mixedKotlinJavaSubjectConvertsAndIsRepeatable() {
        val projectDir = TestKitSupport.tempProject(
            "k2j-mixed-subject",
            mixedBuildScript(),
            mapOf(
                "src/main/kotlin/accept/plain/Single.kt" to singleKt("v1"),
                "src/main/java/accept/plain/JavaHelper.java" to """
                    package accept.plain;

                    public class JavaHelper {
                        public String tag() { return "java"; }
                    }
                """.trimIndent()
            )
        )

        // ---- first run ------------------------------------------------------------------
        val first = TestKitSupport.run(projectDir, "printK2jWiring", "k2j").output
        println("===== FIRST RUN OUTPUT =====")
        println(first)

        // The trap is real: the Java classes dir exists and comes first in Gradle's order.
        val classesDirLine = first.lineSequence().first { it.startsWith("K2J_CLASSES_DIR=") }
        assertTrue(classesDirLine.contains("classes${sep}java${sep}main"), classesDirLine)
        assertTrue(classesDirLine.contains("classes${sep}kotlin${sep}main"), classesDirLine)

        assertEquals("K2J_KOTLIN_PLUGIN=true", first.lineSequence().first { it.startsWith("K2J_KOTLIN_PLUGIN=") })
        assertEquals("K2J_JAR_SHA=$pin", first.lineSequence().first { it.startsWith("K2J_JAR_SHA=") })
        // The runtime is resolved by probe, never from a transform cache or a hard-coded home.
        assertTrue(!first.contains("transforms" + File.separatorChar), "runtime must not come from a transform cache")
        val runtimeLine = first.lineSequence().first { it.startsWith("K2J_RUNTIME=") }
        assertTrue(!runtimeLine.endsWith("=unset"), "a Java runtime must be resolved: $runtimeLine")
        assertTrue(
            first.lineSequence().first { it.startsWith("K2J_RUNTIME_EXE=") }.endsWith("java.exe"),
            "the runtime launcher must be a real java executable"
        )
        // sourceRoots is scoped to the Kotlin source dir: editing src/main/java must not invalidate k2j.
        val sourceRoots = first.lineSequence().filter { it.startsWith("K2J_SOURCE_ROOT=") }.toList()
        assertEquals(1, sourceRoots.size, "expected exactly the Kotlin source root, got $sourceRoots")
        assertTrue(
            sourceRoots.single().replace('\\', '/').endsWith("src/main/kotlin"),
            "sourceRoots must be the Kotlin source dir, was ${sourceRoots.single()}"
        )

        // BLOCKER 1: the Kotlin classes are converted, and the count is not silently zero.
        val converted = convertedCount(first)
        assertTrue(converted > 0, "k2j must convert the Kotlin classes, output said: $converted")
        val single = projectDir.resolve("build/k2j/accept/plain/Single.java")
        assertTrue(single.isFile, "expected ${single.path}")
        assertTrue(single.readText().contains("class Single"), single.readText())
        assertTrue(single.readText().contains("v1"), single.readText())
        val manifest = projectDir.resolve("build/k2j/k2j-manifest.json")
        assertTrue(manifest.isFile, "expected ${manifest.path}")
        assertTrue(manifest.readText().contains("\"success\": true"), manifest.readText())
        assertTrue(manifest.readText().contains("accept.plain.Single"), manifest.readText())

        // ---- second run, with a source edit and a stale unit ------------------------------
        projectDir.resolve("src/main/kotlin/accept/plain/Single.kt").writeText(singleKt("v2"))
        val stale = projectDir.resolve("build/k2j/accept/plain/Stale.java")
        stale.parentFile.mkdirs()
        stale.writeText("// stale\nclass Stale {}\n")

        val second = TestKitSupport.run(projectDir, "k2j").output
        println("===== SECOND RUN OUTPUT =====")
        println(second)

        // BLOCKER 2: no 'refusing to overwrite existing file', and the output reflects the edit.
        assertFalse(
            second.contains("refusing to overwrite"),
            "the second run must not hit core's overwrite guard:\n$second"
        )
        assertEquals("v2" in single.readText(), true, single.readText())
        assertTrue(single.readText().contains("v2"), "generated unit must reflect the source edit:\n${single.readText()}")
        assertFalse(stale.isFile, "a .java for a class that disappeared must not survive: ${stale.path}")
        assertTrue(convertedCount(second) > 0, "the second run must still convert classes:\n$second")
    }

    @Test
    fun aUnitReferencingAClassFromTheOtherClassesDirectoryCompiles() {
        // The target module shape, in miniature: the Kotlin source lives under src/main/java, so KGP
        // compiles it into build/classes/kotlin/main while its Java neighbour is compiled by javac
        // into build/classes/java/main. Two classes directories, one source set. The generated unit
        // for the Kotlin class references the Java class from the *other* directory, so it only
        // compiles when every classes dir of the source set is on the compile gate's classpath.
        val projectDir = TestKitSupport.tempProject(
            "k2j-sibling-classes-dir",
            """
                plugins {
                    kotlin("jvm") version "2.4.0"
                    id("org.example.k2j")
                }
                repositories { mavenCentral() }
                kotlin { jvmToolchain(21) }
                k2j { }
            """.trimIndent(),
            mapOf(
                "src/main/java/accept/mixed/JavaHelper.java" to """
                    package accept.mixed;

                    public class JavaHelper {
                        public String tag() { return "java"; }
                    }
                """.trimIndent(),
                // A Kotlin source under src/main/java: the shape that splits one module's classes
                // across two output directories and that triggers this defect.
                "src/main/java/accept/uses/UsesHelper.kt" to """
                    package accept.uses

                    import accept.mixed.JavaHelper

                    class UsesHelper {
                        private val helper: JavaHelper = JavaHelper()

                        fun tag(): String = helper.tag()
                    }
                """.trimIndent()
            )
        )

        val output = TestKitSupport.run(projectDir, "k2j", "--compile-check").output
        println("===== kotlin source in src/main/java, --compile-check =====")
        println(output)

        // The two outputs really are distinct classes directories of the one source set: the Java
        // class is in build/classes/java/main, the Kotlin class in build/classes/kotlin/main.
        assertTrue(
            projectDir.resolve("build/classes/java/main/accept/mixed/JavaHelper.class").isFile,
            "fixture: the Java source must compile into build/classes/java/main"
        )
        assertTrue(
            projectDir.resolve("build/classes/kotlin/main/accept/uses/UsesHelper.class").isFile,
            "fixture: the Kotlin source under src/main/java must compile into build/classes/kotlin/main"
        )

        // The generated unit resolves the Java class from the other directory and passes the gate.
        val generated = projectDir.resolve("build/k2j/accept/uses/UsesHelper.java")
        assertTrue(
            generated.isFile,
            "a unit that references a class from the other classes dir must compile and be written: ${generated.path}\n$output"
        )
        assertTrue(
            generated.readText().contains("JavaHelper"),
            "the generated unit must reference the class from the other classes dir:\n${generated.readText()}"
        )
        assertTrue(convertedCount(output) >= 1, output)
        assertTrue(
            projectDir.resolve("build/k2j/k2j-manifest.json").readText().contains("\"success\": true"),
            projectDir.resolve("build/k2j/k2j-manifest.json").readText()
        )
    }

    @Test
    fun projectWithoutTheKotlinPluginFailsWithAnActionableK2jMessage() {
        val projectDir = TestKitSupport.tempProject(
            "k2j-no-kotlin-plugin",
            """
                plugins { id("org.example.k2j") }
                k2j { }
            """.trimIndent()
        )

        val result = TestKitSupport.runAndFail(projectDir, "k2j")
        val output = result.output
        println("===== NO KOTLIN PLUGIN =====")
        println(output)

        assertTrue(
            output.contains("k2j: the Kotlin JVM plugin is not applied"),
            "expected the explicit k2j plugin check, got:\n$output"
        )
        assertTrue(output.contains("org.jetbrains.kotlin.jvm"), output)
        assertFalse(output.contains("NoSuchElementException"), output)
        assertFalse(output.contains("no element matching the predicate"), output)
    }

    @Test
    fun projectWithNoCompiledClassesFailsWithAnActionableK2jMessage() {
        val projectDir = TestKitSupport.tempProject(
            "k2j-no-classes",
            """
                plugins {
                    kotlin("jvm") version "2.4.0"
                    id("org.example.k2j")
                }
                repositories { mavenCentral() }
                k2j { }
            """.trimIndent()
        )

        val result = TestKitSupport.runAndFail(projectDir, "k2j")
        val output = result.output
        println("===== NO CLASSES DIR =====")
        println(output)

        assertTrue(
            Regex("k2j: no (compiled main classes found|Kotlin classes)").containsMatchIn(output),
            "expected an actionable k2j message about missing classes, got:\n$output"
        )
        assertTrue(output.contains(":classes"), "the message must point at :classes:\n$output")
        assertFalse(output.contains("NoSuchElementException"), output)
        assertFalse(output.contains("no element matching the predicate"), output)
        assertFalse(output.contains("Collection contains no element"), output)
    }

    @Test
    fun aFailedConversionLeavesTheManifestNamedInTheError() {
        val projectDir = TestKitSupport.tempProject(
            "k2j-failed-conversion",
            """
                plugins {
                    kotlin("jvm") version "2.4.0"
                    id("org.example.k2j")
                }
                repositories { mavenCentral() }
                kotlin { jvmToolchain(21) }
                k2j {
                    decompilerJar.set(layout.projectDirectory.file("bogus.jar"))
                }
            """.trimIndent(),
            mapOf("src/main/kotlin/accept/plain/Single.kt" to singleKt("v1"))
        )
        projectDir.resolve("bogus.jar").writeText("this is not a jar")

        val result = TestKitSupport.runAndFail(projectDir, "k2j")
        val output = result.output
        println("===== FAILED CONVERSION =====")
        println(output)

        assertTrue(output.contains("k2j: conversion failed for"), "expected a k2j conversion failure:\n$output")
        val manifest = projectDir.resolve("build/k2j/k2j-manifest.json")
        assertTrue(manifest.isFile, "the manifest named in the error must exist: ${manifest.path}")
        assertTrue(manifest.readText().contains("\"success\": false"), manifest.readText())
        assertTrue(
            manifest.readText().contains("\"failures\""),
            "the manifest must carry the failures: ${manifest.readText()}"
        )
        assertFalse(
            output.contains("\\k2j/k2j-manifest.json"),
            "the manifest path in the message must not mix separators:\n$output"
        )
    }

    @Test
    fun aChecksumMismatchIsReportedClearly() {
        val projectDir = TestKitSupport.tempProject(
            "k2j-checksum",
            """
                plugins {
                    kotlin("jvm") version "2.4.0"
                    id("org.example.k2j")
                }
                repositories { mavenCentral() }
                k2j {
                    decompilerJarSha256.set("${"0".repeat(64)}")
                }
            """.trimIndent()
        )

        val result = TestKitSupport.runAndFail(projectDir, "k2j")
        val output = result.output
        println("===== CHECKSUM MISMATCH =====")
        println(output)

        assertTrue(output.contains("k2j: decompiler jar checksum mismatch"), output)
        assertTrue(output.contains("expected sha256: ${"0".repeat(64)}"), output)
        assertTrue(output.contains(pin), "the actual digest must be reported:\n$output")
    }

    private val sep = File.separatorChar

    private fun convertedCount(output: String): Int {
        val match = Regex("""k2j: converted (\d+) class\(es\)""").find(output)
        assertTrue(match != null, "expected a 'k2j: converted N class(es)' line in:\n$output")
        return match!!.groupValues[1].toInt()
    }
}
