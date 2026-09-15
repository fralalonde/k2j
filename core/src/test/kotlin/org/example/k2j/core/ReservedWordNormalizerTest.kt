package org.example.k2j.core

import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * [ReservedWordNormalizer] — the Kotlin member named `default`, which no Java parser accepts.
 *
 * The unit under test is the shape of the real dumps: `target module`'s
 * `com/example/properties/EnumValue.class` decompiles to a `private final Enum<?>
 * default;` field, a `public EnumValue(@Nullable Enum<?> actual, @Nullable Enum<?> default)`
 * constructor, `this.default = default;`, `var0.default` in `copy$default`/`equals`, and a
 * `toString()` whose *message string* also contains `default=`. The parse gate rejects the unit with
 * exactly the diagnostics the real run recorded (`<identifier> expected` at 16:25 and 24:12), so the
 * unit never reaches the compile gate.
 *
 * Each test is an axis that fails when the behaviour is reverted:
 *  - the raw FernFlower-shaped text must **not** parse, and the normalized text must parse;
 *  - the rename must be complete (a field, a parameter, a `this.` access and a cross-instance access
 *    are all the same name) and must leave a `default:` switch label and a `default=` *message string*
 *    alone;
 *  - every refusal — a visible member, a use with no declaration in the unit, a type position — must
 *    return the *same instance*, so a unit this pass cannot prove is handed to the gate untouched.
 */
class ReservedWordNormalizerTest {

    @TempDir
    lateinit var tmp: Path

    /** The real `EnumValue.java` shape, with the Kotlin standard library's annotations dropped. */
    private val valueClass = """
        package accept.reservedword;

        public final class EnumValue {
           private final Enum<?> actual;
           private final Enum<?> default;

           public EnumValue(Enum<?> actual, Enum<?> default) {
              this.actual = actual;
              this.default = default;
           }

           public Enum<?> getDefault() {
              return this.default;
           }

           public final EnumValue copy(Enum<?> actual, Enum<?> default) {
              return new EnumValue(actual, default);
           }

           public static EnumValue copy${'$'}default(EnumValue var0, Enum var1, Enum var2, int var3, Object var4) {
              if ((var3 & 1) != 0) {
                 var1 = var0.actual;
              }

              if ((var3 & 2) != 0) {
                 var2 = var0.default;
              }

              return var0.copy(var1, var2);
           }

           public String toString() {
              return "EnumValue(actual=" + this.actual + ", default=" + this.default + ")";
           }
        }
    """.trimIndent() + "\n"

    private fun parseErrors(name: String, text: String): List<String> = JavacValidator().validate(name, text)

    // -- the repair -------------------------------------------------------------------------------

    @Test
    fun `the real shape - a field, a parameter, this-access and a cross-instance access are all renamed`() {
        // Axis 1: the raw text does not parse, with the diagnostics the real module recorded.
        val rawErrors = parseErrors("EnumValue.java", valueClass)
        assertFalse(rawErrors.isEmpty(), "the raw FernFlower-shaped text must not parse:\n$valueClass")
        assertTrue(
            rawErrors.any { it.contains("<identifier> expected") },
            "a reserved-word name is a missing identifier; got $rawErrors"
        )

        val normalized = ReservedWordNormalizer.normalize(valueClass)

        // Axis 2: it parses now.
        assertEquals(emptyList<String>(), parseErrors("EnumValue.java", normalized), normalized)

        // Axis 3: one name, used everywhere the field and the parameter were used.
        assertTrue(normalized.contains("private final Enum<?> default_;"), normalized)
        assertTrue(normalized.contains("public EnumValue(Enum<?> actual, Enum<?> default_) {"), normalized)
        assertTrue(normalized.contains("this.default_ = default_;"), normalized)
        assertTrue(normalized.contains("return this.default_;"), normalized)
        assertTrue(normalized.contains("var2 = var0.default_;"), normalized)
        assertTrue(normalized.contains("return var0.copy(var1, var2);"), normalized)
        // ...and no declaration or use of the word is left: the single `default` the text still
        // contains is the one inside the `toString` message string.
        assertEquals(
            1,
            Regex("""(?<![\w$])default(?![\w$])""").findAll(normalized).count(),
            "only the message string may keep the word:\n$normalized"
        )

        // Axis 4: the API did not move — the getter keeps its Java name.
        assertTrue(normalized.contains("public Enum<?> getDefault() {"), normalized)
    }

    @Test
    fun `the toString message string is text, not a name, and survives the rename`() {
        val normalized = ReservedWordNormalizer.normalize(valueClass)
        assertTrue(
            normalized.contains(
                "return \"EnumValue(actual=\" + this.actual + \", default=\" + this.default_ + \")\";"
            ),
            normalized
        )
    }

    @Test
    fun `a switch label is left alone while a member of the same name is renamed`() {
        val text = """
            package accept.reservedword;

            public final class Labelled {
               private final int default = 1;

               public int pick(int x) {
                  int result;
                  switch (x) {
                     case 1: result = this.default; break;
                     default: result = 0;
                  }
                  return result;
               }
            }
        """.trimIndent() + "\n"

        val normalized = ReservedWordNormalizer.normalize(text)

        assertEquals(emptyList<String>(), parseErrors("Labelled.java", normalized), normalized)
        assertTrue(normalized.contains("private final int default_ = 1;"), normalized)
        assertTrue(normalized.contains("result = this.default_;"), normalized)
        assertTrue(normalized.contains("default: result = 0;"), "the label must survive:\n$normalized")
    }

    @Test
    fun `the chosen name skips a name the unit already uses`() {
        val text = """
            package accept.reservedword;

            public final class Taken {
               private final int default_ = 0;
               private final int default = 1;

               public int get() {
                  return this.default;
               }
            }
        """.trimIndent() + "\n"

        val normalized = ReservedWordNormalizer.normalize(text)

        assertEquals(emptyList<String>(), parseErrors("Taken.java", normalized), normalized)
        assertTrue(normalized.contains("private final int default__ = 1;"), normalized)
        assertTrue(normalized.contains("private final int default_ = 0;"), normalized)
        assertTrue(normalized.contains("return this.default__;"), normalized)
    }

    // -- the refusals -----------------------------------------------------------------------------

    @Test
    fun `a public member named default is refused - the rename would change the API`() {
        val text = """
            package accept.reservedword;

            public final class Visible {
               public final int default = 1;
            }
        """.trimIndent() + "\n"

        assertSame(text, ReservedWordNormalizer.normalize(text))
    }

    @Test
    fun `a unit that only reads another class's default field is refused`() {
        val text = """
            package accept.reservedword;

            public final class Reader {
               public int read(Other other) {
                  return other.default;
               }
            }
        """.trimIndent() + "\n"

        assertSame(text, ReservedWordNormalizer.normalize(text))
    }

    @Test
    fun `a type position is refused`() {
        val text = """
            package accept.reservedword;

            public final class Holder extends default {
            }
        """.trimIndent() + "\n"

        assertSame(text, ReservedWordNormalizer.normalize(text))
    }

    @Test
    fun `a unit with no reserved word is returned as the same instance, and the repair is idempotent`() {
        val plain = """
            package accept.reservedword;

            public final class Plain {
               private final int count = 1;

               public int getCount() {
                  return this.count;
               }
            }
        """.trimIndent() + "\n"

        assertSame(plain, ReservedWordNormalizer.normalize(plain))

        val normalized = ReservedWordNormalizer.normalize(valueClass)
        assertSame(normalized, ReservedWordNormalizer.normalize(normalized))
    }

    // -- the wiring -------------------------------------------------------------------------------

    /**
     * The pipeline test: the repair only counts when [K2j] applies it. A unit whose only defect is the
     * `default` name is rejected by the parse gate unless the normalizer is wired into the text
     * pipeline, so this fails the moment that call is dropped.
     */
    @Test
    fun `the pipeline converts a unit whose member is named default`() {
        val classes = tmp.resolve("classes")
        TestSupport.writeFacadeClassFile(classes, "accept/reservedword", "EnumValue", "EnumValue.kt")
        val sources = tmp.resolve("src")
        val kt = sources.resolve("accept/reservedword/EnumValue.kt")
        Files.createDirectories(kt.parent)
        Files.writeString(
            kt,
            "package accept.reservedword\n\nclass EnumValue(val actual: Enum? = null, val default: Enum? = null)\n"
        )

        // Refuse the raw text: the fixture is only worth anything if it is the defect.
        assertFalse(
            parseErrors("EnumValue.java", valueClass).isEmpty(),
            "the fixture must not parse before the repair"
        )

        val outputRoot = tmp.resolve("out")
        val manifest = K2j(
            surveyor = AsmSurveyor(),
            decompiler = object : Decompiler {
                override fun decompile(classFiles: List<Path>, classpath: List<Path>): Map<String, String> =
                    mapOf("accept.reservedword.EnumValue" to valueClass)
            },
            validator = JavacValidator(),
            writer = FileSystemWriter(),
            log = object : RunLog {
                override fun info(message: String) = println(message)
                override fun warn(message: String) = println("WARN: $message")
                override fun error(message: String, cause: Throwable?) = println("ERROR: $message")
            }
        ).convert(
            ConversionRequest(
                classesRoot = classes,
                classpath = emptyList(),
                outputRoot = outputRoot,
                sourceRoots = listOf(sources)
            )
        )

        assertTrue(manifest.success, "the unit must convert once the name is repaired: ${manifest.failures}")
        val written = Files.readString(outputRoot.resolve("accept/reservedword/EnumValue.java"))
        assertTrue(written.contains("private final Enum<?> default_;"), written)
        assertEquals(emptyList<String>(), parseErrors("EnumValue.java", written), written)
    }
}
