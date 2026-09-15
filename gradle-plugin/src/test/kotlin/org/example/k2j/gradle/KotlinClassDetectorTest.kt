package org.example.k2j.gradle

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Tests for the constant-pool Kotlin-class detector against real compiled class files.
 *
 * The detector is what makes BLOCKER-1 root selection possible, so it gets a direct test: a
 * Kotlin class file must be recognised by its `kotlin/Metadata` constant-pool entry, and a
 * Java-compiled class (and any non-class file) must not.
 */
class KotlinClassDetectorTest {

    private val kotlinClassFile: File = firstClassFile(codeSourceOf(KotlinClassDetectorTest::class.java))
    private val javaClassFile: File = firstClassFile(
        codeSourceOf(Class.forName("org.example.k2j.gradle.JavaOnlyFixture"))
    )

    @Test
    fun recognisesARealKotlinClass() {
        assertTrue(
            KotlinClassDetector.hasKotlinMetadata(kotlinClassFile),
            "expected kotlin/Metadata in ${kotlinClassFile.absolutePath}"
        )
    }

    @Test
    fun rejectsARealJavaClass() {
        assertFalse(
            KotlinClassDetector.hasKotlinMetadata(javaClassFile),
            "a javac-compiled class must not look like Kotlin: ${javaClassFile.absolutePath}"
        )
    }

    @Test
    fun rejectsNonClassInputWithoutThrowing() {
        val text = Files.createTempFile("k2j-detector", ".class").toFile()
        text.writeText("not a class file at all")
        assertFalse(KotlinClassDetector.hasKotlinMetadata(text))

        val empty = Files.createTempFile("k2j-detector-empty", ".class").toFile()
        assertFalse(KotlinClassDetector.hasKotlinMetadata(empty))

        val truncated = Files.createTempFile("k2j-detector-trunc", ".class").toFile()
        truncated.writeBytes(kotlinClassFile.readBytes().copyOfRange(0, 12))
        assertFalse(KotlinClassDetector.hasKotlinMetadata(truncated))
    }

    @Test
    fun distinguishesTheTwoCompiledDirectoriesContainingTheTestClasses() {
        assertTrue(ClassRoots.containsKotlinClass(codeSourceOf(KotlinClassDetectorTest::class.java)))
        assertFalse(ClassRoots.containsKotlinClass(codeSourceOf(Class.forName("org.example.k2j.gradle.JavaOnlyFixture"))))
    }

    private fun firstClassFile(dir: File): File {
        val file = dir.walkTopDown().firstOrNull { it.isFile && it.extension == "class" }
        assertTrue(file != null, "no .class files under ${dir.absolutePath}")
        return file!!
    }

    private fun codeSourceOf(type: Class<*>): File =
        File(type.protectionDomain.codeSource.location.toURI()).canonicalFile
}
