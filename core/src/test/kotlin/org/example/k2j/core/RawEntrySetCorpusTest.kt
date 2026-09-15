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
 * The corpus fixture, on the **real** vendored FernFlower output: `accept/mapfilter/MapFilterProps.kt`
 * declares the shape that broke `com.example.app.properties.Properties` on `target module` — a
 * `data class` whose `equals` compares two `filter`ed maps.
 *
 * The fixture is the minimal Kotlin shape that reproduces it: the local the inlined `filter` body
 * binds its receiver to carries only the erased `Ljava/util/Map;` signature (Kotlin writes no
 * `LocalVariableTypeTable`), while the loop variable of the same body was written with
 * `Ljava/util/Map$Entry;`. FernFlower renders both faithfully and the two do not compose, so javac
 * rejects the enhanced `for`: `incompatible types: java.lang.Object cannot be converted to
 * java.util.Map.Entry`.
 *
 * Each assertion is an axis that fails when the behaviour is reverted:
 *  - the raw output must **parse**, with the parse gate accepting it and only the compile gate
 *    rejecting it — which is what made the real defect a `COMPILE` failure — so a fixture that
 *    stopped reproducing the defect fails the test instead of passing quietly;
 *  - after the pipeline's **other** passes, which this data class also needs (the delegating
 *    constructor and the `? extends V` capture of `Map<K, out V>`), the unit must still fail with
 *    exactly the two recorded diagnostics, so the defect is isolated to the shape this pass owns;
 *  - the normalized unit must parse **and compile**, so a normalizer that deletes the loop, or
 *    invents a type that does not fit the surrounding code, fails it;
 *  - the normalized text must be that pre-pass text with exactly the two iterable expressions
 *    re-spelled, so a transform that reformats, drops or reorders anything fails it.
 */
class RawEntrySetCorpusTest {

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

    /** The two libraries the generated text imports, so it can be handed to the real compiler. */
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
    }

    /** The real decompiler output of the fixture's class files, keyed by binary class name. */
    private fun decompile(vararg classFiles: String): Map<String, String> =
        FernFlowerDecompiler(decompilerJar, runtimeHome!!).decompile(
            classFiles.map { corpusClasses.resolve("accept/mapfilter/$it") },
            emptyList()
        )

    private fun parseErrors(name: String, text: String): List<String> = JavacValidator().validate(name, text)

    /** The fixture package's units keyed by the path javac sees, so the sibling types resolve. */
    private fun rawPackage(units: Map<String, String>): Map<String, String> =
        units.mapKeys { (className, _) -> "accept/mapfilter/${className.substringAfterLast('.')}.java" }

    /** Compiles the units with kotlin-stdlib and the JetBrains annotations, and returns the errors. */
    private fun compile(units: Map<String, String>): List<String> {
        val classpath = compileClasspath
        Assumptions.assumeTrue(
            classpath != null,
            "kotlin-stdlib-2.4.0.jar / annotations-13.0.jar not in the Gradle module cache"
        )
        return TestSupport.compileJavaSources(units, classpath!!)
    }

    /**
     * The pipeline's other passes, in the order `K2j` applies them — the fixture is a real data class
     * and needs all four, exactly as the real `Properties.java` did before the dump was written.
     */
    private fun otherPasses(text: String): String = PlaceholderParameterNormalizer.normalize(
        WildcardCaptureNormalizer.normalize(
            EnumNormalizer.normalize(
                DefaultArgumentConstructorNormalizer.normalize(ConstructorNormalizer.normalize(text))
            )
        )
    )

    // -- the unit the real dumps are shaped like ----------------------------------------------------

    @Test
    fun `MapFilterProps - the raw FernFlower loops fail the compile gate and the normalized unit compiles`() {
        assumeReady()
        val units = decompile(
            "MapFilterProps.class", "MapFilterContract.class", "MapFilterKey.class", "MapFilterValue.class"
        )
        val raw = units.getValue("accept.mapfilter.MapFilterProps")

        // Axis 1: the two loops the real `Properties.java` failed on, verbatim: a raw `Map` receiver
        // iterated by a `Map.Entry` variable.
        assertEquals(
            1,
            Regex("Map \\\$this\\\$filter\\\$iv = this\\.getProperties\\(\\);").findAll(raw).count(),
            "the receiver local must stay raw, as Kotlin's missing LVT makes it:\n$raw"
        )
        assertEquals(
            2,
            Regex("for\\(Map\\.Entry element\\\$iv\\\$iv : \\\$this\\\$filter\\\$iv\\.entrySet\\(\\)\\)")
                .findAll(raw).count(),
            "the fixture must carry the inlined `filter` body twice (one per compared map):\n$raw"
        )

        // The unit *parses*: the parse gate accepts it, which is why the real defect was reported as a
        // COMPILE failure and why no parse-level repair could have been the fix.
        assertEquals(
            emptyList<String>(),
            parseErrors("MapFilterProps.java", raw),
            "the raw decompiler output must parse; otherwise this fixture is not the defect"
        )

        // The four passes that ran before this one, on the whole package.
        val prePass = rawPackage(units).mapValues { (_, text) -> otherPasses(text) }

        // Axis 2: they leave exactly the two diagnostics this pass owns — the same two the recorded
        // `.failure.txt` for `Properties` carries, one per compared map.
        val prePassErrors = compile(prePass)
        assertEquals(
            2,
            prePassErrors.size,
            "the other passes must leave only the two entry-set loops: $prePassErrors"
        )
        for (line in listOf(48, 61)) {
            assertTrue(
                prePassErrors.any {
                    it.contains("MapFilterProps.java:$line:") &&
                        it.contains("java.lang.Object cannot be converted to java.util.Map.Entry")
                },
                "the recorded diagnostic shape must be reproduced at line $line; got $prePassErrors"
            )
        }

        val normalized = RawEntrySetNormalizer.normalize(prePass.getValue("accept/mapfilter/MapFilterProps.java"))

        // Axis 3: after this pass the package parses and compiles.
        assertEquals(emptyList<String>(), parseErrors("MapFilterProps.java", normalized), normalized)
        assertEquals(
            emptyList<String>(),
            compile(
                prePass.mapValues { (path, text) ->
                    if (path.endsWith("MapFilterProps.java")) normalized else text
                }
            ),
            "the normalized package must compile"
        )

        // Axis 4: only the two iterable expressions changed — the pre-pass text with those two casts
        // spliced in is the normalized text, character for character.
        assertEquals(
            prePass.getValue("accept/mapfilter/MapFilterProps.java").replace(
                "Map.Entry element\$iv\$iv : \$this\$filter\$iv.entrySet()",
                "Map.Entry element\$iv\$iv : (java.util.Set<Map.Entry>) \$this\$filter\$iv.entrySet()"
            ),
            normalized,
            "nothing but the two iterable expressions may differ"
        )
        // Idempotent, and the members the data class generates survive.
        assertEquals(normalized, RawEntrySetNormalizer.normalize(normalized))
        for (member in listOf(
            "public boolean equals(@Nullable Object other) {",
            "public int hashCode() {",
            "public final MapFilterProps copy(@NotNull Map<MapFilterKey, MapFilterValue> properties) {",
            "public static MapFilterProps copy\$default(MapFilterProps var0, Map var1, int var2, Object var3) {",
            "public MapFilterProps(Map var1, int var2, DefaultConstructorMarker var3) {this(var1);",
            "public MapFilterProps() {"
        )) {
            assertTrue(normalized.contains(member), "'$member' must survive the transform:\n$normalized")
        }
    }

    @Test
    fun `a real corpus unit that carries no entrySet loop is returned as the same instance`() {
        assumeReady()
        val raw = FernFlowerDecompiler(decompilerJar, runtimeHome!!).decompile(
            listOf(corpusClasses.resolve("accept/plain/Single.class")),
            emptyList()
        ).getValue("accept.plain.Single")

        assertFalse(raw.contains("entrySet()"), raw)
        assertSame(raw, RawEntrySetNormalizer.normalize(raw))
    }
}
