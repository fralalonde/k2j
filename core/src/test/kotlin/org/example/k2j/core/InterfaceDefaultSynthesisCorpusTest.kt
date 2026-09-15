package org.example.k2j.core

import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions
import org.junit.jupiter.api.Test

/**
 * The corpus fixture, on the **real** vendored FernFlower output: `accept/defaultimpls/
 * DefaultImplsBridge.kt` declares the shape that produced the largest defect family on `target module`
 * — a class inheriting an interface default through a sub-interface that narrowed the member's type,
 * which Kotlin bridges with a delegating method and FernFlower deletes as a bridge.
 *
 * The fixture is the minimal Kotlin shape that reproduces it (`BothBranches`): `Context.items` is
 * abstract over `List<Entry>` while `Aware.items` is a default over `List<Item>` with `Item : Entry`,
 * which Kotlin accepts (its `List<out E>` is covariant) and Java does not (its generics are
 * invariant). The class therefore has to declare `getItems()` itself, and the generated Java does
 * not — javac's own words, on the real module:
 *
 * ```
 * BothBranches.java:8:8: BothBranches is not abstract and does not override abstract method
 *     getItems() in accept.defaultimpls.Context
 * ```
 *
 * Each assertion is an axis that fails when the behaviour is reverted:
 *  - the real output must **parse** and must not declare the member at all, so a fixture that stopped
 *    reproducing the defect fails the test instead of passing quietly;
 *  - it must fail `javac` with exactly the family's diagnostic, so the defect is the one this pass
 *    owns and not an unrelated compile error;
 *  - the synthesized unit must parse **and compile**, so a pass that appends the wrong signature, the
 *    wrong delegation or a duplicate member fails it;
 *  - the synthesized text must be the generated text with the appended members and nothing else, so a
 *    transform that reformats, drops or reorders an existing member fails it.
 */
class InterfaceDefaultSynthesisCorpusTest {

    private val corpusClasses: Path
        get() = TestSupport.corpusClasses
            ?: error("corpus classes not built at D:/Work/k2j/corpus/app/build/classes/kotlin/main")

    /** The vendored FernFlower jar, as the plugin ships it (class-version 69 => needs Java 25). */
    private val decompilerJar: Path =
        Path.of("D:/Work/k2j/gradle-plugin/src/main/resources/k2j/java-decompiler.jar")

    /** The same Java 25 runtime probe the other corpus tests use. */
    private val runtimeHome: Path? = listOf(
        Path.of("C:/Users/FrancisLalonde/.rsdk/tools/java/25.0.2-jbr"),
        Path.of("C:/Users/FrancisLalonde/AppData/Local/Programs/IntelliJ IDEA Ultimate/jbr")
    ).firstOrNull { Files.isRegularFile(it.resolve("bin/java.exe")) }

    private val kotlinStdlib: Path? = TestSupport.cachedJar("kotlin-stdlib-2.4.0.jar")
    private val annotations: Path? = TestSupport.cachedJar("annotations-13.0.jar")

    private val compileClasspath: List<Path>?
        get() {
            val stdlib = kotlinStdlib
            val annotationsJar = annotations
            return if (stdlib != null && annotationsJar != null) {
                listOf(corpusClasses, stdlib, annotationsJar)
            } else {
                null
            }
        }

    private fun assumeReady() {
        Assumptions.assumeTrue(Files.isRegularFile(decompilerJar), "vendored java-decompiler.jar not found")
        Assumptions.assumeTrue(
            runtimeHome != null && Files.isDirectory(corpusClasses),
            "no Java 25 runtime (probed ${runtimeHome ?: "none"}) or no corpus classes"
        )
    }

    /** The real decompiler output of one fixture class file. */
    private fun decompile(classFile: String): String =
        FernFlowerDecompiler(decompilerJar, runtimeHome!!).decompile(
            listOf(corpusClasses.resolve("accept/defaultimpls/$classFile")),
            emptyList()
        ).getValue("accept.defaultimpls.${classFile.removeSuffix(".class")}")

    /** Compiles the unit the way the pipeline's gate does: the generated text, nothing else. */
    private fun compile(name: String, text: String): List<String> {
        val classpath = compileClasspath
        Assumptions.assumeTrue(classpath != null, "kotlin-stdlib-2.4.0.jar / annotations-13.0.jar not in the module cache")
        return TestSupport.compileJavaSources(mapOf("accept/defaultimpls/$name" to text), classpath!!)
    }

    private fun synthesize(classFile: String, text: String): String =
        InterfaceDefaultSynthesis.synthesize(corpusClasses.resolve("accept/defaultimpls/$classFile"), corpusClasses, text)

    // -- the fixture the family is shaped like --------------------------------------------------

    @Test
    fun `the real FernFlower output of the fixture fails javac and the synthesized unit compiles`() {
        assumeReady()
        val raw = decompile("BothBranches.class")

        // Axis 1: the defect is real — FernFlower kept the class and dropped both inherited defaults.
        assertFalse(raw.contains("getItems"), "FernFlower must have dropped the member:\n$raw")
        assertFalse(raw.contains("describe"), "FernFlower must have dropped the member:\n$raw")
        assertEquals(
            emptyList<String>(),
            JavacValidator().validate("BothBranches.java", raw),
            "the raw unit parses: the defect is semantic, which is why it is a COMPILE failure\n$raw"
        )

        // Axis 2: javac rejects it with exactly the family's diagnostic. Only `getItems` is named:
        // `describe(String)` is override-equivalent in Java too, so javac sees that default through —
        // and the bytecode still declares its delegating member, which is what axis 3 restores.
        val rawErrors = compile("BothBranches.java", raw)
        assertEquals(1, rawErrors.size, "the fixture must reproduce the family, got: $rawErrors")
        assertTrue(
            rawErrors.any {
                it.contains("is not abstract and does not override abstract method getItems() in accept.defaultimpls.Context")
            },
            "the getItems diagnostic must be javac's own: $rawErrors"
        )

        // Axis 3: the pass appends exactly the two delegating members and the unit compiles.
        val synthesized = synthesize("BothBranches.class", raw)
        assertTrue(
            synthesized.contains(
                "   public java.util.List getItems() {\n" +
                    "      return accept.defaultimpls.StartedEvent.super.getItems();\n   }"
            ),
            "the getter must delegate where the bytecode delegated:\n$synthesized"
        )
        assertTrue(
            synthesized.contains(
                "   public java.lang.String describe(java.lang.String arg0) {\n" +
                    "      return accept.defaultimpls.StartedEvent.super.describe(arg0);\n   }"
            ),
            "a parameterised, non-void member must keep its parameters:\n$synthesized"
        )
        assertEquals(emptyList<String>(), JavacValidator().validate("BothBranches.java", synthesized), synthesized)
        assertEquals(
            emptyList<String>(),
            compile("BothBranches.java", synthesized),
            "the synthesized unit must pass the gate that rejected the raw one"
        )

        // Axis 4: nothing but the appended members differs — the raw unit's prefix and suffix survive
        // byte for byte, and what sits between them is exactly the two appended members.
        val prefix = raw.substring(0, raw.lastIndexOf('}'))
        val suffix = raw.substring(raw.lastIndexOf('}'))
        assertTrue(synthesized.startsWith(prefix), "the unit's text before the closing brace must not move:\n$synthesized")
        assertTrue(synthesized.endsWith(suffix), "the closing brace and anything after it must not move:\n$synthesized")
        assertEquals(
            "\n   public java.util.List getItems() {\n" +
                "      return accept.defaultimpls.StartedEvent.super.getItems();\n   }\n" +
                "\n   public java.lang.String describe(java.lang.String arg0) {\n" +
                "      return accept.defaultimpls.StartedEvent.super.describe(arg0);\n   }\n",
            synthesized.removePrefix(prefix).removeSuffix(suffix),
            "exactly the two delegating members may be inserted"
        )
        // Idempotence: the text now declares them, so a second pass is a no-op.
        assertSame(synthesized, synthesize("BothBranches.class", synthesized))
    }

    @Test
    fun `two independent chains on one class are both satisfied`() {
        assumeReady()
        val raw = decompile("MultiInherited.class")

        val rawErrors = compile("MultiInherited.java", raw)
        assertTrue(
            rawErrors.any { it.contains("does not override abstract method getItems() in accept.defaultimpls.Context") },
            "the class must be rejected for the first chain's member: $rawErrors"
        )

        val synthesized = synthesize("MultiInherited.class", raw)
        assertTrue(
            synthesized.contains("return accept.defaultimpls.StartedEvent.super.getItems();"),
            "the first chain's getter:\n$synthesized"
        )
        assertTrue(
            synthesized.contains("return accept.defaultimpls.StartedEvent.super.describe(arg0);"),
            "the first chain's parameterised member:\n$synthesized"
        )
        assertTrue(
            synthesized.contains("return accept.defaultimpls.TagEvent.super.getTags();"),
            "the second chain's getter:\n$synthesized"
        )
        assertEquals(
            emptyList<String>(),
            compile("MultiInherited.java", synthesized),
            "a class with several such interfaces must compile once every chain is appended"
        )
    }

    @Test
    fun `a nested parameter type is spelled as a member of its interface`() {
        assumeReady()
        val raw = decompile("BoundImpl.class")

        // javac sees this default through (its signature is override-equivalent in Java too), so the
        // raw unit compiles — but the bytecode declares the delegating member, and a pass that dropped
        // it would be dropping a member the class file carries.
        val synthesized = synthesize("BoundImpl.class", raw)
        assertTrue(
            synthesized.contains(
                "   public java.util.List<accept.defaultimpls.Entry> bind(accept.defaultimpls.Binder.Marker arg0) {\n" +
                    "      return accept.defaultimpls.BoundEvent.super.bind(arg0);\n   }"
            ),
            "a nested parameter type must be rendered as Java source spells it, and the generic spelling" +
                " kept where every declaration spells it the same way:\n$synthesized"
        )
        assertEquals(emptyList<String>(), compile("BoundImpl.java", synthesized), synthesized)
    }

    // -- the refusals ----------------------------------------------------------------------------

    @Test
    fun `a class that already declares the member is left byte-identical`() {
        assumeReady()
        val raw = decompile("DeclaringClass.class")

        // The generated text declares both members (Kotlin's `override`), so the pass appends nothing
        // — this is the fixture's whole point.
        assertTrue(raw.contains("getItems()"), "the fixture must declare the member in the text:\n$raw")
        assertSame(raw, synthesize("DeclaringClass.class", raw))

        // The unit still fails javac, and for a reason this pass does not own: the declared member's
        // element type is the narrowed `List<Item>`, which Java's invariant generics do not accept as
        // an override of `List<Entry>`. Appending anything here would not help — which is exactly why
        // the pass appends nothing.
        val errors = compile("DeclaringClass.java", raw)
        assertTrue(
            errors.any { it.contains("return type java.util.List<accept.defaultimpls.Item> is not compatible with java.util.List<accept.defaultimpls.Entry>") },
            "the fixture must be the invariant-generics defect, not this one: $errors"
        )
    }

    @Test
    fun `a default the superclass provides is left byte-identical`() {
        assumeReady()
        val raw = decompile("SubclassOfProvider.class")

        // The superclass satisfies the interface; an override in the subclass delegating to the
        // interface default would bypass it, so this unit is refused and left to the gate.
        assertSame(raw, synthesize("SubclassOfProvider.class", raw))
    }

    @Test
    fun `a generic default whose Signature carries a type variable is left byte-identical`() {
        assumeReady()
        val raw = decompile("GenImpl.class")

        assertFalse(raw.contains("generate"), "FernFlower must have dropped the member:\n$raw")
        assertSame(
            raw,
            synthesize("GenImpl.class", raw),
            "a type variable this pass cannot render without the declaration site is a refusal"
        )
    }

    @Test
    fun `a unit whose class file carries nothing to synthesize is returned as the same instance`() {
        assumeReady()
        val raw = decompile("Entry.class")

        assertSame(raw, synthesize("Entry.class", raw))
    }

    @Test
    fun `an interface default super call keeps its bytecode owner and then compiles`() {
        assumeReady()
        val raw = decompile("CancellationEvent.class")

        assertTrue(raw.contains("super.getCancellationContext()"), "the fixture must reproduce FernFlower's lost owner:\n$raw")
        val rawErrors = compile("CancellationEvent.java", raw)
        assertTrue(rawErrors.any { it.contains("cannot find symbol") }, "javac must reject the ownerless call: $rawErrors")

        val repaired = synthesize("CancellationEvent.class", raw)
        assertEquals(
            raw.replace(
                "super.getCancellationContext()",
                "CancellationDefaults.super.getCancellationContext()"
            ),
            repaired,
            "only the bytecode-named interface owner may be restored"
        )
        assertEquals(emptyList<String>(), compile("CancellationEvent.java", repaired), repaired)
        assertSame(repaired, synthesize("CancellationEvent.class", repaired))
    }

    @Test
    fun `an interface default super call inside an interface keeps its bytecode owner and compiles`() {
        assumeReady()
        val raw = decompile("ChildDefaults.class")

        // The defect: a Kotlin interface default that super-calls its parent default decompiles
        // with the owner dropped, and javac rejects the bare `super.label` inside an interface.
        assertTrue(raw.contains("super.getLabel()"), "the fixture must reproduce FernFlower's lost owner:\n$raw")
        val rawErrors = compile("ChildDefaults.java", raw)
        assertTrue(
            rawErrors.any { it.contains("cannot find symbol") && it.contains("super") },
            "javac must reject the ownerless call inside the interface: $rawErrors"
        )

        val repaired = synthesize("ChildDefaults.class", raw)
        assertEquals(
            raw.replace("super.getLabel()", "ParentDefaults.super.getLabel()"),
            repaired,
            "only the bytecode-named interface owner may be restored"
        )
        assertEquals(emptyList<String>(), compile("ChildDefaults.java", repaired), repaired)
        assertSame(repaired, synthesize("ChildDefaults.class", repaired))
    }

    @Test
    fun `an unrelated corpus unit is returned as the same instance`() {
        assumeReady()
        val classFile = corpusClasses.resolve("accept/plain/Single.class")
        val raw = FernFlowerDecompiler(decompilerJar, runtimeHome!!).decompile(
            listOf(classFile),
            emptyList()
        ).getValue("accept.plain.Single")

        assertSame(raw, InterfaceDefaultSynthesis.synthesize(classFile, corpusClasses, raw))
    }
}
