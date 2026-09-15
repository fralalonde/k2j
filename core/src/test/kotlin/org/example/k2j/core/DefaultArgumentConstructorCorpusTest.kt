package org.example.k2j.core

import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions
import org.junit.jupiter.api.Test

/**
 * [DefaultArgumentConstructorNormalizer] on the **real** FernFlower output of the corpus fixture
 * `corpus/app/src/main/kotlin/accept/defaultargs/` — the shapes that failed on `target module`.
 *
 * The fixture holds three classes, one per shape measured in that module's `k2j-failures` dump:
 *
 * * `RandomDefaultId` — the `ActivityId` shape
 *   (`data class ActivityId(override val objectId: UUID) : IObjectId { constructor() : this(UUID.randomUUID()) }`):
 *   a secondary no-argument constructor whose **delegation argument is a call on a platform type**,
 *   so the compiler's null check forces the value through a temporary local.
 * * `RandomIdList` — the `AssetBarcodeList` shape (`constructor(n: Int) : super(arrayListOf(UUID.randomUUID()))`),
 *   where the reference sits inside a larger `super(...)` argument expression and the spill is an array.
 * * `DefaultArgumentIds` — the shape a Kotlin **default argument** takes under Kotlin 2.4.0: the
 *   no-argument constructor delegates to the synthetic `(UUID, int, DefaultConstructorMarker)` one
 *   and the default is applied *after* the delegation, to a declared parameter. It is the
 *   corpus-level negative control: the transform must return it byte-identical.
 *
 * The chain under test is what `K2j` wires: FernFlower's own output is the compiler's statement
 * order (spill and null-check first, delegation last), which javac rejects with *call to this must
 * be first statement in constructor*; [ConstructorNormalizer] then hoists the delegation, which is
 * where the `cannot find symbol: variable varN` diagnostic in the dump comes from. The normalizer
 * under test repairs **both** orders, so these tests drive it with the raw text and with the hoisted
 * text and require the same conclusion from each.
 */
class DefaultArgumentConstructorCorpusTest {

    private val corpusClasses: Path
        get() = TestSupport.corpusClasses
            ?: error("corpus classes not built at D:/Work/k2j/corpus/app/build/classes/kotlin/main")

    /** The vendored FernFlower jar, as the plugin ships it (class-version 69 => needs Java 25). */
    private val decompilerJar: Path =
        Path.of("D:/Work/k2j/gradle-plugin/src/main/resources/k2j/java-decompiler.jar")

    /** The same Java 25 probe [EnumNormalizerCorpusTest] uses: the rsdk toolchain, then the IDE JBR. */
    private val runtimeHome: Path? = listOf(
        Path.of("C:/Users/FrancisLalonde/.rsdk/tools/java/25.0.2-jbr"),
        Path.of("C:/Users/FrancisLalonde/AppData/Local/Programs/IntelliJ IDEA Ultimate/jbr")
    ).firstOrNull { Files.isRegularFile(it.resolve("bin/java.exe")) }

    /** The libraries the generated units import: kotlin-stdlib and the JetBrains annotations. */
    private val kotlinStdlib: Path? = TestSupport.cachedJar("kotlin-stdlib-2.4.0.jar")
    private val annotations: Path? = TestSupport.cachedJar("annotations-13.0.jar")
    private val compileClasspath: List<Path>? =
        if (kotlinStdlib != null && annotations != null) listOf(kotlinStdlib, annotations) else null

    private fun assumeReady() {
        Assumptions.assumeTrue(
            Files.isRegularFile(decompilerJar),
            "vendored java-decompiler.jar not found at $decompilerJar"
        )
        Assumptions.assumeTrue(
            runtimeHome != null && Files.isDirectory(corpusClasses),
            "no Java 25 runtime (probed ${runtimeHome ?: "none"}) or no corpus classes at $corpusClasses"
        )
        Assumptions.assumeTrue(
            compileClasspath != null,
            "kotlin-stdlib-2.4.0.jar / annotations-13.0.jar not in the Gradle module cache"
        )
    }

    /** The real FernFlower text of the named fixture class files, keyed by qualified class name. */
    private fun decompile(vararg classFiles: String): Map<String, String> =
        FernFlowerDecompiler(decompilerJar, runtimeHome!!).decompile(
            classFiles.map { corpusClasses.resolve("accept/defaultargs/$it") },
            emptyList()
        )

    private fun parseErrors(name: String, text: String): List<String> = JavacValidator().validate(name, text)

    private fun compile(units: Map<String, String>): List<String> =
        TestSupport.compileJavaSources(units, compileClasspath!!)

    /** The line separator of a unit, so the assertions hold whatever the checkout's line endings are. */
    private val String.nl: String get() = if (contains("\r\n")) "\r\n" else "\n"

    /**
     * Byte-exact proof that the transform did exactly the listed splices: applying them to the input
     * by hand reproduces the normalized unit character for character.
     */
    private fun assertSpliced(raw: String, normalized: String, vararg edits: Pair<String, String>) {
        var expected = raw
        for ((old, new) in edits) {
            assertTrue(expected.contains(old), "the raw unit must contain <$old>:\n$raw")
            expected = expected.replace(old, new)
        }
        assertEquals(expected, normalized, "only the matched spans may change:\n$normalized")
    }

    /** A caller of the no-argument constructor the ActivityId shape repairs. */
    private val randomDefaultIdCaller = """
        package accept.defaultargs;

        import java.util.UUID;

        public final class UseRandomDefaultId {
           public int use(UUID id) {
              RandomDefaultId viaDefault = new RandomDefaultId();
              RandomDefaultId viaPrimary = new RandomDefaultId(id);
              return viaDefault.hex().length() + viaPrimary.component1().hashCode()
                 + viaPrimary.getObjectId().toString().length();
           }
        }
    """.trimIndent() + "\n"

    /** A caller of the `super(...)` constructor the AssetBarcodeList shape repairs. */
    private val randomIdListCaller = """
        package accept.defaultargs;

        public final class UseRandomIdList {
           public int use() {
              RandomIdList list = new RandomIdList(1);
              return list.size();
           }
        }
    """.trimIndent() + "\n"

    /** A caller of the default-argument constructor, which must keep working untouched. */
    private val defaultArgumentIdsCaller = """
        package accept.defaultargs;

        import java.util.UUID;

        public final class UseDefaultArgumentIds {
           public int use(UUID id) {
              DefaultArgumentIds viaDefault = new DefaultArgumentIds();
              DefaultArgumentIds viaPrimary = new DefaultArgumentIds(id);
              return viaDefault.hex().length() + viaPrimary.getId().hashCode();
           }
        }
    """.trimIndent() + "\n"

    /** All three APIs at once, for the package-level test. */
    private val allCaller = """
        package accept.defaultargs;

        import java.util.UUID;

        public final class UseDefaultArgs {
           public int use(UUID id) {
              RandomDefaultId viaDefault = new RandomDefaultId();
              RandomDefaultId viaPrimary = new RandomDefaultId(id);
              RandomIdList list = new RandomIdList(1);
              DefaultArgumentIds ids = new DefaultArgumentIds();
              DefaultArgumentIds idsOf = new DefaultArgumentIds(id);
              return viaDefault.hex().length() + viaPrimary.component1().hashCode()
                 + list.size() + ids.hex().length() + idsOf.getId().hashCode();
           }
        }
    """.trimIndent() + "\n"

    // -- the ActivityId shape ---------------------------------------------------------------------

    @Test
    fun `the raw unit is rejected for the ordering rule, the hoisted one for the missing local, and the repaired one compiles`() {
        assumeReady()
        val units = decompile("RandomDefaultId.class", "HasObjectId.class")
        val raw = units.getValue("accept.defaultargs.RandomDefaultId")
        val iface = units.getValue("accept.defaultargs.HasObjectId")
        val nl = raw.nl
        val withCaller = { unit: String ->
            mapOf(
                "accept/defaultargs/RandomDefaultId.java" to unit,
                "accept/defaultargs/HasObjectId.java" to iface,
                "accept/defaultargs/UseRandomDefaultId.java" to randomDefaultIdCaller
            )
        }

        // Axis 1: the raw FernFlower text PARSES — the parse gate the pipeline runs by default cannot
        // see this defect — and carries the compiler's order, which javac rejects for Java's rule.
        assertEquals(emptyList<String>(), parseErrors("RandomDefaultId.java", raw), raw)
        assertTrue(
            raw.contains(
                "public RandomDefaultId() {$nl      UUID var10001 = UUID.randomUUID();$nl" +
                    "      Intrinsics.checkNotNullExpressionValue(var10001, \"randomUUID(...)\");$nl" +
                    "      this(var10001);"
            ),
            "the raw unit must carry the spill before the delegation:\n$raw"
        )
        val rawErrors = compile(withCaller(raw))
        assertTrue(
            rawErrors.any { it.contains("call to this must be first statement") },
            "javac must reject the raw unit for the delegation's position; got $rawErrors"
        )
        // The same unit also carries the defect [ConstructorNormalizer] owns (a parameter null-check
        // before the primary constructor's `super()`), which is why the compile axis has to run the
        // chain the pipeline wires, not this transform alone.
        assertTrue(
            rawErrors.any { it.contains("call to super must be first statement") },
            "the raw unit must also show the pre-existing super() ordering defect; got $rawErrors"
        )

        // The pipeline's first repair hoists the delegation; that is the shape the dump shows and the
        // shape whose diagnostic (`cannot find symbol: variable var10001`) the real module reported.
        val hoisted = ConstructorNormalizer.normalize(raw)
        assertTrue(hoisted.contains("public RandomDefaultId() {this(var10001);"), hoisted)
        val hoistedErrors = compile(withCaller(hoisted))
        assertTrue(
            hoistedErrors.any { it.contains("cannot find symbol") && it.contains("var10001") },
            "the hoisted unit must reproduce the real module's diagnostic; got $hoistedErrors"
        )

        // Axis 2: the repair. The chain the pipeline wires compiles, and so does the opposite
        // composition — this transform repairs FernFlower's own order as well as the hoisted one.
        val fixed = DefaultArgumentConstructorNormalizer.normalize(hoisted)
        val otherOrder = ConstructorNormalizer.normalize(DefaultArgumentConstructorNormalizer.normalize(raw))
        for (repaired in listOf(fixed, otherOrder)) {
            assertTrue(repaired.contains("this(java.util.Objects.requireNonNull(UUID.randomUUID(), \"randomUUID(...)\"));"), repaired)
            assertFalse(repaired.contains("var10001"), "the synthetic local must be gone:\n$repaired")
            assertEquals(emptyList<String>(), parseErrors("RandomDefaultId.java", repaired), repaired)
            assertEquals(
                emptyList<String>(),
                compile(withCaller(repaired)),
                "the normalized unit and a caller of its API must compile:\n$repaired"
            )
            assertDelegationFirst(repaired, "public RandomDefaultId() {", "this(java.util.Objects.requireNonNull(UUID.randomUUID(), \"randomUUID(...)\"));")
        }

        // Axis 3: nothing else moved — the reference is inlined and the two statements are deleted.
        assertSpliced(
            raw,
            DefaultArgumentConstructorNormalizer.normalize(raw),
            "this(var10001);" to "this(java.util.Objects.requireNonNull(UUID.randomUUID(), \"randomUUID(...)\"));",
            "UUID var10001 = UUID.randomUUID();" to "",
            "Intrinsics.checkNotNullExpressionValue(var10001, \"randomUUID(...)\");" to ""
        )
        assertSpliced(
            hoisted,
            fixed,
            "this(var10001);" to "this(java.util.Objects.requireNonNull(UUID.randomUUID(), \"randomUUID(...)\"));",
            "UUID var10001 = UUID.randomUUID();" to "",
            "Intrinsics.checkNotNullExpressionValue(var10001, \"randomUUID(...)\");" to ""
        )
    }

    // -- the AssetBarcodeList shape: the reference nested in a super(...) argument list -------------

    @Test
    fun `the nested super reference is inlined and the array local is gone`() {
        assumeReady()
        val raw = decompile("RandomIdList.class").getValue("accept.defaultargs.RandomIdList")
        val nl = raw.nl
        val withCaller = { unit: String ->
            mapOf(
                "accept/defaultargs/RandomIdList.java" to unit,
                "accept/defaultargs/UseRandomIdList.java" to randomIdListCaller
            )
        }

        assertEquals(emptyList<String>(), parseErrors("RandomIdList.java", raw), raw)
        assertTrue(
            raw.contains(
                "public RandomIdList(int count) {$nl      UUID[] var2 = new UUID[]{UUID.randomUUID()};$nl" +
                    "      super((Collection)CollectionsKt.arrayListOf(var2));"
            ),
            "the raw unit must carry the array local before the delegation:\n$raw"
        )
        val rawErrors = compile(withCaller(raw))
        assertTrue(
            rawErrors.any { it.contains("call to super must be first statement") },
            "javac must reject the raw unit for the delegation's position; got $rawErrors"
        )

        // The hoisted unit is the one the dump reports: `cannot find symbol: variable var2`
        // (AssetBarcodeList.failure.txt has exactly this diagnostic on the real module).
        val hoisted = ConstructorNormalizer.normalize(raw)
        assertTrue(hoisted.contains("{super((Collection)CollectionsKt.arrayListOf(var2));"), hoisted)
        val hoistedErrors = compile(withCaller(hoisted))
        assertTrue(
            hoistedErrors.any { it.contains("cannot find symbol") && it.contains("var2") },
            "the hoisted unit must reproduce the dump's diagnostic; got $hoistedErrors"
        )

        val fixedRaw = DefaultArgumentConstructorNormalizer.normalize(raw)
        val fixedHoisted = DefaultArgumentConstructorNormalizer.normalize(hoisted)
        for (fixed in listOf(fixedRaw, fixedHoisted)) {
            assertTrue(
                fixed.contains("super((Collection)CollectionsKt.arrayListOf(new UUID[]{UUID.randomUUID()}));"),
                "the array expression must be inlined into the delegation:\n$fixed"
            )
            assertFalse(fixed.contains("var2"), "the synthetic array local must be gone:\n$fixed")
            assertEquals(
                emptyList<String>(),
                compile(withCaller(fixed)),
                "the normalized unit and a caller of its API must compile:\n$fixed"
            )
        }
        assertDelegationFirst(
            fixedRaw,
            "public RandomIdList(int count) {",
            "super((Collection)CollectionsKt.arrayListOf(new UUID[]{UUID.randomUUID()}));"
        )
        assertDelegationFirst(
            fixedHoisted,
            "public RandomIdList(int count) {",
            "super((Collection)CollectionsKt.arrayListOf(new UUID[]{UUID.randomUUID()}));"
        )
        assertSpliced(
            raw,
            fixedRaw,
            "arrayListOf(var2)" to "arrayListOf(new UUID[]{UUID.randomUUID()})",
            "UUID[] var2 = new UUID[]{UUID.randomUUID()};" to ""
        )
        assertSpliced(
            hoisted,
            fixedHoisted,
            "arrayListOf(var2)" to "arrayListOf(new UUID[]{UUID.randomUUID()})",
            "UUID[] var2 = new UUID[]{UUID.randomUUID()};" to ""
        )
    }

    // -- the Kotlin default-argument shape the transform must refuse -------------------------------

    @Test
    fun `the default-argument constructor is left byte-identical in either order`() {
        assumeReady()
        val units = decompile("DefaultArgumentIds.class", "HasObjectId.class")
        val raw = units.getValue("accept.defaultargs.DefaultArgumentIds")
        val nl = raw.nl

        // Kotlin 2.4.0 routes a default argument that is a static call through the synthetic
        // `(UUID, int, DefaultConstructorMarker)` constructor: the no-argument one delegates to that
        // one, and the default is applied *after* the delegation, to a declared parameter. There is
        // nothing to inline, and a transform that guessed here would change behaviour.
        assertTrue(raw.contains("DefaultConstructorMarker"), raw)
        assertTrue(raw.contains("this((UUID)null, 1, (DefaultConstructorMarker)null);"), raw)
        assertTrue(raw.contains("if ((var2 & 1) != 0) {$nl         UUID var10000 = UUID.randomUUID();"), raw)

        assertEquals(
            raw,
            DefaultArgumentConstructorNormalizer.normalize(raw),
            "the parameter-delegation shape must come back byte-identical:\n$raw"
        )
        val hoisted = ConstructorNormalizer.normalize(raw)
        assertEquals(
            hoisted,
            DefaultArgumentConstructorNormalizer.normalize(hoisted),
            "and again after the delegation hoist, which is the tree the pipeline feeds:\n$hoisted"
        )
        // The hoisted form of *this* shape is legal Java (the parameter was always in scope), which is
        // why the transform must not touch it: the defect here is the default's evaluation order, not
        // the declaration of a local.
        assertEquals(
            emptyList<String>(),
            compile(
                mapOf(
                    "accept/defaultargs/DefaultArgumentIds.java" to hoisted,
                    "accept/defaultargs/HasObjectId.java" to units.getValue("accept.defaultargs.HasObjectId"),
                    "accept/defaultargs/UseDefaultArgumentIds.java" to defaultArgumentIdsCaller
                )
            ),
            "the hoisted default-argument unit must compile:\n$hoisted"
        )
    }

    // -- the whole fixture package ------------------------------------------------------------------

    @Test
    fun `every unit of the fixture package parses, normalizes and compiles together`() {
        assumeReady()
        val units = decompile(
            "RandomDefaultId.class",
            "DefaultArgumentIds.class",
            "RandomIdList.class",
            "HasObjectId.class"
        )
        assertEquals(
            listOf(
                "accept.defaultargs.DefaultArgumentIds",
                "accept.defaultargs.HasObjectId",
                "accept.defaultargs.RandomDefaultId",
                "accept.defaultargs.RandomIdList"
            ),
            units.keys.sorted()
        )

        val hoisted = units.mapValues { (_, text) -> ConstructorNormalizer.normalize(text) }
        val fixed = hoisted.mapValues { (name, text) ->
            val repaired = DefaultArgumentConstructorNormalizer.normalize(text)
            assertEquals(emptyList<String>(), parseErrors("${name.substringAfterLast('.')}.java", repaired), repaired)
            repaired
        }
        // The undeclared synthetic locals are gone from every repaired unit (the units' other, real
        // temporaries — `var10000` in `hex()`, `var2` in `equals` — are untouched), and the refused
        // unit is byte-identical to what the chain's earlier steps produced.
        assertFalse(fixed.getValue("accept.defaultargs.RandomDefaultId").contains("var10001"))
        assertFalse(fixed.getValue("accept.defaultargs.RandomIdList").contains("var2"))
        assertEquals(
            hoisted.getValue("accept.defaultargs.DefaultArgumentIds"),
            fixed.getValue("accept.defaultargs.DefaultArgumentIds")
        )

        val sources = fixed.mapKeys { (name, _) -> "accept/defaultargs/${name.substringAfterLast('.')}.java" } +
            mapOf("accept/defaultargs/UseDefaultArgs.java" to allCaller)
        assertEquals(
            emptyList<String>(),
            compile(sources),
            "the normalized package and a caller must compile:\n$sources"
        )
    }

    /** Proves the repaired delegation is the body's first statement: Java's own requirement. */
    private fun assertDelegationFirst(fixed: String, header: String, delegation: String) {
        val body = fixed.substringAfter(header, missingDelimiterValue = "")
        assertTrue(body.isNotEmpty(), "no <$header> in:\n$fixed")
        assertEquals(
            delegation,
            body.trimStart().substringBefore("\n").trim(),
            "the delegation must be the first statement of the body:\n$fixed"
        )
    }
}
