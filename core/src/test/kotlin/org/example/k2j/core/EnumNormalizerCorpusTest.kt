package org.example.k2j.core

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.name
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * The corpus fixtures, end to end: the **real** vendored FernFlower output for the corpus sources
 * under `corpus/app/src/main/kotlin/accept/` fed through the normalizers, and then the same classes
 * converted by the whole `K2j` pipeline.
 *
 * These are regression cases for the shapes that broke on `target module`, so each one asserts what the
 * fixture was added for: fields-before-constants lifted (`EnumWithFields`), an already-ordered body
 * left byte-identical (`PlainEnum`), the hoist happening inside the nested body only (`EnumHolder`),
 * an enum whose interface supplies the default method still parsing (`LabelledEnum`), and — the one
 * defect no parse can see — the wildcard parameter of a declaration-site-variant property
 * (`VarianceProps`), which must be stripped so the unit compiles.
 */
class EnumNormalizerCorpusTest {

    @TempDir
    lateinit var tmp: Path

    private val corpusClasses: Path
        get() = TestSupport.corpusClasses
            ?: error("corpus classes not built at D:/Work/k2j/corpus/app/build/classes/kotlin/main")

    /** The vendored FernFlower jar, as the plugin ships it (class-version 69 => needs Java 25). */
    private val decompilerJar: Path =
        Path.of("D:/Work/k2j/gradle-plugin/src/main/resources/k2j/java-decompiler.jar")

    /**
     * Probed Java 25 runtime, the same order the plugin uses on this machine: the rsdk toolchain the
     * project commands use, then the IDE JBR. The test skips (loudly) when neither is present.
     */
    private val runtimeHome: Path? = listOf(
        Path.of("C:/Users/FrancisLalonde/.rsdk/tools/java/25.0.2-jbr"),
        Path.of("C:/Users/FrancisLalonde/AppData/Local/Programs/IntelliJ IDEA Ultimate/jbr")
    ).firstOrNull { Files.isRegularFile(it.resolve("bin/java.exe")) }

    /**
     * The two libraries the decompiled text imports, so the corpus units can be handed to the real
     * compiler instead of only to the parse gate. Null when the Gradle module cache does not hold
     * them (the compile assertions skip, loudly, in that case).
     */
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

    private fun decompileIn(packagePath: String, vararg classFiles: String): Map<String, String> =
        FernFlowerDecompiler(decompilerJar, runtimeHome!!).decompile(
            classFiles.map { corpusClasses.resolve("$packagePath/$it") },
            emptyList()
        )

    private fun decompile(vararg classFiles: String): Map<String, String> =
        decompileIn("accept/enums", *classFiles)

    private fun parseErrors(name: String, text: String): List<String> = JavacValidator().validate(name, text)

    /** The line separator of a fixture, so the assertions hold whatever the checkout's line endings are. */
    private val String.nl: String get() = if (contains("\r\n")) "\r\n" else "\n"

    /** The constants of a body must precede any member; the pipeline report names the exact position. */
    private fun assertConstantsFirst(text: String, constant: String, member: String) {
        val constants = text.indexOf(constant)
        val members = text.indexOf(member)
        assertTrue(constants >= 0, "constant '$constant' missing from:\n$text")
        assertTrue(members >= 0, "member '$member' missing from:\n$text")
        assertTrue(constants < members, "'$constant' must precede '$member':\n$text")
    }

    // -- the raw decompiler output, fixture by fixture ----------------------------------------

    @Test
    fun `EnumWithFields - fields before the constant list are lifted, and the unit compiles`() {
        assumeReady()
        val raw = decompile("EnumWithFields.class").getValue("accept.enums.EnumWithFields")
        assertTrue(
            parseErrors("EnumWithFields.java", raw).isNotEmpty(),
            "the raw FernFlower output must reproduce the defect:\n$raw"
        )
        assertTrue(raw.contains("EnumEntriesKt.enumEntries(\$VALUES)"), raw)

        val normalized = EnumNormalizer.normalize(raw)

        assertEquals(emptyList<String>(), parseErrors("EnumWithFields.java", normalized), normalized)
        assertConstantsFirst(normalized, "ALPHA(\"alpha\", String.class),", "private final String alias;")
        assertTrue(normalized.contains("BETA(\"beta\", StringBuilder.class);"), normalized)
        assertTrue(normalized.contains("EnumEntriesKt.enumEntries(\$values())"), normalized)
        assertFalse(normalized.contains("\$VALUES"), normalized)
    }

    @Test
    fun `PlainEnum - the same dangling reference, with the constant list left byte-identical`() {
        assumeReady()
        val raw = decompile("PlainEnum.class").getValue("accept.enums.PlainEnum")

        // The defect here is only the reference: the constants are already first, so nothing may move.
        assertTrue(raw.contains("EnumEntriesKt.enumEntries(\$VALUES)"), raw)
        val normalized = EnumNormalizer.normalize(raw)
        assertEquals(raw.replace("\$VALUES", "\$values()"), normalized)
        val nl = normalized.nl
        assertTrue(normalized.contains("   ONE,${nl}   TWO,${nl}   THREE;"), normalized)
    }

    @Test
    fun `EnumHolder - the hoist happens inside the nested enum body only`() {
        assumeReady()
        val raw = decompile("EnumHolder.class", "EnumHolder\$Inner.class").getValue("accept.enums.EnumHolder")
        assertTrue(
            parseErrors("EnumHolder.java", raw).isNotEmpty(),
            "the raw FernFlower output must reproduce the defect:\n$raw"
        )

        val normalized = EnumNormalizer.normalize(raw)

        assertEquals(emptyList<String>(), parseErrors("EnumHolder.java", normalized), normalized)
        assertConstantsFirst(normalized, "A(1),", "private final int code;")
        assertTrue(normalized.indexOf("public final class EnumHolder") < normalized.indexOf("A(1),"))
        assertFalse(normalized.contains("\$VALUES"), normalized)
    }

    @Test
    fun `LabelledEnum - the field is lifted and the interface default still satisfies the enum`() {
        assumeReady()
        val raw = decompile("LabelledEnum.class").getValue("accept.enums.LabelledEnum")
        assertTrue(
            parseErrors("LabelledEnum.java", raw).isNotEmpty(),
            "the raw FernFlower output must reproduce the defect:\n$raw"
        )

        val normalized = EnumNormalizer.normalize(raw)

        assertEquals(emptyList<String>(), parseErrors("LabelledEnum.java", normalized), normalized)
        assertConstantsFirst(normalized, "X(1),", "private final int id;")
        assertTrue(normalized.contains("Y(2);"), normalized)
        assertTrue(normalized.contains("implements Labelled"), normalized)
    }

    // -- the declaration-site variance shape ----------------------------------------------------

    /**
     * The caller of the repaired unit: it compiles only if the getter, `copy`, `copy$default` and the
     * constructor all still exist and are callable with the narrowed parameter type.
     */
    private val varianceCaller = """
        package accept.variance;

        import java.util.Map;

        public final class UseVariance {
           public Map<String, Number> use(VarianceProps props, Map<String, Number> values) {
              Map<String, Number> viaGetter = props.getProperties();
              VarianceProps viaCopy = props.copy(values);
              VarianceProps viaCopyDefault = VarianceProps.copy${'$'}default(props, null, 1, null);
              VarianceProps viaConstructor = new VarianceProps(values);
              return viaCopyDefault.getProperties().isEmpty()
                 ? viaCopy.getProperties()
                 : viaConstructor.getProperties().isEmpty() ? viaGetter : props.component1();
           }
        }
    """.trimIndent()

    @Test
    fun `VarianceProps - the raw unit parses but does not compile, and the wildcard strip fixes it`() {
        assumeReady()
        val units = decompileIn("accept/variance", "Props.class", "VarianceProps.class")
        val raw = units.getValue("accept.variance.VarianceProps")
        val props = units.getValue("accept.variance.Props")
        val classpath = compileClasspath
        Assumptions.assumeTrue(classpath != null, "kotlin-stdlib-2.4.0.jar / annotations-13.0.jar not in the Gradle module cache")

        // Axis 1: the raw decompiler output *parses* — the defect is invisible to the gate the
        // pipeline runs by default — and its parameters really carry the wildcard while the field
        // does not. javac rejects it for that reason, among the two defects this fixture carries.
        assertEquals(emptyList<String>(), parseErrors("VarianceProps.java", raw), raw)
        assertTrue(raw.contains("public VarianceProps(@NotNull Map<String, ? extends Number> properties)"), raw)
        assertTrue(raw.contains("public final VarianceProps copy(@NotNull Map<String, ? extends Number> properties)"), raw)
        assertTrue(raw.contains("private final Map<String, Number> properties;"), "the field is already invariant:\n$raw")
        val rawErrors = TestSupport.compileJavaSources(
            mapOf("accept/variance/Props.java" to props, "accept/variance/VarianceProps.java" to raw),
            classpath!!
        )
        assertTrue(
            rawErrors.any { it.contains("incompatible types") && it.contains("cannot be converted") },
            "the raw unit must fail javac's capture rules; got $rawErrors"
        )

        // The other defect this fixture carries is the constructor-delegation ordering the pipeline
        // already repairs; once that is done the wildcard capture is the only error left, which is
        // exactly the defect this transform exists for.
        val hoisted = ConstructorNormalizer.normalize(raw)
        assertEquals(emptyList<String>(), parseErrors("VarianceProps.java", hoisted), hoisted)
        val hoistedErrors = TestSupport.compileJavaSources(
            mapOf("accept/variance/Props.java" to props, "accept/variance/VarianceProps.java" to hoisted),
            classpath
        )
        assertTrue(
            hoistedErrors.isNotEmpty() &&
                hoistedErrors.all { it.contains("incompatible types") && it.contains("cannot be converted") },
            "after the constructor repair only the wildcard capture may remain; got $hoistedErrors"
        )

        val normalized = WildcardCaptureNormalizer.normalize(hoisted)

        // Axis 2: after the transform the unit and a caller of its API compile — getter, `copy`,
        // `copy$default` and both constructors included.
        assertEquals(
            emptyList<String>(),
            TestSupport.compileJavaSources(
                mapOf(
                    "accept/variance/Props.java" to props,
                    "accept/variance/VarianceProps.java" to normalized,
                    "accept/variance/UseVariance.java" to varianceCaller
                ),
                classpath
            ),
            "the normalized unit must compile:\n$normalized"
        )
        // Axis 3: the transform is exactly the wildcard strip — nothing else may change, so the field
        // stays invariant and no other member is reformatted.
        assertEquals(
            hoisted.replace("? extends Number", "Number"),
            normalized,
            "the field must stay invariant and only the two parameters may lose their wildcard"
        )
        // Axis 4: the members the shape is about are all still declared.
        assertTrue(normalized.contains("private final Map<String, Number> properties;"), normalized)
        assertTrue(normalized.contains("public Map<String, Number> getProperties() {"), normalized)
        assertTrue(normalized.contains("public final Map<String, Number> component1() {"), normalized)
        assertTrue(normalized.contains("public final VarianceProps copy(@NotNull Map<String, Number> properties) {"), normalized)
        assertTrue(normalized.contains("public static VarianceProps copy\$default(VarianceProps var0, Map var1, int var2, Object var3) {"), normalized)
        assertTrue(normalized.contains("public VarianceProps(Map var1, int var2, DefaultConstructorMarker var3) {this(var1);"), normalized)
        assertTrue(normalized.contains("public VarianceProps() {"), normalized)
    }

    @Test
    fun `the accept variance package converts with no failures and the generated tree compiles`() {
        assumeReady()
        val classpath = compileClasspath
        Assumptions.assumeTrue(classpath != null, "kotlin-stdlib-2.4.0.jar / annotations-13.0.jar not in the Gradle module cache")
        val outputRoot = tmp.resolve("variance-out")

        val manifest = converter().convert(
            ConversionRequest(
                classesRoot = corpusClasses,
                classpath = emptyList(),
                outputRoot = outputRoot,
                packages = listOf("accept.variance"),
                sourceRoots = listOf(TestSupport.corpusSources)
            )
        )

        assertTrue(manifest.success, "no variance unit may fail a gate: ${manifest.failures}")
        val generated = manifest.converted.map { Path.of(it.outputPath).name }.sorted()
        assertEquals(listOf("Props.java", "VarianceProps.java"), generated)

        // The written unit is the one that compiles, and it is what is on disk (not just reported).
        val written = Files.readString(outputRoot.resolve("accept/variance/VarianceProps.java"))
        assertTrue(written.contains("private final Map<String, Number> properties;"), written)
        assertTrue(written.contains("public VarianceProps(@NotNull Map<String, Number> properties) {super();"), written)
        assertFalse(written.contains("? extends"), written)

        val errors = TestSupport.compileJavaSources(
            mapOf(
                "accept/variance/Props.java" to Files.readString(outputRoot.resolve("accept/variance/Props.java")),
                "accept/variance/VarianceProps.java" to written,
                "accept/variance/UseVariance.java" to varianceCaller
            ),
            classpath!!
        )
        assertEquals(emptyList<String>(), errors, "the generated variance tree must compile")
    }

    /** The pipeline under test, wired exactly as the CLI/plugin wire it. */
    private fun converter(): K2j = K2j(
        surveyor = AsmSurveyor(),
        decompiler = FernFlowerDecompiler(decompilerJar, runtimeHome!!),
        validator = JavacValidator(),
        writer = FileSystemWriter(),
        log = object : RunLog {
            override fun info(message: String) = println(message)
            override fun warn(message: String) = println("WARN: $message")
            override fun error(message: String, cause: Throwable?) = println("ERROR: $message")
        }
    )

    // -- the whole package through the pipeline ------------------------------------------------

    @Test
    fun `the accept enums package converts with no failures and every enum written as java`() {
        assumeReady()
        val outputRoot = tmp.resolve("out")
        val converter = K2j(
            surveyor = AsmSurveyor(),
            decompiler = FernFlowerDecompiler(decompilerJar, runtimeHome!!),
            validator = JavacValidator(),
            writer = FileSystemWriter(),
            log = object : RunLog {
                override fun info(message: String) = println(message)
                override fun warn(message: String) = println("WARN: $message")
                override fun error(message: String, cause: Throwable?) = println("ERROR: $message")
            }
        )
        val manifest = converter.convert(
            ConversionRequest(
                classesRoot = corpusClasses,
                classpath = emptyList(),
                outputRoot = outputRoot,
                packages = listOf("accept.enums"),
                sourceRoots = listOf(TestSupport.corpusSources)
            )
        )

        assertTrue(manifest.success, "no enum may fail the parse gate: ${manifest.failures}")
        val generated = manifest.converted.map { Path.of(it.outputPath).name }.sorted()
        assertEquals(
            listOf("EnumHolder.java", "EnumWithFields.java", "HasAlias.java", "Labelled.java", "LabelledEnum.java", "PlainEnum.java"),
            generated
        )

        // Written as .java (not just reported), and each enum body is in the only order javac accepts.
        for (name in generated) {
            assertTrue(Files.isRegularFile(outputRoot.resolve("accept/enums/$name")), "$name was not written")
        }
        val enumWithFields = Files.readString(outputRoot.resolve("accept/enums/EnumWithFields.java"))
        assertConstantsFirst(enumWithFields, "ALPHA(\"alpha\", String.class),", "private final String alias;")
        // Both synthetic accessors survive. (The pipeline's decompiler options erase the generic
        // signatures, so this asserts the members, not their type arguments.)
        assertTrue(enumWithFields.contains("getEntries()"), enumWithFields)
        assertTrue(enumWithFields.contains("private static final EnumWithFields[] \$values()"), enumWithFields)
        assertFalse(enumWithFields.contains("\$VALUES"), enumWithFields)

        val plainEnum = Files.readString(outputRoot.resolve("accept/enums/PlainEnum.java"))
        assertConstantsFirst(plainEnum, "ONE,", "private static final EnumEntries")
        assertFalse(plainEnum.contains("\$VALUES"), plainEnum)

        val enumHolder = Files.readString(outputRoot.resolve("accept/enums/EnumHolder.java"))
        assertConstantsFirst(enumHolder, "A(1),", "private final int code;")

        val labelledEnum = Files.readString(outputRoot.resolve("accept/enums/LabelledEnum.java"))
        assertConstantsFirst(labelledEnum, "X(1),", "private final int id;")
    }
}
