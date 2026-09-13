package com.onomatic.k2j.gradle

import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * End-to-end: a subject project carrying the corpus sources is built and converted by the
 * `k2j` task. The corpus itself is never modified — its sources are copied read-only into a
 * temp project. Requires the vendored FernFlower jar and a Java 25 runtime to be resolvable;
 * the core decompiler forks onto that runtime itself.
 *
 * The corpus has no Java sources, so this test cannot see the mixed-module classes-root trap:
 * [K2jSubjectFunctionalTest] covers that with a `.kt` + `.java` subject pair.
 */
class K2jCorpusIntegrationTest {

    private val corpusSources = java.io.File("D:/Work/k2j/corpus/app/src")

    @Test
    fun `k2j on the corpus produces build k2j accept plain Single java`() {
        val projectDir = TestKitSupport.tempProject(
            "k2j-corpus-subject",
            """
                plugins {
                    kotlin("jvm") version "2.4.0"
                    id("com.onomatic.k2j")
                }
                repositories { mavenCentral() }
                kotlin { jvmToolchain(21) }
                k2j { }
            """.trimIndent()
        )
        corpusSources.copyRecursively(projectDir.resolve("src"), overwrite = true)

        val result = TestKitSupport.run(projectDir, "k2j")
        assertTrue(
            Regex("""k2j: converted [1-9]\d* class\(es\)""").containsMatchIn(result.output),
            "the corpus run must convert at least one class:\n${result.output}"
        )

        val generated = projectDir.resolve("build/k2j/accept/plain/Single.java")
        assertTrue(
            generated.isFile && generated.readText().contains("class Single"),
            "expected converted Single.java at ${generated.path}"
        )
    }
}
