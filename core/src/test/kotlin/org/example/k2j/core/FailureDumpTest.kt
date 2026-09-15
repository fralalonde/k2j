package org.example.k2j.core

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.name
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * `--dump-failures` / `k2j { dumpFailures }`: the generated text of a unit that fails a phase must be
 * readable on disk, because the manifest only names the class ("Bad.java:2:4: enum constant expected
 * here") and the unit itself was never written.
 *
 * The dump is a diagnostic, not a decision: these tests assert both that the text lands on disk AND
 * that the failure is still reported — a dump must never turn a rejected unit into a silent success.
 */
class FailureDumpTest {

    @TempDir
    lateinit var tmp: Path

    private fun log(): Pair<RunLog, MutableList<String>> {
        val lines = mutableListOf<String>()
        val log = object : RunLog {
            override fun info(message: String) { lines += "INFO: $message" }
            override fun warn(message: String) { lines += "WARN: $message" }
            override fun error(message: String, cause: Throwable?) { lines += "ERROR: $message" }
        }
        return log to lines
    }

    /** One target whose text the parse gate rejects, plus one that converts. */
    private class TwoUnitDecompiler : Decompiler {
        override fun decompile(classFiles: List<Path>, classpath: List<Path>): Map<String, String> = mapOf(
            "accept.one.One" to "public class One { public int one() { return 1; } }",
            "accept.two.Bad" to "public class Bad {\n   public void broken( {\n}\n"
        )
    }

    private class TwoTargetSurveyor : Surveyor {
        override fun survey(request: ConversionRequest): SurveyResult {
            fun target(name: String) = ClassTarget(
                classFile = request.classesRoot.resolve("${name.substringAfterLast('.')}.class"),
                className = name
            )
            return SurveyResult(
                targets = listOf(target("accept.one.One"), target("accept.two.Bad")),
                excluded = emptyList(),
                sources = emptyList()
            )
        }
    }

    private fun run(dumpFailures: Path?): ConversionManifest {
        val (log, _) = log()
        return K2j(
            surveyor = TwoTargetSurveyor(),
            decompiler = TwoUnitDecompiler(),
            validator = JavacValidator(),
            writer = FileSystemWriter(),
            log = log
        ).convert(
            ConversionRequest(
                classesRoot = tmp.resolve("classes"),
                classpath = emptyList(),
                outputRoot = tmp.resolve("out"),
                dumpFailures = dumpFailures
            )
        )
    }

    @Test
    fun `the failing unit's text and reason are written and the failure is still reported`() {
        val dump = tmp.resolve("dumps")
        val manifest = run(dump)

        // Not weakened: the offending class is still a failure, and the good one still converts.
        assertEquals(listOf("accept.two.Bad"), manifest.failures.map { it.className })
        assertEquals("VALIDATE", manifest.failures.single().phase)
        assertFalse(manifest.success)
        assertEquals(listOf("accept.one.One"), manifest.converted.map { it.className })

        val text = dump.resolve("Bad.java")
        assertTrue(Files.isRegularFile(text), "the failing text must be readable at $text")
        assertEquals(
            "public class Bad {\n   public void broken( {\n}\n",
            Files.readString(text),
            "the dump must be the text exactly as the parse gate saw it"
        )

        val sidecar = Files.readString(dump.resolve("Bad.failure.txt"))
        assertTrue(sidecar.contains("class: accept.two.Bad"), sidecar)
        assertTrue(sidecar.contains("phase: VALIDATE"), sidecar)
        assertTrue(sidecar.contains("Bad.java:2"), "the reason must carry the parse error and its position: $sidecar")

        // Nothing is dumped for the unit that converted, and the dump never masquerades as output.
        assertFalse(Files.exists(dump.resolve("One.java")))
        assertFalse(Files.exists(tmp.resolve("out/accept/two/Bad.java")))
        assertTrue(Files.isRegularFile(tmp.resolve("out/accept/one/One.java")))
    }

    @Test
    fun `no dump is written when the flag is off`() {
        val manifest = run(null)
        assertEquals(listOf("accept.two.Bad"), manifest.failures.map { it.className })
        assertTrue(Files.notExists(tmp.resolve("dumps")))
    }

    @Test
    fun `a decompile failure with no text still records its reason`() {
        val dump = tmp.resolve("dumps")
        val (log, _) = log()
        val manifest = K2j(
            surveyor = TwoTargetSurveyor(),
            decompiler = object : Decompiler {
                override fun decompile(classFiles: List<Path>, classpath: List<Path>): Map<String, String> =
                    emptyMap()
            },
            validator = JavacValidator(),
            writer = FileSystemWriter(),
            log = log
        ).convert(
            ConversionRequest(
                classesRoot = tmp.resolve("classes"),
                classpath = emptyList(),
                outputRoot = tmp.resolve("out"),
                dumpFailures = dump
            )
        )

        assertEquals(2, manifest.failures.size)
        assertTrue(manifest.failures.all { it.phase == "DECOMPILE" }, manifest.failures.toString())
        val sidecar = Files.readString(dump.resolve("Bad.failure.txt"))
        assertTrue(sidecar.contains("phase: DECOMPILE"), sidecar)
        assertTrue(sidecar.contains("no unit"), sidecar)
        // No text existed, so no .java is invented.
        assertFalse(Files.exists(dump.resolve("Bad.java")))
    }

    @Test
    fun `two packages with the same simple name dump to two files`() {
        val dump = tmp.resolve("dumps")
        val (log, _) = log()
        val surveyor = object : Surveyor {
            override fun survey(request: ConversionRequest): SurveyResult = SurveyResult(
                targets = listOf(
                    ClassTarget(request.classesRoot.resolve("a.class"), "one.Same"),
                    ClassTarget(request.classesRoot.resolve("b.class"), "two.Same")
                ),
                excluded = emptyList(),
                sources = emptyList()
            )
        }
        val decompiler = object : Decompiler {
            override fun decompile(classFiles: List<Path>, classpath: List<Path>): Map<String, String> = mapOf(
                "one.Same" to "public class Same { broken( }",
                "two.Same" to "public class Same { alsoBroken( }"
            )
        }
        val manifest = K2j(surveyor, decompiler, JavacValidator(), FileSystemWriter(), log).convert(
            ConversionRequest(
                classesRoot = tmp.resolve("classes"),
                classpath = emptyList(),
                outputRoot = tmp.resolve("out"),
                dumpFailures = dump
            )
        )

        assertEquals(2, manifest.failures.size)
        val dumped = Files.list(dump).use { stream -> stream.map { it.name }.sorted().toList() }
        assertEquals(
            listOf("Same.failure.txt", "Same.java", "two_Same.failure.txt", "two_Same.java"),
            dumped,
            "a simple-name collision must not overwrite the first dump"
        )
    }
}
