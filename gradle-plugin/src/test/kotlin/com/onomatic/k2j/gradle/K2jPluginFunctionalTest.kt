package com.onomatic.k2j.gradle

import kotlin.test.Test
import kotlin.test.assertTrue
import java.io.File

/**
 * Functional tests for plugin wiring: registration, the classes dependency, and the extension
 * defaults. The subject projects are built by Gradle TestKit in temp dirs.
 *
 * Failure-mode behaviour (no Kotlin plugin, no classes, failed conversion, checksum mismatch) and
 * the repeatable mixed-subject run live in [K2jSubjectFunctionalTest]; the end-to-end corpus run is
 * in [K2jCorpusIntegrationTest].
 */
class K2jPluginFunctionalTest {

    private fun kotlinSubject(extra: String = "") = """
        plugins {
            kotlin("jvm") version "2.4.0"
            id("com.onomatic.k2j")
        }
        repositories { mavenCentral() }
        $extra
    """.trimIndent()

    @Test
    fun `applying the plugin registers the k2j task`() {
        val projectDir = TestKitSupport.tempProject("k2j-registration", kotlinSubject("k2j { }"))

        val result = TestKitSupport.run(projectDir, "k2j", "--dry-run")
        assertTrue(
            result.output.contains(":k2j"),
            "expected :k2j in the execution plan, got:\n${result.output}"
        )
    }

    @Test
    fun `k2j depends on classes of the main source set`() {
        val projectDir = TestKitSupport.tempProject("k2j-classes-dep", kotlinSubject("k2j { }"))

        // --dry-run shows the execution plan; :compileKotlin there means the classes dependency is wired.
        val result = TestKitSupport.run(projectDir, "k2j", "--dry-run")
        assertTrue(
            result.output.contains(":compileKotlin") || result.output.contains(":classes"),
            "expected the k2j plan to include the classes tasks, got:\n${result.output}"
        )
    }

    @Test
    fun `extension defaults are the vendored artifact, a probed runtime and build slash k2j`() {
        val projectDir = TestKitSupport.tempProject(
            "k2j-defaults",
            kotlinSubject(
                """
                k2j { }
                tasks.register("printK2jConfig") {
                    val ext = project.extensions.getByType(com.onomatic.k2j.gradle.K2jExtension::class.java)
                    doLast {
                        println("outputRoot=" + ext.outputRoot.get().asFile)
                        println("deleteSources=" + ext.deleteSources.get())
                        println("packages=" + ext.packages.get())
                        println("decompilerJar=" + (ext.decompilerJar.orNull ?: "unset (uses the vendored artifact)"))
                        println("vendoredJar=" + com.onomatic.k2j.gradle.K2jExtension.VENDORED_DECOMPILER_JAR)
                        println("vendoredSha=" + com.onomatic.k2j.gradle.K2jExtension.VENDORED_DECOMPILER_JAR_SHA256)
                        println("runtimeJavaHome=" + ext.runtimeJavaHome.orNull)
                    }
                }
                """.trimIndent()
            )
        )

        val out = TestKitSupport.run(projectDir, "printK2jConfig").output
        assertTrue(out.contains("deleteSources=false"), out)
        assertTrue(out.contains("packages=[]"), out)
        assertTrue(out.contains("outputRoot="), out)
        assertTrue(out.contains("k2j"), "outputRoot should default under build/k2j: $out")
        // The decompiler default is the vendored classpath resource, not a path into a Gradle cache.
        assertTrue(out.contains("decompilerJar=unset (uses the vendored artifact)"), out)
        assertTrue(out.contains("vendoredJar=${K2jExtension.VENDORED_DECOMPILER_JAR}"), out)
        assertTrue(out.contains("vendoredSha=${K2jExtension.VENDORED_DECOMPILER_JAR_SHA256}"), out)
        assertTrue(!out.contains("transforms"), "no default may point at a Gradle transform cache: $out")
        assertTrue(!out.contains("caches/9.0.0"), "no default may point at a Gradle transform cache: $out")
        // The runtime default is whatever the ordered probe found (unset on a bare machine), but
        // it is never a hard-coded user-specific path baked into the plugin (see
        // DecompilerArtifactTest.mainSourcesCarryNoTransformCacheOrUserSpecificDefaults).
        val runtimeLine = out.lineSequence().firstOrNull { it.startsWith("runtimeJavaHome=") }
        assertTrue(runtimeLine != null, "the defaults task must report runtimeJavaHome: $out")
        val runtime = runtimeLine!!.substringAfter('=')
        assertTrue(
            runtime == "null" || File(runtime).isDirectory,
            "runtimeJavaHome must be a resolvable JDK home or unset, was: $runtime"
        )
    }
}
