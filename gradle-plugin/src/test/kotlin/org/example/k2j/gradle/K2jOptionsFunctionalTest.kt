package org.example.k2j.gradle

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The `k2j` task's command-line options: `--packages`, `--output-root`, `--delete-sources`,
 * `--dump-failures`, `--decompiler-jar`, `--decompiler-jar-sha256`, `--runtime-java-home`.
 *
 * Every test here is a GradleRunner functional test over a real temp subject project, because
 * Gradle's option handling (`@Option` scanning, the value parsers, and the rule that an option
 * value — applied with `set` — beats the extension's convention) is Gradle-internal. A unit test
 * that assigns the task property directly would pass with no `@Option` annotation present at all,
 * so it would prove nothing about the flag.
 *
 * Each test is written so that removing its `@Option` annotation makes it fail: the invocation
 * itself then fails with `Unknown command-line option`. The CLI-beats-extension claim is reverted
 * separately (`convention` -> `set` in `K2jPlugin`) and is asserted by
 * [optionsOverrideTheExtensionWhenBothAreSupplied].
 */
class K2jOptionsFunctionalTest {

    private val pin = K2jExtension.VENDORED_DECOMPILER_JAR_SHA256

    /** One Kotlin class per package, so a package filter is observable in the output tree. */
    private fun sources() = mapOf(
        "src/main/kotlin/accept/one/One.kt" to """
            package accept.one

            class One {
                fun label(): String = "one"
            }
        """.trimIndent(),
        "src/main/kotlin/accept/two/Two.kt" to """
            package accept.two

            class Two {
                fun label(): String = "two"
            }
        """.trimIndent()
    )

    private fun subject(extension: String = "k2j { }") = """
        plugins {
            kotlin("jvm") version "2.4.0"
            id("org.example.k2j")
        }
        repositories { mavenCentral() }
        kotlin { jvmToolchain(21) }
        $extension
    """.trimIndent()

    private fun kotlinSubject(extension: String = "k2j { }") =
        TestKitSupport.tempProject("k2j-options", subject(extension), sources())

    private fun tempDir(prefix: String): File =
        Files.createTempDirectory(prefix).toFile().canonicalFile

    private fun generated(projectDir: File, outputRoot: File): List<String> =
        outputRoot.walkTopDown()
            .filter { it.isFile && it.extension == "java" }
            .map { it.relativeTo(outputRoot).invariantSeparatorsPath }
            .sorted()
            .toList()

    private fun manifest(outputRoot: File): String =
        File(outputRoot, K2jTask.MANIFEST_NAME).readText()

    /** The JSON array of a manifest key, e.g. `deletedSources` — enough to assert membership. */
    private fun manifestSection(manifest: String, key: String): String =
        Regex(""""$key"\s*:\s*\[(.*?)]""", RegexOption.DOT_MATCHES_ALL)
            .find(manifest)?.groupValues?.get(1).orEmpty()

    // -- --packages ---------------------------------------------------------------------------

    @Test
    fun packagesOptionNarrowsWhatIsConverted() {
        val projectDir = kotlinSubject()
        val narrowed = tempDir("k2j-opt-packages-narrowed")

        val output = TestKitSupport.run(
            projectDir,
            "k2j",
            "--packages=accept.one",
            "--output-root=${narrowed.path}"
        ).output
        println("===== --packages=accept.one =====")
        println(output)

        // The flag was parsed and honoured: only accept.one is a conversion target.
        assertEquals(listOf("accept/one/One.java"), generated(projectDir, narrowed))
        assertTrue(manifest(narrowed).contains("accept.one.One"), manifest(narrowed))
        assertFalse(
            manifest(narrowed).contains("accept.two.Two"),
            "the narrowed run must not convert accept.two: ${manifest(narrowed)}"
        )

        // Control: without the flag the same task converts every Kotlin package.
        val all = tempDir("k2j-opt-packages-all")
        TestKitSupport.run(projectDir, "k2j", "--output-root=${all.path}")
        assertEquals(listOf("accept/one/One.java", "accept/two/Two.java"), generated(projectDir, all))

        // Repeatable: two --packages values are both honoured.
        val both = tempDir("k2j-opt-packages-repeat")
        TestKitSupport.run(
            projectDir,
            "k2j",
            "--packages=accept.one",
            "--packages=accept.two",
            "--output-root=${both.path}"
        )
        assertEquals(listOf("accept/one/One.java", "accept/two/Two.java"), generated(projectDir, both))
    }

    // -- --output-root ------------------------------------------------------------------------

    @Test
    fun outputRootOptionRedirectsTheOutput() {
        val extensionRoot = "build/from-extension"
        val projectDir = kotlinSubject(
            """
                k2j {
                    outputRoot.set(layout.buildDirectory.dir("$extensionRoot"))
                }
            """.trimIndent()
        )
        val redirected = tempDir("k2j-opt-output-root")

        val output = TestKitSupport.run(projectDir, "k2j", "--output-root=${redirected.path}").output
        println("===== --output-root =====")
        println(output)

        assertEquals(listOf("accept/one/One.java", "accept/two/Two.java"), generated(projectDir, redirected))
        assertTrue(
            File(redirected, K2jTask.MANIFEST_NAME).isFile,
            "the manifest must land in the redirected output root: ${File(redirected, K2jTask.MANIFEST_NAME)}"
        )
        // The extension's outputRoot is the convention; the option wins over it entirely.
        val extensionDir = projectDir.resolve(extensionRoot)
        assertFalse(
            extensionDir.exists(),
            "the extension's output root must not be written when --output-root is given: ${extensionDir.path}"
        )
        assertFalse(projectDir.resolve("build/k2j").exists(), "the default output root must not be used")
    }

    // -- --delete-sources ---------------------------------------------------------------------

    @Test
    fun deleteSourcesOptionDeletesTheConvertedSource() {
        val projectDir = kotlinSubject(
            """
                k2j {
                    deleteSources.set(false)
                }
            """.trimIndent()
        )
        val outputRoot = tempDir("k2j-opt-delete-sources")
        val deletable = projectDir.resolve("src/main/kotlin/accept/one/One.kt")
        val kept = projectDir.resolve("src/main/kotlin/accept/two/Two.kt")

        val output = TestKitSupport.run(
            projectDir,
            "k2j",
            "--delete-sources",
            "--packages=accept.one",
            "--output-root=${outputRoot.path}"
        ).output
        println("===== --delete-sources =====")
        println(output)

        // Parse-safety proof landed in the manifest, and the deletion followed it.
        val deleted = manifestSection(manifest(outputRoot), "deletedSources")
        assertTrue(deleted.contains("One.kt"), "the manifest must record the deleted source: $deleted")
        assertFalse(
            deletable.isFile,
            "the .kt whose declarations are all in the written output must be gone: ${deletable.path}"
        )
        // Only the converted target is a deletion candidate.
        assertTrue(kept.isFile, "a source outside --packages must survive: ${kept.path}")
        assertFalse(deleted.contains("Two.kt"), "Two.kt is outside --packages, so it must not be deleted: $deleted")
    }

    // -- --dump-failures -----------------------------------------------------------------------

    @Test
    fun dumpFailuresOptionWritesTheTextTheParseGateRejected() {
        // A decompiler that emits text the gate must reject, injected through the extension (the
        // Kotlin enums that used to break the gate are fixed, and no other corpus fixture does).
        // The point of the flag is that the rejected text is readable instead of reproduced by hand.
        // A single-line unit so it can travel into the generated build script verbatim.
        val broken = "public class One { private final int n; ONE(1); }"
        val projectDir = TestKitSupport.tempProject(
            "k2j-opt-dump",
            """
                plugins {
                    kotlin("jvm") version "2.4.0"
                    id("org.example.k2j")
                }
                repositories { mavenCentral() }
                kotlin { jvmToolchain(21) }
                k2j {
                    decompiler.set(object : org.example.k2j.core.Decompiler {
                        override fun decompile(
                            classFiles: List<java.nio.file.Path>,
                            classpath: List<java.nio.file.Path>
                        ): Map<String, String> = mapOf("accept.one.One" to "$broken")
                    })
                }
            """.trimIndent(),
            sources()
        )
        val outputRoot = tempDir("k2j-opt-dump-out")
        val dumpDir = tempDir("k2j-opt-dump-dir")

        val result = TestKitSupport.runAndFail(
            projectDir,
            "k2j",
            "--packages=accept.one",
            "--output-root=${outputRoot.path}",
            "--dump-failures=${dumpDir.path}"
        )
        println("===== --dump-failures ===== ")
        println(result.output)

        // The failure is still a failure — the dump is a diagnostic, not a way to pass.
        assertTrue(
            result.output.contains("k2j: conversion failed for 1 class(es)"),
            "the rejected unit must still fail the task:\n${result.output}"
        )
        assertTrue(
            result.output.contains(dumpDir.absolutePath),
            "the failure must point at the dump:\n${result.output}"
        )

        val dumped = File(dumpDir, "One.java")
        assertTrue(dumped.isFile, "the rejected text must be readable at ${dumped.absolutePath}")
        assertEquals(broken, dumped.readText(), "the dump must be the text the gate judged")
        val sidecar = File(dumpDir, "One.failure.txt").readText()
        assertTrue(sidecar.contains("class: accept.one.One"), sidecar)
        assertTrue(sidecar.contains("phase: VALIDATE"), sidecar)
        assertTrue(sidecar.contains("One.java:1"), "the reason must carry the error position: $sidecar")

        // Off by default: the same run without the flag writes no dump anywhere.
        val unflaggedRoot = tempDir("k2j-no-dump-out")
        val unflaggedDump = tempDir("k2j-no-dump-dir").resolve("never")
        TestKitSupport.runAndFail(
            projectDir,
            "k2j",
            "--packages=accept.one",
            "--output-root=${unflaggedRoot.path}"
        )
        assertFalse(unflaggedDump.exists(), "no dump may be written without --dump-failures")
    }

    // -- an option beats the extension ---------------------------------------------------------

    @Test
    fun optionsOverrideTheExtensionWhenBothAreSupplied() {
        val projectDir = kotlinSubject(
            """
                k2j {
                    packages.set(listOf("accept.two"))
                    outputRoot.set(layout.buildDirectory.dir("from-extension"))
                    deleteSources.set(false)
                }
            """.trimIndent()
        )
        val cliRoot = tempDir("k2j-opt-override")

        val output = TestKitSupport.run(
            projectDir,
            "k2j",
            "--packages=accept.one",
            "--output-root=${cliRoot.path}",
            "--delete-sources"
        ).output
        println("===== options vs extension =====")
        println(output)

        // packages: the option's package converted, not the extension's.
        assertEquals(listOf("accept/one/One.java"), generated(projectDir, cliRoot))
        assertFalse(
            manifest(cliRoot).contains("accept.two.Two"),
            "the extension's packages value must lose to --packages: ${manifest(cliRoot)}"
        )
        // outputRoot: the option's directory, not the extension's.
        assertFalse(
            projectDir.resolve("build/from-extension").exists(),
            "the extension's outputRoot must lose to --output-root"
        )
        // deleteSources: the extension says false, the flag deletes anyway.
        val deleted = manifestSection(manifest(cliRoot), "deletedSources")
        assertTrue(deleted.contains("One.kt"), "the flag must beat deleteSources = false: $deleted")
        assertFalse(
            projectDir.resolve("src/main/kotlin/accept/one/One.kt").isFile,
            "--delete-sources must delete even though k2j { deleteSources = false } was set"
        )
    }

    // -- --decompiler-jar ---------------------------------------------------------------------

    @Test
    fun decompilerJarOptionIsUsedInsteadOfTheVendoredArtifact() {
        val projectDir = kotlinSubject()
        val outputRoot = tempDir("k2j-opt-jar")
        val bogus = projectDir.resolve("bogus-decompiler.jar")
        bogus.writeText("this is not a jar\n")

        // A jar that exists but is not the vendored one: proving it was used is a *loud* failure
        // rather than the silent success the vendored artifact would have produced.
        val result = TestKitSupport.runAndFail(
            projectDir,
            "k2j",
            "--decompiler-jar=${bogus.path}",
            "--output-root=${outputRoot.path}"
        )
        println("===== --decompiler-jar (bogus) =====")
        println(result.output)
        assertTrue(
            result.output.contains("k2j: conversion failed for"),
            "the bogus --decompiler-jar must be used and fail loudly:\n${result.output}"
        )
        assertTrue(
            File(outputRoot, K2jTask.MANIFEST_NAME).isFile,
            "the failed run must still leave the manifest it names"
        )

        // An override without a digest is not verified against the vendored pin (the same rule the
        // extension already had), so the failure above is a decompile failure, not a checksum one.
        assertFalse(
            result.output.contains("checksum mismatch"),
            "an override with no digest must not be verified against the vendored pin:\n${result.output}"
        )

        // A path that does not exist is named, not silently replaced by the vendored artifact.
        val missing = projectDir.resolve("no-such-decompiler.jar")
        val missingResult = TestKitSupport.runAndFail(
            projectDir,
            "k2j",
            "--decompiler-jar=${missing.path}",
            "--output-root=${outputRoot.path}"
        )
        println("===== --decompiler-jar (missing) =====")
        println(missingResult.output)
        assertTrue(
            missingResult.output.contains("decompiler jar not found: ${missing.path}"),
            "the missing --decompiler-jar must be reported with its path:\n${missingResult.output}"
        )
    }

    // -- --decompiler-jar-sha256 --------------------------------------------------------------

    @Test
    fun decompilerJarSha256OptionIsVerified() {
        val projectDir = kotlinSubject()
        val outputRoot = tempDir("k2j-opt-jar-sha")
        val bogus = projectDir.resolve("bogus-decompiler.jar")
        bogus.writeText("this is not a jar\n")

        // The digest applies to the jar the run actually resolves (here: --decompiler-jar), and the
        // message names that file.
        val result = TestKitSupport.runAndFail(
            projectDir,
            "k2j",
            "--decompiler-jar=${bogus.path}",
            "--decompiler-jar-sha256=$pin",
            "--output-root=${outputRoot.path}"
        )
        println("===== --decompiler-jar-sha256 (mismatch) =====")
        println(result.output)
        assertTrue(result.output.contains("decompiler jar checksum mismatch"), result.output)
        assertTrue(
            result.output.contains("for ${bogus.path}"),
            "the mismatch must name the jar the run resolved, i.e. the --decompiler-jar value:\n${result.output}"
        )
        assertTrue(result.output.contains("expected sha256: $pin"), result.output)

        // Happy path: the same digest against the vendored artifact converts normally.
        val vendored = tempDir("k2j-opt-jar-sha-ok")
        val ok = TestKitSupport.run(
            projectDir,
            "k2j",
            "--decompiler-jar-sha256=$pin",
            "--output-root=${vendored.path}"
        ).output
        println("===== --decompiler-jar-sha256 (vendored pin) =====")
        println(ok)
        assertEquals(
            listOf("accept/one/One.java", "accept/two/Two.java"),
            generated(projectDir, vendored)
        )
        assertTrue(ok.contains("k2j: converted 2 class(es)"), ok)
    }

    // -- --runtime-java-home ------------------------------------------------------------------

    @Test
    fun runtimeJavaHomeOptionOverridesTheProbedRuntime() {
        val projectDir = kotlinSubject()
        val outputRoot = tempDir("k2j-opt-runtime")
        // The build's own runtime (this test JVM's home, the Java 21 toolchain): a real JDK home
        // that is not a Java 25 one. A machine-specific path is never involved.
        val buildRuntime = File(System.getProperty("java.home"))
        val buildMajor = JavaRuntime.majorVersion(buildRuntime) ?: return
        if (buildMajor >= K2jExtension.REQUIRED_JAVA_MAJOR) return

        val result = TestKitSupport.runAndFail(
            projectDir,
            "k2j",
            "--runtime-java-home=${buildRuntime.absolutePath}",
            "--output-root=${outputRoot.path}"
        )
        println("===== --runtime-java-home (Java $buildMajor) =====")
        println(result.output)

        // The probed Java 25 runtime would have converted fine, so a failure that names the
        // command-line home is proof the option was used instead.
        assertTrue(
            result.output.contains("is Java $buildMajor"),
            "expected the Java-25 requirement to reject the command-line home:\n${result.output}"
        )
        assertTrue(
            result.output.contains(buildRuntime.absolutePath),
            "the failure must name the home supplied on the command line:\n${result.output}"
        )
        assertFalse(
            File(outputRoot, K2jTask.MANIFEST_NAME).isFile,
            "a rejected runtime must fail before any conversion work"
        )
    }

    // -- --compile-check / --compile-classpath -------------------------------------------------

    @Test
    fun compileCheckOptionCompilesEveryUnitAndReportsAJavacRejectedOne() {
        // The subject's own compile classpath carries kotlin-stdlib (the `kotlin("jvm")` plugin adds
        // it), so the generated units resolve `kotlin.Metadata` and `Intrinsics` without any extra
        // wiring: --compile-check on this project must pass.
        val projectDir = kotlinSubject()
        val passing = tempDir("k2j-opt-compile-pass")
        val output = TestKitSupport.run(
            projectDir,
            "k2j",
            "--compile-check",
            "--output-root=${passing.path}"
        ).output
        println("===== --compile-check (passing) =====")
        println(output)

        assertEquals(listOf("accept/one/One.java", "accept/two/Two.java"), generated(projectDir, passing))
        assertTrue(output.contains("k2j: converted 2 class(es)"), output)

        // Control: without the flag the same run is parse-only — the manifest says nothing about a
        // compile, and the task reports the same conversion.
        val parseOnly = tempDir("k2j-opt-compile-off")
        TestKitSupport.run(projectDir, "k2j", "--output-root=${parseOnly.path}")
        assertEquals(listOf("accept/one/One.java", "accept/two/Two.java"), generated(projectDir, parseOnly))

        // A unit javac rejects: valid Java syntax, so the parse gate passes it, and an assignment the
        // compiler refuses. Injected through the extension's decompiler, the same technique the
        // --dump-failures test uses.
        val broken = "package accept.one; import java.util.Map;" +
            " public final class One { private final Map<String, Number> properties;" +
            " public One(Map<String, ? extends Number> props) { this.properties = props; }" +
            " public Map<String, Number> getProperties() { return this.properties; } }"
        val brokenProject = TestKitSupport.tempProject(
            "k2j-opt-compile-broken",
            """
                plugins {
                    kotlin("jvm") version "2.4.0"
                    id("org.example.k2j")
                }
                repositories { mavenCentral() }
                kotlin { jvmToolchain(21) }
                k2j {
                    decompiler.set(object : org.example.k2j.core.Decompiler {
                        override fun decompile(
                            classFiles: List<java.nio.file.Path>,
                            classpath: List<java.nio.file.Path>
                        ): Map<String, String> = mapOf("accept.one.One" to "$broken")
                    })
                }
            """.trimIndent(),
            sources()
        )
        val brokenOut = tempDir("k2j-opt-compile-broken-out")
        val dumpDir = tempDir("k2j-opt-compile-broken-dump")

        // Parse-only first: this is the silent success the flag exists to replace.
        val silent = TestKitSupport.run(
            brokenProject,
            "k2j",
            "--packages=accept.one",
            "--output-root=${tempDir("k2j-opt-compile-silent").path}"
        ).output
        println("===== same broken unit, parse-only =====")
        println(silent)
        assertTrue(silent.contains("k2j: converted 1 class(es)"), silent)

        // ... and with the flag on, the run fails with javac's own diagnostic.
        val result = TestKitSupport.runAndFail(
            brokenProject,
            "k2j",
            "--compile-check",
            "--packages=accept.one",
            "--output-root=${brokenOut.path}",
            "--dump-failures=${dumpDir.path}"
        )
        println("===== --compile-check (reported failure) =====")
        println(result.output)

        assertTrue(
            result.output.contains("k2j: conversion failed for 1 class(es)"),
            "a unit javac rejects must fail the task:\\n${result.output}"
        )
        val failure = File(brokenOut, K2jTask.MANIFEST_NAME).readText()
        assertTrue(failure.contains("\"phase\": \"COMPILE\""), failure)
        assertTrue(
            failure.contains("One.java:") && failure.contains("incompatible types"),
            "the manifest must carry javac's file/line/message: $failure"
        )
        assertFalse(
            File(brokenOut, "accept/one/One.java").exists(),
            "a unit that does not compile must not be written"
        )
        // The dump holds the text the compile gate judged, with the phase recorded.
        assertEquals(broken, File(dumpDir, "One.java").readText())
        assertTrue(File(dumpDir, "One.failure.txt").readText().contains("phase: COMPILE"))
    }

    @Test
    fun compileClasspathOptionIsAddedToTheCompileGateClasspath() {
        val projectDir = kotlinSubject()
        val outputRoot = tempDir("k2j-opt-compile-cp")
        // A path that does not exist is still a classpath entry: javac ignores it, and the run must
        // convert exactly as it does without the flag (the value is an addition, not a replacement).
        val extra = projectDir.resolve("does-not-exist.jar")

        val output = TestKitSupport.run(
            projectDir,
            "k2j",
            "--compile-check",
            "--compile-classpath=${extra.path}",
            "--output-root=${outputRoot.path}"
        ).output
        println("===== --compile-classpath =====")
        println(output)

        assertEquals(listOf("accept/one/One.java", "accept/two/Two.java"), generated(projectDir, outputRoot))
        assertTrue(output.contains("k2j: converted 2 class(es)"), output)
    }

    // -- discoverability ----------------------------------------------------------------------

    @Test
    fun helpTaskListsEveryOptionWithItsDescription() {
        val projectDir = kotlinSubject()

        val output = TestKitSupport.run(projectDir, "help", "--task", "k2j").output
        println("===== help --task k2j =====")
        println(output)

        val expected = mapOf(
            "--packages" to "Package to convert to Java",
            "--output-root" to "Directory for the generated .java files and the manifest",
            "--delete-sources" to "Delete the .kt sources whose declarations are all represented",
            "--dump-failures" to "Write the generated text of every unit that fails a phase",
            "--compile-check" to "Compile every generated unit with javac before writing it",
            "--compile-classpath" to "Path (jar or class directory) to compile the generated units against",
            "--decompiler-jar" to "Override the decompiler jar",
            "--decompiler-jar-sha256" to "Expected SHA-256 of the decompiler jar",
            "--runtime-java-home" to "JDK/JRE home used to fork the decompiler"
        )
        for ((option, description) in expected) {
            assertTrue(output.contains(option), "expected $option in the help output:\n$output")
            assertTrue(
                output.contains(description),
                "expected a description for $option containing '$description':\n$output"
            )
        }
    }
}
