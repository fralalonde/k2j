package org.example.k2j.core

import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * [ConversionRequest.compileCheck] — the opt-in gate that compiles each generated unit before it is
 * written, because the parse gate provably cannot see this class of defect.
 *
 * The unit under it here is the real defect shape: the Java **parses** (so the default gate passes it
 * and the run reports success) and javac then rejects it. Its parameter name deliberately does not
 * match the field's, so [WildcardCaptureNormalizer] refuses it — which is the point: the pipeline is
 * asked to stop claiming success on output that does not build.
 */
class CompileCheckTest {

    @TempDir
    lateinit var tmp: Path

    /** A classes root holding one Kotlin (Metadata-annotated) target, plus the source it maps back to. */
    private fun subject(className: String, sourceName: String): Pair<Path, Path> {
        val classes = tmp.resolve("classes-$className")
        TestSupport.writeFacadeClassFile(classes, "accept/compilecheck", className, "$sourceName.kt")
        val sources = tmp.resolve("src")
        val kt = sources.resolve("accept/compilecheck/$sourceName.kt")
        Files.createDirectories(kt.parent)
        Files.writeString(kt, "package accept.compilecheck\n\nclass $sourceName\n")
        return classes to sources
    }

    /** A decompiler that emits exactly what the test wants to be judged. */
    private class FixedDecompiler(private val units: Map<String, String>) : Decompiler {
        override fun decompile(classFiles: List<Path>, classpath: List<Path>): Map<String, String> = units
    }

    private fun converter(className: String, text: String): K2j =
        converter(mapOf(className to text))

    private fun converter(units: Map<String, String>): K2j = K2j(
        surveyor = AsmSurveyor(),
        decompiler = FixedDecompiler(units),
        validator = JavacValidator(),
        writer = FileSystemWriter(),
        log = object : RunLog {
            override fun info(message: String) = println(message)
            override fun warn(message: String) = println("WARN: $message")
            override fun error(message: String, cause: Throwable?) = println("ERROR: $message")
        }
    )

    /** Parses, and javac then rejects the assignment: `props` does not match the field's name. */
    private val unprovable = """
        package accept.compilecheck;

        import java.util.Map;

        public final class Broken {
           private final Map<String, Number> properties;

           public Broken(Map<String, ? extends Number> props) {super();
              this.properties = props;
           }
        }
    """.trimIndent() + "\n"

    @Test
    fun `the default gate stays parse-only, so this unit converts`() {
        val (classes, sources) = subject("Broken", "Broken")
        val outputRoot = tmp.resolve("out-default")
        val manifest = converter("accept.compilecheck.Broken", unprovable).convert(
            ConversionRequest(
                classesRoot = classes,
                classpath = emptyList(),
                outputRoot = outputRoot,
                sourceRoots = listOf(sources)
            )
        )
        // The default is unchanged behaviour: parse-only, and this defect is invisible to it.
        assertTrue(manifest.success, "the parse gate must not have started rejecting this unit: ${manifest.failures}")
        assertTrue(Files.isRegularFile(outputRoot.resolve("accept/compilecheck/Broken.java")))
    }

    @Test
    fun `compile-check reports the javac diagnostic as a per-class failure and writes nothing`() {
        val (classes, sources) = subject("Broken", "Broken")
        val outputRoot = tmp.resolve("out-compile-check")
        val manifest = converter("accept.compilecheck.Broken", unprovable).convert(
            ConversionRequest(
                classesRoot = classes,
                classpath = emptyList(),
                outputRoot = outputRoot,
                sourceRoots = listOf(sources),
                compileCheck = true
            )
        )

        // A failure, with the phase, the class and javac's own message: file, line, message.
        assertFalse(manifest.success, "a unit javac rejects must fail the run")
        val failure = manifest.failures.single()
        assertEquals("accept.compilecheck.Broken", failure.className)
        assertEquals("COMPILE", failure.phase)
        assertTrue(failure.message.startsWith("Broken.java:"), "the diagnostic must start with the file: ${failure.message}")
        assertTrue(
            failure.message.contains("incompatible types"),
            "the real javac message must be carried: ${failure.message}"
        )
        assertTrue(
            Regex("""capture#\d+ of \? extends java\.lang\.Number|CAP#\d+ extends Number""")
                .containsMatchIn(failure.message),
            "javac's capture detail must be carried: ${failure.message}"
        )

        // ...and it was NOT written, and the source it came from was NOT deleted (the gate is
        // fail-closed exactly like the parse gate).
        assertFalse(Files.exists(outputRoot.resolve("accept/compilecheck/Broken.java")))
        assertTrue(manifest.converted.isEmpty(), "${manifest.converted}")
        assertTrue(manifest.deletableSources.isEmpty(), "${manifest.deletableSources}")
        val decision = manifest.sources.single()
        assertFalse(decision.deletable, decision.reason)
    }

    @Test
    fun `compile-classpath is what the compile gate resolves against`() {
        val (classes, sources) = subject("UsesDep", "UsesDep")
        // `Dep` is compiled into a directory of its own: it is NOT in the classes root, so the unit
        // can only compile when the caller supplies that directory.
        val deps = tmp.resolve("deps")
        TestSupport.compileJavaClass(deps, "accept.compilecheck", "Dep")
        val unit = """
            package accept.compilecheck;

            public final class UsesDep {
               public Dep dep() {
                  return null;
               }
            }
        """.trimIndent() + "\n"

        val without = tmp.resolve("out-no-cp")
        val failed = converter("accept.compilecheck.UsesDep", unit).convert(
            ConversionRequest(
                classesRoot = classes,
                classpath = emptyList(),
                outputRoot = without,
                sourceRoots = listOf(sources),
                compileCheck = true
            )
        )
        assertFalse(failed.success, "without the dependency the unit cannot compile")
        assertEquals("COMPILE", failed.failures.single().phase)
        assertTrue(
            failed.failures.single().message.contains("cannot find symbol"),
            failed.failures.single().message
        )
        assertFalse(Files.exists(without.resolve("accept/compilecheck/UsesDep.java")))

        val with = tmp.resolve("out-with-cp")
        val converted = converter("accept.compilecheck.UsesDep", unit).convert(
            ConversionRequest(
                classesRoot = classes,
                classpath = emptyList(),
                outputRoot = with,
                sourceRoots = listOf(sources),
                compileCheck = true,
                compileClasspath = listOf(deps)
            )
        )
        assertTrue(converted.success, "the supplied classpath must make the unit compile: ${converted.failures}")
        assertTrue(Files.isRegularFile(with.resolve("accept/compilecheck/UsesDep.java")))

        // The classes root is on the compile classpath too — that is where a generated unit's own
        // module types live — so a unit referring to a sibling in the same root compiles.
        val sibling = """
            package accept.compilecheck;

            public final class SelfRef {
               public Broken broken() {
                  return null;
               }
            }
        """.trimIndent() + "\n"
        val plainBroken = """
            package accept.compilecheck;

            import java.util.Map;

            public final class Broken {
               private final Map<String, Number> properties = null;

               public final Map<String, Number> getProperties() {
                  return this.properties;
               }
            }
        """.trimIndent() + "\n"
        val (siblingClasses, siblingSources) = subject("SelfRef", "SelfRef")
        TestSupport.writeFacadeClassFile(siblingClasses, "accept/compilecheck", "Broken", "Broken.kt")
        val selfOut = tmp.resolve("out-sibling")
        val selfManifest = converter(
            mapOf(
                "accept.compilecheck.SelfRef" to sibling,
                "accept.compilecheck.Broken" to plainBroken
            )
        ).convert(
            ConversionRequest(
                classesRoot = siblingClasses,
                classpath = emptyList(),
                outputRoot = selfOut,
                sourceRoots = listOf(siblingSources),
                compileCheck = true
            )
        )
        assertTrue(selfManifest.success, "a sibling in the classes root must resolve: ${selfManifest.failures}")
    }

    @Test
    fun `every classes directory of the module resolves in the compile gate`() {
        // The real defect (target module): the survey root is the Kotlin output
        // (build/classes/kotlin/main), while the module's *own* Java classes — annotations, helpers —
        // were compiled into the sibling output directory build/classes/java/main. A generated unit
        // that references one of them is rejected with `cannot find symbol` even though the class
        // file is on disk, because the compile gate only ever resolved against the single root the
        // survey used.
        val (classes, sources) = subject("UsesSibling", "UsesSibling")
        // The module's *other* classes directory: same source set, different output directory. It is
        // not the survey root and holds nothing Kotlin (no kotlin/Metadata) — exactly like
        // build/classes/java/main next to build/classes/kotlin/main.
        val sibling = tmp.resolve("classes-java-output")
        TestSupport.compileJavaClass(sibling, "accept.compilecheck", "Sibling")
        val unit = """
            package accept.compilecheck;

            public final class UsesSibling {
               public Sibling sibling() {
                  return null;
               }
            }
        """.trimIndent() + "\n"

        // Before: only the survey root is on the classpath, so the unit cannot resolve a type that
        // is sitting in the sibling directory. This is the pre-fix behaviour, and it is also the
        // proof that the default (classesRoots empty) is unchanged.
        val without = tmp.resolve("out-sibling-without")
        val failed = converter("accept.compilecheck.UsesSibling", unit).convert(
            ConversionRequest(
                classesRoot = classes,
                classpath = emptyList(),
                outputRoot = without,
                sourceRoots = listOf(sources),
                compileCheck = true
            )
        )
        assertFalse(failed.success, "a type in the other classes directory was resolvable before?")
        assertEquals("COMPILE", failed.failures.single().phase)
        assertTrue(
            failed.failures.single().message.contains("cannot find symbol"),
            failed.failures.single().message
        )
        assertFalse(Files.exists(without.resolve("accept/compilecheck/UsesSibling.java")))

        // After: the module declares every classes directory of its main source set, so the gate
        // resolves the sibling — and the unit converts.
        val with = tmp.resolve("out-sibling-with")
        val converted = converter("accept.compilecheck.UsesSibling", unit).convert(
            ConversionRequest(
                classesRoot = classes,
                classpath = emptyList(),
                outputRoot = with,
                sourceRoots = listOf(sources),
                compileCheck = true,
                classesRoots = listOf(sibling)
            )
        )
        assertTrue(
            converted.success,
            "every classes dir of the module must be on the compile gate's classpath: ${converted.failures}"
        )
        assertTrue(Files.isRegularFile(with.resolve("accept/compilecheck/UsesSibling.java")))
    }

    @Test
    fun `the checker itself reports position and message for a unit javac rejects`() {
        val errors = JavacCompileChecker().check("Broken.java", unprovable, emptyList())
        assertEquals(1, errors.size, errors.toString())
        assertTrue(
            Regex("""^Broken\.java:\d+:\d+: incompatible types: .*cannot be converted.*""")
                .containsMatchIn(errors.single()),
            "the diagnostic must carry file, line, column and the javac message: ${errors.single()}"
        )
    }

    // -- generated-source batch scenarios ----------------------------------------------------------
    //
    // The family this pass exists for: a caller that only calls a callee's *synthetic* constructor.
    // `target module` produces 56 such units — `no suitable constructor found for
    // Properties(java.util.Map,int,kotlin.jvm.internal.DefaultConstructorMarker)`, while the callee's
    // own generated `Properties.java` declares that constructor. javac does not offer a synthetic
    // member of a *class file* during overload resolution, and the first gate resolves against the
    // classes root — but in a real build the generated `.java` is ordinary source and the call
    // resolves. The tests below pin both directions: a blocked caller settles once the callee has been
    // written, and a unit that genuinely does not compile never does.

    /** The callee's *generated* unit: it declares a constructor the class file does not carry. */
    private val settleCallee = """
        package accept.compilecheck;

        public final class SettleCallee {
           public SettleCallee(int height, int width) {super();
           }
        }
    """.trimIndent() + "\n"

    /** The caller, whose only defect is the call: `SettleCallee` has no two-argument constructor yet. */
    private val settleCaller = """
        package accept.compilecheck;

        public final class SettleCaller {
           public Object make() {
              return new SettleCallee(1, 2);
           }
        }
    """.trimIndent() + "\n"

    /**
     * The classes root for a settle scenario: a facade (Metadata-annotated) class file for each name in
     * [facades], so exactly those units are survey targets, plus a *real* compiled `SettleCallee` whose
     * only constructor is the no-argument one — the class file a caller is judged against before the
     * tree is written.
     */
    private fun settleSubject(vararg facades: String): Triple<Path, Path, Path> {
        val classes = tmp.resolve("classes-settle")
        for (name in facades) {
            TestSupport.writeFacadeClassFile(classes, "accept/compilecheck", name, "$name.kt")
        }
        val sources = tmp.resolve("src-settle")
        val kt = sources.resolve("accept/compilecheck/SettleCaller.kt")
        Files.createDirectories(kt.parent)
        Files.writeString(kt, "package accept.compilecheck\n\nclass SettleCaller\n")
        val deps = tmp.resolve("deps-settle")
        TestSupport.compileJavaClass(deps, "accept.compilecheck", "SettleCallee")
        return Triple(classes, sources, deps)
    }

    /** The diagnostic the *main loop* produces: the classes root, no generated tree yet. */
    private fun mainLoopDiagnostic(fileName: String, text: String, deps: Path, classes: Path): String =
        JavacCompileChecker().check(
            fileName,
            text,
            listOf(tmp.resolve("no-tree-yet"), deps, classes)
        ).joinToString("; ")

    @Test
    fun `a generated caller and callee compile together in one batch`() {
        val (classes, sources, deps) = settleSubject("SettleCaller", "SettleCallee")
        val outputRoot = tmp.resolve("out-settle")

        val manifest = converter(
            mapOf(
                "accept.compilecheck.SettleCaller" to settleCaller,
                "accept.compilecheck.SettleCallee" to settleCallee
            )
        ).convert(
            ConversionRequest(
                classesRoot = classes,
                classpath = emptyList(),
                outputRoot = outputRoot,
                sourceRoots = listOf(sources),
                compileCheck = true,
                compileClasspath = listOf(deps)
            )
        )

        // Compiling the caller alone against the old class file reproduces the historical failure.
        val original = mainLoopDiagnostic("SettleCaller.java", settleCaller, deps, classes)
        assertTrue(
            original.contains("SettleCallee") && original.contains("cannot be applied to given types"),
            "the fixture must reproduce the real failure shape; got: $original"
        )
        // In the batch, the generated callee is present from the start and both units compile.
        assertTrue(
            manifest.success,
            "the caller must be reclassified: ${manifest.failures}"
        )
        assertEquals(
            listOf("accept.compilecheck.SettleCallee", "accept.compilecheck.SettleCaller"),
            manifest.converted.map { it.className }.sorted()
        )
        assertTrue(Files.isRegularFile(outputRoot.resolve("accept/compilecheck/SettleCaller.java")))
        assertTrue(Files.isRegularFile(outputRoot.resolve("accept/compilecheck/SettleCallee.java")))
        // The text written is the text that was gated, byte for byte.
        assertEquals(settleCaller, Files.readString(outputRoot.resolve("accept/compilecheck/SettleCaller.java")))
    }

    @Test
    fun `a genuinely invalid generated unit remains a failure with its batch diagnostic`() {
        val (classes, sources, deps) = settleSubject("SettleBroken", "SettleCallee")
        // Three arguments: no compilation surface declares that constructor — neither the class file
        // nor the generated unit the same run writes for the callee. Only a *text* change could make
        // this compile, and the batch gate must never invent that change.
        val broken = """
            package accept.compilecheck;

            public final class SettleBroken {
               public Object make() {
                  return new SettleCallee(1, 2, 3);
               }
            }
        """.trimIndent() + "\n"
        val outputRoot = tmp.resolve("out-settle-broken")

        val manifest = converter(
            mapOf(
                "accept.compilecheck.SettleBroken" to broken,
                "accept.compilecheck.SettleCallee" to settleCallee
            )
        ).convert(
            ConversionRequest(
                classesRoot = classes,
                classpath = emptyList(),
                outputRoot = outputRoot,
                sourceRoots = listOf(sources),
                compileCheck = true,
                compileClasspath = listOf(deps)
            )
        )

        assertFalse(manifest.success, "a unit that does not compile must stay a failure")
        assertEquals(
            listOf("accept.compilecheck.SettleBroken"),
            manifest.failures.map { it.className },
            "the caller must be the only failure: ${manifest.failures}"
        )
        val failure = manifest.failures.single()
        assertEquals("accept.compilecheck.SettleBroken", failure.className)
        assertEquals("COMPILE", failure.phase)
        // The generated callee was accepted and written; the caller's independent defect remains.
        assertTrue(Files.isRegularFile(outputRoot.resolve("accept/compilecheck/SettleCallee.java")))
        // The batch gate judges the caller against the generated callee, not the obsolete Kotlin
        // class file. It therefore reports the generated two-argument constructor while preserving
        // the actual defect: this caller supplies three arguments and remains rejected.
        assertTrue(
            failure.message.contains("required: int,int") && failure.message.contains("found:    int,int,int"),
            "the diagnostic must describe the generated source surface: ${failure.message}"
        )
        assertEquals(listOf("accept.compilecheck.SettleCallee"), manifest.converted.map { it.className })
        assertFalse(Files.exists(outputRoot.resolve("accept/compilecheck/SettleBroken.java")))
    }

    @Test
    fun `a caller whose dependency is absent from the generated batch remains a failure`() {
        val (classes, sources, deps) = settleSubject("SettleCaller", "SettleCallee")
        // The callee fails the parse gate and is absent from the batch, so the caller is blocked by a
        // missing generated dependency.
        val unparseableCallee = """
            package accept.compilecheck;

            public final class SettleCallee {
               )
            }
        """.trimIndent() + "\n"
        val outputRoot = tmp.resolve("out-settle-missing")

        val manifest = converter(
            mapOf(
                "accept.compilecheck.SettleCaller" to settleCaller,
                "accept.compilecheck.SettleCallee" to unparseableCallee
            )
        ).convert(
            ConversionRequest(
                classesRoot = classes,
                classpath = emptyList(),
                outputRoot = outputRoot,
                sourceRoots = listOf(sources),
                compileCheck = true,
                compileClasspath = listOf(deps)
            )
        )

        assertFalse(manifest.success, "a missing dependency must reject its caller")
        val caller = manifest.failures.single { it.className == "accept.compilecheck.SettleCaller" }
        assertEquals("COMPILE", caller.phase)
        assertEquals(mainLoopDiagnostic("SettleCaller.java", settleCaller, deps, classes), caller.message)
        assertTrue(manifest.failures.any { it.className == "accept.compilecheck.SettleCallee" && it.phase == "VALIDATE" })
        assertFalse(Files.exists(outputRoot.resolve("accept/compilecheck/SettleCaller.java")))
        assertFalse(Files.exists(outputRoot.resolve("accept/compilecheck/SettleCallee.java")))
        assertTrue(manifest.converted.isEmpty(), "${manifest.converted}")
    }

    @Test
    fun `transitive generated dependencies compile in the same batch`() {
        // A three-level chain, shaped like `properties.EnumValue` : `AbstractPropertyValue` :
        // `IProperties` in the real module: each level calls a constructor that exists only in the
        // generated unit above it. All three must resolve in one compilation surface.
        val classes = tmp.resolve("classes-chain")
        for (name in listOf("SettleBase", "SettleMid", "SettleLeaf")) {
            TestSupport.writeFacadeClassFile(classes, "accept/compilecheck", name, "$name.kt")
        }
        val sources = tmp.resolve("src-chain")
        Files.createDirectories(sources.resolve("accept/compilecheck"))

        val baseUnit = """
            package accept.compilecheck;

            public final class SettleBase {
               public SettleBase(int a, int b) {super();
               }
            }
        """.trimIndent() + "\n"
        // Compiling this unit alone against the old class file fails; the batch includes SettleBase.java.
        val midUnit = """
            package accept.compilecheck;

            public final class SettleMid {
               public SettleMid(int x) {super();
                  SettleBase base = new SettleBase(1, 2);
               }
            }
        """.trimIndent() + "\n"
        // This transitive caller resolves SettleMid.java from that same batch.
        val leafUnit = """
            package accept.compilecheck;

            public final class SettleLeaf {
               public SettleLeaf() {super();
                  SettleMid mid = new SettleMid(9);
               }
            }
        """.trimIndent() + "\n"

        val deps = tmp.resolve("deps-chain")
        TestSupport.compileJavaClass(deps, "accept.compilecheck", "SettleBase")
        TestSupport.compileJavaClass(deps, "accept.compilecheck", "SettleMid")

        val outputRoot = tmp.resolve("out-chain")
        val manifest = converter(
            mapOf(
                "accept.compilecheck.SettleBase" to baseUnit,
                "accept.compilecheck.SettleMid" to midUnit,
                "accept.compilecheck.SettleLeaf" to leafUnit
            )
        ).convert(
            ConversionRequest(
                classesRoot = classes,
                classpath = emptyList(),
                outputRoot = outputRoot,
                sourceRoots = listOf(sources),
                compileCheck = true,
                compileClasspath = listOf(deps)
            )
        )

        assertTrue(
            manifest.success,
            "every level of the chain must settle, the last one in the round after the one below it: " +
                "${manifest.failures}"
        )
        assertEquals(
            listOf(
                "accept.compilecheck.SettleBase",
                "accept.compilecheck.SettleLeaf",
                "accept.compilecheck.SettleMid"
            ),
            manifest.converted.map { it.className }.sorted()
        )
        for (name in listOf("SettleBase", "SettleMid", "SettleLeaf")) {
            assertTrue(Files.isRegularFile(outputRoot.resolve("accept/compilecheck/$name.java")))
        }
    }
}
