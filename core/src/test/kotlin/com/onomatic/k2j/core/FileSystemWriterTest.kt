package com.onomatic.k2j.core

import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class FileSystemWriterTest {

    @TempDir
    lateinit var tmp: Path

    @Test
    fun `writes package-path layout`() {
        val writer = FileSystemWriter()
        val written = writer.write(mapOf("com.foo.Bar" to "package com.foo;\npublic class Bar {}\n"), tmp)
        assertEquals(1, written.size)
        val expected = tmp.resolve("com/foo/Bar.java")
        assertTrue(Files.isRegularFile(expected))
        assertEquals(expected.toString(), written[0].outputPath)
        assertEquals("com.foo.Bar", written[0].className)
    }

    @Test
    fun `refuses to overwrite an existing file`() {
        val writer = FileSystemWriter()
        writer.write(mapOf("com.foo.Bar" to "first\n"), tmp)
        val ex = assertThrows(IllegalStateException::class.java) {
            writer.write(mapOf("com.foo.Bar" to "second\n"), tmp)
        }
        assertTrue(ex.message!!.contains("refusing to overwrite"))
    }

    @Test
    fun `deleteVerifiedSources deletes exactly the listed files`() {
        val a = tmp.resolve("A.kt")
        val b = tmp.resolve("B.kt")
        val dir = tmp.resolve("sub")
        Files.createDirectories(dir)
        Files.writeString(a, "class A")
        Files.writeString(b, "class B")
        val writer = FileSystemWriter()
        val deleted = writer.deleteVerifiedSources(listOf(a, b, dir, tmp.resolve("missing.kt")))
        assertEquals(listOf(a.toString(), b.toString()), deleted.sorted())
        assertTrue(Files.notExists(a) && Files.notExists(b))
        assertTrue(Files.isDirectory(dir), "directories must never be deleted")
    }

    @Test
    fun `manifest records a reason for every source decision and the warnings`() {
        val writer = FileSystemWriter()
        val manifest = ConversionManifest(
            converted = listOf(ConvertedClass("com.foo.Bar", "out/com/foo/Bar.java")),
            failures = emptyList(),
            deletableSources = listOf("src/Bar.kt"),
            deletedSources = emptyList(),
            warnings = listOf("Nested class com/foo/Bar\$Inner missing!"),
            sources = listOf(
                SourceDecision("src/Bar.kt", true, "every class file was converted and written"),
                SourceDecision("src/Aliases.kt", false, "typealias 'Alias' has no representation in the generated output")
            )
        )
        val path = writer.writeManifest(manifest, tmp)
        val json = Files.readString(path)
        assertTrue(json.contains("\"source\": \"src/Aliases.kt\""), json)
        assertTrue(json.contains("\"deletable\": false"), json)
        assertTrue(json.contains("typealias 'Alias' has no representation"), json)
        assertTrue(json.contains("Nested class com/foo/Bar\$Inner missing!"), json)
        assertTrue(json.contains("\"success\": true"), json)
    }
}
