package org.example.k2j.core

import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * The corpus fixture, on the **real** vendored FernFlower output: `accept/rawtypes/` in the corpus
 * project declares the three shapes the raw-type fallback exists for, and the tests below drive the
 * real pipeline over their class files.
 *
 * The three units, and the Kotlin that produces them:
 *
 * - `NarrowContext` — shape **(a)**, the method clash. `NarrowContext : WideContext` narrows
 *   `items` from `List<Entry>` to `List<Item>`, which Kotlin accepts (its `List<out E>` is
 *   covariant) and Java does not. javac: `getItems() in NarrowContext clashes with getItems() in
 *   WideContext; return type List<Item> is not compatible with List<Entry>`. The declaration is in
 *   the unit, so its return type is the one thing that may go raw;
 * - `ConflictingProvider` — shape **(b)**, the incompatible supertypes. The abstract class implements
 *   `Provider<Entry>` and `OtherProvider<Item>`, both **named with their type arguments in its own
 *   clause**, and their `provide()` members return `List<Entry>` and `List<Item>`: related in Kotlin,
 *   unrelated in invariant Java. javac: `types OtherProvider<Item> and Provider<Entry> are
 *   incompatible; both define provide(), but with unrelated return types`. Making those two named
 *   supertypes raw — and the clause only — makes both members erase to `List provide()`;
 * - `RefusedSupertypes` — the **refusal**. It reaches the same two interfaces through
 *   `EntryProvider`/`ItemProvider`, so javac names the *transitive* supertypes while the clause
 *   spells its own supertypes without type arguments. There is nothing in the unit to make raw, and
 *   the fallback must leave it byte-identical — this is exactly the shape of the real module's
 *   `StorageCategory`, `EquipmentCategory` and `*DeviceVariant` enums.
 *
 * Each assertion is an axis that fails when the behaviour is reverted: the raw unit must really fail
 * javac with *that* wording (or the fixture stopped reproducing the defect); the repaired text must
 * compile and must compile *unchanged* by the gate; the repair must be the minimal single-declaration
 * edit, asserted against the text itself; and the refused unit must stay a failure carrying the
 * diagnostic of the first attempt, with no file written for it.
 */
class RawTypeFallbackCorpusTest {

    @TempDir
    lateinit var tmp: Path

    /** The checkout root, resolved from Gradle's working directory (the `core` project directory). */
    private val root: Path = listOf(Path.of(".."), Path.of("."))
        .map { it.toAbsolutePath().normalize() }
        .firstOrNull { Files.isDirectory(it.resolve("corpus/app")) }
        ?: Path.of("..").toAbsolutePath().normalize()

    private val corpusClasses: Path = root.resolve("corpus/app/build/classes/kotlin/main")
    private val corpusSources: Path = root.resolve("corpus/app/src/main/kotlin")
    private val decompilerJar: Path = root.resolve("gradle-plugin/src/main/resources/k2j/java-decompiler.jar")

    /** The jar-class-version-69 decompiler needs a Java 25 runtime; the tests probe the two known ones. */
    private val runtimeHome: Path? = listOf(
        Path.of("C:/Users/FrancisLalonde/.rsdk/tools/java/25.0.2-jbr"),
        Path.of("C:/Users/FrancisLalonde/AppData/Local/Programs/IntelliJ IDEA Ultimate/jbr")
    ).firstOrNull { Files.isRegularFile(it.resolve("bin/java.exe")) }

    private val kotlinStdlib: Path? get() = TestSupport.cachedJar("kotlin-stdlib-2.4.0.jar")
    private val annotations: Path? get() = TestSupport.cachedJar("annotations-13.0.jar")

    private fun assumeReady() {
        Assumptions.assumeTrue(Files.isRegularFile(decompilerJar), "vendored java-decompiler.jar not found")
        Assumptions.assumeTrue(runtimeHome != null, "no Java 25 runtime (probed ${runtimeHome ?: "none"})")
        Assumptions.assumeTrue(
            Files.isRegularFile(corpusClasses.resolve("accept/rawtypes/NarrowContext.class")),
            "the accept/rawtypes corpus fixture is not built at $corpusClasses"
        )
        Assumptions.assumeTrue(
            kotlinStdlib != null && annotations != null,
            "kotlin-stdlib-2.4.0.jar / annotations-13.0.jar not in the module cache"
        )
    }

    /** What the compile gate resolves against: the fixture's own classes and the two jars it imports. */
    private val compileClasspath: List<Path>
        get() = listOf(corpusClasses, kotlinStdlib!!, annotations!!)

    /** The real decompiler output of the whole fixture, one FernFlower context, as a run would do it. */
    private val decompiled: Map<String, String> by lazy {
        val fixtures = Files.list(corpusClasses.resolve("accept/rawtypes")).use { stream ->
            stream.filter { it.fileName.toString().endsWith(".class") }.sorted().toList()
        }
        // No library classpath: the fixture's classes are the *sources* here, and a classes directory
        // that holds them must never be handed to FernFlower as a library — a class file that is both
        // a source and a library class is dropped, and the run then reports "no unit" for it.
        FernFlowerDecompiler(decompilerJar, runtimeHome!!).decompile(fixtures, emptyList())
    }

    private fun unit(className: String): String =
        decompiled["accept.rawtypes.$className"] ?: error("no unit for $className in ${decompiled.keys}")

    /** The gate, on one generated unit, exactly as the pipeline calls it. */
    private fun gate(className: String, text: String): List<String> =
        JavacCompileChecker().check("$className.java", text, compileClasspath)

    private fun log(): RunLog = object : RunLog {
        override fun info(message: String) = println(message)
        override fun warn(message: String) = println("WARN: $message")
        override fun error(message: String, cause: Throwable?) = println("ERROR: $message")
    }

    // -- shape (a): the method clash -------------------------------------------------------------

    @Test
    fun `shape a - the real fixture output fails the gate with javac's wording and the repair compiles`() {
        assumeReady()
        val raw = unit("NarrowContext")

        // The defect is semantic, not syntactic: the parse gate cannot see it, which is why the unit
        // ever reaches the compile gate.
        assertEquals(emptyList<String>(), JavacValidator().validate("NarrowContext.java", raw), raw)

        val rawErrors = gate("NarrowContext", raw)
        assertEquals(
            listOf(
                "NarrowContext.java:8:15: getItems() in accept.rawtypes.NarrowContext clashes with " +
                    "getItems() in accept.rawtypes.WideContext\n" +
                    "  return type java.util.List<accept.rawtypes.Item> is not compatible with " +
                    "java.util.List<accept.rawtypes.Entry>"
            ),
            rawErrors,
            "the fixture must reproduce the family, in javac's own words"
        )

        val repair = RawTypeFallback.repair("NarrowContext.java", raw, rawErrors)
        assertTrue(repair != null, "javac named getItems(), which this unit declares")
        assertEquals(
            listOf("getItems() return type `List<Item>` -> `List`"),
            repair!!.notes
        )
        assertEquals(
            emptyList<String>(),
            gate("NarrowContext", repair.text),
            "the repaired unit must pass the gate that rejected the raw one:\n${repair.text}"
        )
        assertEquals(emptyList<String>(), JavacValidator().validate("NarrowContext.java", repair.text))
    }

    @Test
    fun `shape a - the repair is minimal, asserted against the text itself`() {
        assumeReady()
        val raw = unit("NarrowContext")
        val repair = RawTypeFallback.repair("NarrowContext.java", raw, gate("NarrowContext", raw))!!

        // The expected repair, computed independently from the fixture: the named declaration's type
        // arguments removed, every other byte the same.
        val expected = raw.replace("List<Item> getItems();", "List getItems();")
        assertTrue(expected != raw, "the fixture must contain the shape")
        assertEquals(expected, repair.text, "the repair must change the named declaration and nothing else")

        // ...and structurally: one line differs, it is the one that declares `getItems`, it loses
        // exactly one type-argument list, and no other type argument in the unit moved.
        assertMinimal(raw, repair.text, "getItems", 1)
        assertEquals(1, raw.count { it == '>' } - repair.text.count { it == '>' })
        assertFalse(repair.text.contains("List<Item>"), repair.text)
    }

    // -- shape (b): the incompatible supertypes --------------------------------------------------

    @Test
    fun `shape b - the real fixture output fails the gate with javac's wording and the repair compiles`() {
        assumeReady()
        val raw = unit("ConflictingProvider")

        assertEquals(emptyList<String>(), JavacValidator().validate("ConflictingProvider.java", raw), raw)

        val rawErrors = gate("ConflictingProvider", raw)
        assertEquals(
            listOf(
                "ConflictingProvider.java:3:17: types accept.rawtypes.OtherProvider<accept.rawtypes.Item> " +
                    "and accept.rawtypes.Provider<accept.rawtypes.Entry> are incompatible;\n" +
                    "  both define provide(), but with unrelated return types"
            ),
            rawErrors,
            "the fixture must reproduce the family, in javac's own words"
        )

        val repair = RawTypeFallback.repair("ConflictingProvider.java", raw, rawErrors)
        assertTrue(repair != null, "the unit's own clause names both interfaces with their arguments")
        assertEquals(
            listOf(
                "Provider made raw in the supertype clause: `Provider<Entry>` -> `Provider`",
                "OtherProvider made raw in the supertype clause: `OtherProvider<Item>` -> `OtherProvider`"
            ),
            repair!!.notes
        )
        assertEquals(emptyList<String>(), gate("ConflictingProvider", repair.text), repair.text)
        assertEquals(emptyList<String>(), JavacValidator().validate("ConflictingProvider.java", repair.text))
    }

    @Test
    fun `shape b - the repair is minimal, asserted against the text itself`() {
        assumeReady()
        val raw = unit("ConflictingProvider")
        val repair =
            RawTypeFallback.repair("ConflictingProvider.java", raw, gate("ConflictingProvider", raw))!!

        val expected = raw.replace(
            "implements Provider<Entry>, OtherProvider<Item>",
            "implements Provider, OtherProvider"
        )
        assertTrue(expected != raw, "the fixture must contain the shape")
        assertEquals(expected, repair.text, "only the clause may change, and only by the two arguments")
        assertMinimal(raw, repair.text, "implements", 2)
    }

    // -- the refusal ------------------------------------------------------------------------------

    @Test
    fun `the unit javac cannot be satisfied for is left byte-identical and reported as it was`() {
        assumeReady()
        val raw = unit("RefusedSupertypes")
        val rawErrors = gate("RefusedSupertypes", raw)

        // The real diagnostic, and the same one the module's `StorageCategory` and `*DeviceVariant`
        // enums produced: the names are the transitive supertypes.
        assertEquals(
            listOf(
                "RefusedSupertypes.java:3:17: types accept.rawtypes.OtherProvider<accept.rawtypes.Item> " +
                    "and accept.rawtypes.Provider<accept.rawtypes.Entry> are incompatible;\n" +
                    "  both define provide(), but with unrelated return types"
            ),
            rawErrors,
            "the fixture must reproduce the family, in javac's own words"
        )

        assertNull(
            RawTypeFallback.repair("RefusedSupertypes.java", raw, rawErrors),
            "neither name is in this unit's clause with type arguments, so there is nothing to make raw"
        )
    }

    // -- the pipeline over the whole fixture ------------------------------------------------------

    @Test
    fun `the pipeline converts the two repairable units, refuses the third, and says so in the manifest`() {
        assumeReady()
        val outputRoot = tmp.resolve("out")
        val manifest = K2j(
            surveyor = AsmSurveyor(),
            decompiler = FernFlowerDecompiler(decompilerJar, runtimeHome!!),
            validator = JavacValidator(),
            writer = FileSystemWriter(),
            log = log()
        ).convert(
            ConversionRequest(
                classesRoot = corpusClasses,
                classpath = listOf(kotlinStdlib!!),
                outputRoot = outputRoot,
                packages = listOf("accept.rawtypes"),
                sourceRoots = listOf(corpusSources),
                compileCheck = true,
                compileClasspath = listOf(kotlinStdlib!!, annotations!!)
            )
        )

        // Exactly one unit fails, and it is the one the fallback refused — with the diagnostic of the
        // first attempt, not of any rewrite.
        assertEquals(listOf("accept.rawtypes.RefusedSupertypes"), manifest.failures.map { it.className })
        val failure = manifest.failures.single()
        assertEquals("COMPILE", failure.phase)
        assertEquals(
            gate("RefusedSupertypes", unit("RefusedSupertypes")).joinToString("; "),
            failure.message,
            "the refused unit keeps javac's own diagnostic"
        )
        assertFalse(Files.exists(outputRoot.resolve("accept/rawtypes/RefusedSupertypes.java")))

        // The other nine convert, the two repairable ones among them.
        assertEquals(9, manifest.converted.size, manifest.converted.map { it.className }.toString())
        assertTrue(manifest.converted.any { it.className == "accept.rawtypes.NarrowContext" })
        assertTrue(manifest.converted.any { it.className == "accept.rawtypes.ConflictingProvider" })

        // What was written is the repaired text — never the raw text, and never silently: each repair
        // is a warning naming the declaration that was made raw and the diagnostic that forced it.
        assertEquals(
            unit("NarrowContext").replace("List<Item> getItems();", "List getItems();"),
            Files.readString(outputRoot.resolve("accept/rawtypes/NarrowContext.java"))
        )
        assertEquals(
            unit("ConflictingProvider").replace(
                "implements Provider<Entry>, OtherProvider<Item>",
                "implements Provider, OtherProvider"
            ),
            Files.readString(outputRoot.resolve("accept/rawtypes/ConflictingProvider.java"))
        )
        assertEquals(2, manifest.warnings.size, manifest.warnings.toString())
        assertTrue(
            manifest.warnings.any { it.startsWith("raw-type fallback: accept.rawtypes.NarrowContext: ") },
            manifest.warnings.toString()
        )
        assertTrue(
            manifest.warnings.any { it.startsWith("raw-type fallback: accept.rawtypes.ConflictingProvider: ") },
            manifest.warnings.toString()
        )
        assertFalse(manifest.success, "one unit genuinely does not compile, so the run is not a success")

        // The written tree is compilable as a whole — the two repaired units included, and with no
        // class file of the fixture on the classpath to stand in for any of them.
        val generated = Files.walk(outputRoot.resolve("accept/rawtypes")).use { stream ->
            stream.filter { it.fileName.toString().endsWith(".java") }.sorted().toList()
        }
        assertEquals(9, generated.size, generated.toString())
        assertEquals(
            emptyList<String>(),
            TestSupport.compileJavaSources(
                generated.associate { it.fileName.toString() to Files.readString(it) },
                listOf(annotations!!)
            ),
            "the tree the run wrote must compile"
        )
    }

    // -- helpers ---------------------------------------------------------------------------------

    /**
     * The minimality assertion: between [raw] and [repaired] exactly one line differs, it is the line
     * that carries the named declaration, it loses exactly [deletions] type-argument lists, no other
     * type argument in the unit is dropped, and every other line is byte-identical.
     */
    private fun assertMinimal(raw: String, repaired: String, named: String, deletions: Int) {
        val rawLines = raw.lines()
        val repairedLines = repaired.lines()
        assertEquals(rawLines.size, repairedLines.size, "no line may be added or removed")
        val changed = rawLines.zip(repairedLines).withIndex().filter { it.value.first != it.value.second }
        assertEquals(1, changed.size, "exactly one line may change, got ${changed.map { it.index + 1 }}")
        val line = changed.single()
        assertTrue(
            line.value.first.contains(named),
            "the changed line must be the named declaration: ${line.value.first}"
        )
        assertEquals(
            deletions,
            line.value.first.count { it == '<' } - line.value.second.count { it == '<' },
            "the changed line must lose exactly the named type-argument lists"
        )
        for (index in rawLines.indices) {
            if (index == line.index) continue
            assertEquals(rawLines[index], repairedLines[index], "line ${index + 1} must not move")
        }
        assertTrue(repaired.length < raw.length, "a raw-type repair only ever removes text")
    }
}
