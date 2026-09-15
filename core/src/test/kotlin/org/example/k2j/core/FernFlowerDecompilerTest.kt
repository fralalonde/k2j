package org.example.k2j.core

import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assumptions

/** Decompiler tests need the Java 25 runtime; skipped (with a printed reason) when absent. */
class FernFlowerDecompilerTest {

    private fun java25Home(): Path? = listOf(
        Path.of("C:/Users/FrancisLalonde/.rsdk/tools/java/25.0.2-jbr"),
        Path.of("C:/Users/FrancisLalonde/AppData/Local/Programs/IntelliJ IDEA Ultimate/jbr")
    ).firstOrNull { Files.isRegularFile(it.resolve("bin/java.exe")) }

    private fun decompilerJar(): Path? = listOf(
        // the vendored artifact (k2j's own pin), then the transform-cache path it was taken from
        Path.of("D:/Work/k2j/gradle-plugin/src/main/resources/k2j/java-decompiler.jar"),
        Path.of(
            "D:/.gradle/caches/9.0.0/transforms/c758eb8dec3a6a10386456b84606aff8/transformed/idea-2026.2.1-win/plugins/java-decompiler/lib/java-decompiler.jar"
        )
    ).firstOrNull { Files.isRegularFile(it) }

    private fun newDecompiler(): FernFlowerDecompiler {
        val jar = decompilerJar()
        val jbr = java25Home()
        Assumptions.assumeTrue(jar != null, "vendored java-decompiler.jar not found at the pinned path")
        Assumptions.assumeTrue(jbr != null, "Java 25 runtime (IDE JBR) not found at the default path")
        return FernFlowerDecompiler(jar!!, jbr!!)
    }

    private fun corpus(): Path {
        val corpus = TestSupport.corpusClasses
        Assumptions.assumeTrue(corpus != null, "corpus classes not built at D:/Work/k2j/corpus/app/build/classes/kotlin/main")
        return corpus!!
    }

    @Test
    fun `decompiles accept plain Single with bridges removed`() {
        val corpus = corpus()
        val decompiler = newDecompiler()

        val result = decompiler.decompile(
            listOf(
                corpus.resolve("accept/plain/Single.class"),
                corpus.resolve("accept/bridged/BridgeImpl.class"),
                corpus.resolve("accept/bridged/BridgeBox.class")
            ),
            emptyList()
        )

        val single = result["accept.plain.Single"]
        assertTrue(single != null, "expected accept.plain.Single in keys ${result.keys}")
        assertTrue(single!!.contains("class Single"), "decompiled text must contain 'class Single'")

        // rbr=1 (the pinned default): the erasure bridge must be gone. Asserted observably — exactly
        // one get() declaration on BridgeImpl and no Object-returning overload. With rbr=0 FernFlower
        // emits a second `public Object get()`; the old assertion looked for the literal strings
        // 'bridge method|synthetic method', which FernFlower never writes into its own output and so
        // passed even with rbr=0.
        val bridgeImpl = result["accept.bridged.BridgeImpl"]
        assertTrue(bridgeImpl != null, "expected accept.bridged.BridgeImpl in keys ${result.keys}")
        val getDeclarations = Regex("""get\(\)\s*\{""").findAll(bridgeImpl!!).count()
        assertEquals(1, getDeclarations, "exactly one get() declaration expected: $bridgeImpl")
        assertTrue(bridgeImpl.contains("String get()"), "BridgeImpl must keep its real get(): $bridgeImpl")
        assertFalse(bridgeImpl.contains("Object get()"), "no Object-returning bridge may survive: $bridgeImpl")

        // The interface's own generic member is NOT a bridge. With generic signatures enabled (dgs=1)
        // it keeps its type parameter and comes back as `T get()`. It used to arrive erased as
        // `Object get()`; pinning that erasure only asserted an artifact of dgs being off, so assert
        // the stronger property instead: the type parameter survives and nothing is erased to Object.
        val bridgeBox = result["accept.bridged.BridgeBox"]
        assertTrue(bridgeBox != null, "expected accept.bridged.BridgeBox in keys ${result.keys}")
        assertTrue(bridgeBox!!.contains("interface BridgeBox<T>"), "type parameter must survive: $bridgeBox")
        assertTrue(bridgeBox.contains("T get()"), "the member must keep its generic return type: $bridgeBox")
        assertFalse(bridgeBox.contains("Object get()"), "no erasure to Object when dgs=1: $bridgeBox")
    }

    @Test
    fun `decompiles nested inner local and anonymous classes into the outer unit`() {
        val corpus = corpus()
        val decompiler = newDecompiler()
        val nested = corpus.resolve("accept/nested")

        val withSiblings = decompiler.decompile(
            listOf(
                nested.resolve("Outer.class"),
                nested.resolve("Outer\$Nested.class"),
                nested.resolve("Outer\$Inner.class"),
                nested.resolve("Outer\$makeLocal\$Local.class"),
                nested.resolve("Outer\$makeAnon\$1.class")
            ),
            emptyList()
        )
        assertEquals(setOf("accept.nested.Outer"), withSiblings.keys, "one unit per top-level class")
        val text = withSiblings.getValue("accept.nested.Outer")
        assertTrue(text.contains("class Nested"), "nested class must appear: $text")
        assertTrue(text.contains("class Inner"), "inner class must appear: $text")
        assertTrue(text.contains("class Local"), "local class must appear: $text")

        // Counter-case that proves the fix matters: the outer class file alone does not carry its
        // nested types — the generated text references `makeLocal.Local` / `makeAnon.1` that do not
        // exist, which is exactly the shape that was written (and deleted) before the fix.
        val outerOnly = decompiler.decompile(listOf(nested.resolve("Outer.class")), emptyList())
        val outerText = outerOnly.getValue("accept.nested.Outer")
        assertFalse(outerText.contains("class Nested"), "outer class file alone cannot carry the nested type")
        assertFalse(outerText.contains("class Inner"), "outer class file alone cannot carry the inner type")
    }

    @Test
    fun `surfaces decompiler warnings to the caller`() {
        // DEFECT D: a missing nested class makes FernFlower log a WARN; it must reach the caller
        // instead of being collected and dropped.
        val corpus = corpus()
        val decompiler = newDecompiler()
        decompiler.decompile(listOf(corpus.resolve("accept/nested/Outer.class")), emptyList())
        assertTrue(
            decompiler.warnings.any { it.contains("missing", ignoreCase = true) },
            "warnings must surface the missing nested classes; got ${decompiler.warnings}"
        )
    }

    @Test
    fun `does not deadlock on a child that floods stdout`() {
        // DEFECT F(a): nothing read the child's stdout, so a chatty child would block on a full pipe
        // and never exit. With stdout discarded the child drains itself and completes.
        Assumptions.assumeTrue(System.getProperty("os.name").lowercase().contains("win"), "windows-specific flood command")
        val big = Files.createTempFile("k2j-flood", ".txt")
        Files.writeString(big, "x".repeat(5_000_000))
        val decompiler = FernFlowerDecompiler(Path.of("dec.jar"), Path.of("jbr"))
        val fork = decompiler.runForked(
            listOf("cmd.exe", "/c", "type", big.toString()),
            ByteArray(0),
            timeoutSeconds = 30
        )
        assertEquals(0, fork.exitCode, "an sdout-flooding child must complete, not deadlock: ${fork.stderr}")
    }

    @Test
    fun `command line carries no class paths and stdin carries the whole protocol`() {
        // DEFECT E: argv must stay minimal; the class list travels over stdin.
        val decompiler = FernFlowerDecompiler(Path.of("dec.jar"), Path.of("jbr"))
        val command = decompiler.buildCommand(
            Path.of("C:/jbr/bin/java.exe"),
            Path.of("D:/cache/dec.jar"),
            Path.of("D:/build/classes"),
            Path.of("D:/tmp/report.tsv")
        )
        assertEquals(5, command.size, "argv must be exactly java -cp <cp> <runner> <report>: $command")
        assertTrue(command.none { it.endsWith(".class") }, "no class path may appear on the command line: $command")

        val classes = listOf(Path.of("C:/c/Outer.class"), Path.of("C:/c/Outer\$Nested.class"))
        val libs = listOf(Path.of("C:/libs/kotlin-stdlib.jar"))
        val stdin = decompiler.buildStdinProtocol(classes, libs).toString(Charsets.UTF_8)
        assertTrue(stdin.contains("#CLASSES"), stdin)
        assertTrue(stdin.contains("C:\\c\\Outer.class") || stdin.contains("C:/c/Outer.class"), stdin)
        assertTrue(stdin.contains("Outer\$Nested.class"), stdin)
        assertTrue(stdin.contains("#LIBRARIES"), stdin)
        assertTrue(stdin.contains("kotlin-stdlib.jar"), stdin)
        assertTrue(stdin.contains("#END"), stdin)
    }

    @Test
    fun `a class list far larger than the windows command line limit still decompiles`() {
        // DEFECT E: ~350-450 absolute paths already exceed Windows' ~32,767 character command line.
        // Build a list whose total path length is well past that and prove it converts.
        val decompiler = newDecompiler()
        val classesRoot = Files.createTempDirectory("k2j-bulk")
        val classFiles = mutableListOf<Path>()
        var totalLength = 0
        var i = 0
        while (totalLength < 40_000) {
            val pkg = "bulk/p%04d".format(i / 20)
            val name = "Cls%04d".format(i)
            TestSupport.writeFacadeClassFile(classesRoot, pkg, name, sourceFile = "$name.kt")
            val path = classesRoot.resolve("$pkg/$name.class")
            classFiles.add(path)
            totalLength += path.toString().length
            i++
        }
        assertTrue(
            totalLength > 32_767,
            "fixture must exceed the Windows command-line limit; got $totalLength across ${classFiles.size} paths"
        )

        val result = decompiler.decompile(classFiles, emptyList())
        assertEquals(
            classFiles.size, result.size,
            "every class must produce a unit when the list arrives over stdin"
        )
    }

    @Test
    fun `escaped summary fields survive a newline and a tab`() {
        // DEFECT F(d): the parent splits the #RESULT line on tab. A failure message containing a
        // newline used to blank the field; one containing a tab used to shift columns and inject a
        // bogus class entry. Both fields are escaped now.
        val decompiler = FernFlowerDecompiler(Path.of("dec.jar"), Path.of("jbr"))
        val warning = "warn line 1\nline 2\twith tab"
        val failure = "hard failure\nsecond line\ttabbed"
        val payload = "accept/plain/Single\t" + FernFlowerRunner.escape("class Single {\n}\n")
        val report = payload + "\n" +
            "#RESULT\t" + FernFlowerRunner.escape(warning) + "\t" + FernFlowerRunner.escape(failure) + "\n"

        val parsed = decompiler.parseReport(report)
        assertEquals(listOf(warning), parsed.warnings, "warning must round-trip intact")
        assertEquals(listOf(failure), parsed.failures, "failure must round-trip intact")
        assertEquals(setOf("accept.plain.Single"), parsed.results.keys, "no bogus class key may be injected")
        assertTrue(parsed.results.getValue("accept.plain.Single").contains("class Single"))
    }

    @Test
    fun `kills a forked jvm that overruns the timeout`() {
        // DEFECT F(b): a hung child must not hang the build forever.
        Assumptions.assumeTrue(System.getProperty("os.name").lowercase().contains("win"), "windows-specific sleep command")
        val decompiler = FernFlowerDecompiler(Path.of("dec.jar"), Path.of("jbr"))
        val started = System.nanoTime()
        val ex = org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException::class.java) {
            decompiler.runForked(
                listOf("cmd.exe", "/c", "ping", "-n", "8", "127.0.0.1"),
                ByteArray(0),
                timeoutSeconds = 1
            )
        }
        assertTrue(ex.message!!.contains("did not finish"), "clear timeout error expected: ${ex.message}")
        val elapsedSeconds = (System.nanoTime() - started) / 1_000_000_000
        assertTrue(elapsedSeconds < 30, "the fork must be killed promptly, not awaited; took ${elapsedSeconds}s")
    }
}
