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
 * The corpus fixture, on the **real** vendored FernFlower output: `accept/placeholder/MutableProps.kt`
 * declares the property setters that broke 223 units of `target module`.
 *
 * The fixture is the minimal Kotlin shape that reproduces the leak — a `var` property — compiled the
 * way the real module's classes are compiled, with `javaParameters = true`
 * (`corpus/app/build.gradle.kts`): only then does Kotlin write the setter parameter's name into the
 * class file's `MethodParameters` attribute, which is where FernFlower reads a parameter name from.
 * Measured on the corpus, that flag changes the generated Java of **no** other fixture (byte-identical
 * trees apart from this package).
 *
 * Each assertion is an axis that fails when the behaviour is reverted:
 *  - the raw output must **not** parse — with the same diagnostics the real dumps carry — so a fixture
 *    that stopped reproducing the defect fails the test instead of passing quietly;
 *  - the normalized unit must parse **and compile**, so a normalizer that only deletes the token, or
 *    invents a name that does not fit the surrounding code, fails it;
 *  - the normalized text must be the raw text with exactly the placeholder's characters replaced, so a
 *    transform that reformats, drops or reorders anything fails it.
 */
class PlaceholderParameterCorpusTest {

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
            classFiles.map { corpusClasses.resolve("accept/placeholder/$it") },
            emptyList()
        )

    private fun parseErrors(name: String, text: String): List<String> = JavacValidator().validate(name, text)

    /** Compiles the units with kotlin-stdlib and the JetBrains annotations, and returns the errors. */
    private fun compile(units: Map<String, String>): List<String> {
        val classpath = compileClasspath
        Assumptions.assumeTrue(
            classpath != null,
            "kotlin-stdlib-2.4.0.jar / annotations-13.0.jar not in the Gradle module cache"
        )
        return TestSupport.compileJavaSources(units, classpath!!)
    }

    // -- the unit the real dumps are shaped like ----------------------------------------------------

    @Test
    fun `MutableProps - the raw setter output does not parse and the normalized unit parses and compiles`() {
        assumeReady()
        val units = decompile("MutableProps.class", "MutableLabel.class", "MutableHolder.class")
        val raw = units.getValue("accept.placeholder.MutableProps")

        // Axis 1: the four occurrences the real dumps carry, in one unit — and a parse failure with
        // the same diagnostics `SearchValue.java` failed with.
        assertTrue(raw.contains("public final void setValues(@NotNull List<String> <set-?>) {"), raw)
        assertTrue(raw.contains("Intrinsics.checkNotNullParameter(<set-?>, \"<set-?>\");"), raw)
        assertTrue(raw.contains("this.values = <set-?>;"), raw)
        assertTrue(raw.contains("public final void setCount(int <set-?>) {"), raw)
        assertTrue(raw.contains("public final void setName(@Nullable String <set-?>) {"), raw)
        assertFalse(raw.contains("var1"), "the fixture must leak the placeholder, not FernFlower's own name:\n$raw")

        val rawErrors = parseErrors("MutableProps.java", raw)
        assertTrue(rawErrors.isNotEmpty(), "the raw FernFlower output must not parse:\n$raw")
        assertTrue(
            rawErrors.any { it.contains("<identifier> expected") },
            "the placeholder must be a missing identifier; got $rawErrors"
        )

        val normalized = PlaceholderParameterNormalizer.normalize(raw)

        // Axis 2: after the transform every unit of the package parses and compiles together.
        assertEquals(emptyList<String>(), parseErrors("MutableProps.java", normalized), normalized)
        assertFalse(normalized.contains("<set-?>"), normalized)
        assertFalse(normalized.contains("\"<set-?>\""), "the NPE message must not keep the placeholder:\n$normalized")
        assertEquals(
            emptyList<String>(),
            compile(
                mapOf(
                    "accept/placeholder/MutableProps.java" to normalized,
                    "accept/placeholder/MutableLabel.java" to
                        PlaceholderParameterNormalizer.normalize(units.getValue("accept.placeholder.MutableLabel")),
                    "accept/placeholder/MutableHolder.java" to
                        PlaceholderParameterNormalizer.normalize(units.getValue("accept.placeholder.MutableHolder"))
                )
            ),
            "the normalized package must compile"
        )

        // Axis 3: the chosen name is the one the shape allows (`value` is free) and it is used
        // everywhere that parameter is used, including the message string.
        assertTrue(normalized.contains("public void setLabel(@NotNull String value) {"), normalized)
        assertTrue(normalized.contains("Intrinsics.checkNotNullParameter(value, \"value\");"), normalized)
        assertTrue(normalized.contains("this.values = value;"), normalized)
        assertTrue(normalized.contains("public final void setCount(int value) {"), normalized)
        assertTrue(
            normalized.contains("public final void setName(@Nullable String value) {"),
            normalized
        )
        // ...and nothing else moved: the raw text with only the placeholder's characters replaced is
        // the normalized text, byte for byte (the message string included).
        assertEquals(raw.replace("<set-?>", "value"), normalized)
    }

    @Test
    fun `MutableLabel - the bodyless declaration a real interface failed on is repaired`() {
        assumeReady()
        val raw = decompile("MutableLabel.class").getValue("accept.placeholder.MutableLabel")

        assertTrue(raw.contains("void setLabel(@NotNull String <set-?>);"), raw)
        val rawErrors = parseErrors("MutableLabel.java", raw)
        assertTrue(rawErrors.isNotEmpty(), "the raw interface must not parse:\n$raw")
        assertTrue(
            rawErrors.any { it.contains("> or ',' expected") },
            "the interface failed with `> or ',' expected` on target module too; got $rawErrors"
        )

        val normalized = PlaceholderParameterNormalizer.normalize(raw)

        assertTrue(normalized.contains("void setLabel(@NotNull String value);"), normalized)
        assertEquals(emptyList<String>(), parseErrors("MutableLabel.java", normalized), normalized)
        assertEquals(raw.replace("<set-?>", "value"), normalized)
    }

    @Test
    fun `MutableHolder - a primitive setter, a nullable setter and a nested body are all repaired`() {
        assumeReady()
        val raw = decompile("MutableHolder.class", "MutableHolder\$Nested.class")
            .getValue("accept.placeholder.MutableHolder")

        assertTrue(raw.contains("public final void setFlag(boolean <set-?>) {"), raw)
        assertTrue(raw.contains("public static final class Nested {"), raw)
        assertTrue(raw.contains("public final void setValues(@NotNull List<String> <set-?>) {"), raw)
        assertTrue(parseErrors("MutableHolder.java", raw).isNotEmpty(), raw)

        val normalized = PlaceholderParameterNormalizer.normalize(raw)

        assertEquals(emptyList<String>(), parseErrors("MutableHolder.java", normalized), normalized)
        assertFalse(normalized.contains("<set-?>"), normalized)
        assertTrue(normalized.contains("public final void setFlag(boolean value) {"), normalized)
        assertTrue(normalized.contains("Intrinsics.checkNotNullParameter(value, \"value\");"), normalized)
        assertEquals(raw.replace("<set-?>", "value"), normalized)
        // Two class bodies, four placeholder methods, one chosen name: idempotent and consistent.
        assertEquals(normalized, PlaceholderParameterNormalizer.normalize(normalized))
    }

    @Test
    fun `a real corpus unit that carries no placeholder is returned as the same instance`() {
        assumeReady()
        val raw = FernFlowerDecompiler(decompilerJar, runtimeHome!!).decompile(
            listOf(corpusClasses.resolve("accept/plain/Single.class")),
            emptyList()
        ).getValue("accept.plain.Single")

        assertFalse(raw.contains("<set-?>"), raw)
        assertSame(raw, PlaceholderParameterNormalizer.normalize(raw))
    }
}
