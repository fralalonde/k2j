package com.onomatic.k2j.gradle

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Tests for the vendored decompiler artifact and the absence of machine-specific defaults.
 *
 * The point of these is MAJOR-5 regression pressure: the plugin must resolve the decompiler from
 * its own classpath (pinned by SHA-256), never from a Gradle transform-cache path or a path under
 * one developer's home directory.
 */
class DecompilerArtifactTest {

    @Test
    fun vendoredJarIsOnThePluginClasspathAndMatchesThePin() {
        val jar = DecompilerArtifact.resolveBundled(File(System.getProperty("java.io.tmpdir"), "k2j-test-artifacts"))

        assertTrue(jar.isFile, "the vendored jar must resolve to a real file: ${jar.absolutePath}")
        assertTrue(jar.length() > 1_000_000, "the vendored jar should be the full artifact, was ${jar.length()} bytes")
        assertEquals(
            K2jExtension.VENDORED_DECOMPILER_JAR_SHA256,
            DecompilerArtifact.sha256(jar),
            "the vendored artifact must match the recorded pin"
        )
    }

    @Test
    fun thePinIsAResourcePathNotAnAbsoluteMachinePath() {
        val pin = K2jExtension.VENDORED_DECOMPILER_JAR
        assertEquals("k2j/java-decompiler.jar", pin)
        assertTrue(!pin.contains(":\\") && !pin.startsWith("/"), "the pin must be a classpath resource: $pin")
        assertTrue(
            Regex("^[0-9a-f]{64}$").matches(K2jExtension.VENDORED_DECOMPILER_JAR_SHA256),
            "the pin must be a lower-case SHA-256: ${K2jExtension.VENDORED_DECOMPILER_JAR_SHA256}"
        )
        assertEquals(25, K2jExtension.REQUIRED_JAVA_MAJOR)
    }

    @Test
    fun checksumMismatchNamesTheExpectedDigestTheActualDigestAndThePin() {
        val jar = DecompilerArtifact.resolveBundled(File(System.getProperty("java.io.tmpdir"), "k2j-test-artifacts"))
        val wrong = "0".repeat(64)

        val failure = assertFailsWith<org.gradle.api.GradleException> { DecompilerArtifact.verify(jar, wrong) }

        assertTrue(failure.message!!.startsWith("k2j: "), failure.message)
        assertTrue(failure.message!!.contains("checksum mismatch"), failure.message)
        assertTrue(failure.message!!.contains("expected sha256: $wrong"), failure.message)
        assertTrue(failure.message!!.contains(DecompilerArtifact.sha256(jar)), failure.message)
        assertTrue(failure.message!!.contains(DecompilerArtifact.PIN_DESCRIPTION), failure.message)
    }

    /**
     * Source-level guard: nothing in the plugin's main sources may hard-code this machine's Gradle
     * transform cache or a developer-specific absolute path. A revert of the MAJOR-5 fix fails here.
     */
    @Test
    fun mainSourcesCarryNoTransformCacheOrUserSpecificDefaults() {
        val sourceRoot = File("src/main/kotlin/com/onomatic/k2j/gradle")
        assertTrue(sourceRoot.isDirectory, "expected the plugin sources at ${sourceRoot.absolutePath}")

        val forbidden = listOf(
            "transforms/c758eb",
            "caches/9.0.0",
            "AppData/Local/Programs",
            "AppData\\\\Local\\\\Programs",
            "rsdk/tools/java"
        )
        val offenders = sourceRoot.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .flatMap { file ->
                val text = file.readText()
                forbidden.filter { text.contains(it) }.map { "${file.name}: $it" }
            }
            .toList()

        assertTrue(offenders.isEmpty(), "machine-specific defaults found in plugin sources: $offenders")
    }
}
