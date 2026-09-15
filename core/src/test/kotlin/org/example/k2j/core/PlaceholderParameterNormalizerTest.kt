package org.example.k2j.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Kotlin writes no name for a `var` property's setter parameter into the class file: the setter
 * carries the literal `<set-?>` in its `LocalVariableTable`/`MethodParameters` and in the string
 * constant it hands to `Intrinsics.checkNotNullParameter`, so FernFlower leaks it into Java that
 * cannot parse. [PlaceholderParameterNormalizer] is the conservative text repair for that one shape;
 * these tests pin it on the text the real decompiler emitted and on the shapes it must refuse.
 *
 * Each behaviour is proved on the axis that makes the test fail when the behaviour is reverted:
 *  - the `Multiple` fixture (the shape of `target module`'s `SearchValue$Multiple`) is parsed and
 *    compiled *before* and *after* the transform, so a no-op `normalize` fails it;
 *  - the no-placeholder fixtures are asserted byte-identical (and the fast path is asserted to be the
 *    *same instance*), so a normalizer that rewrites without a proven shape fails it;
 *  - the refused shapes assert the unchanged text, so dropping any one guard (the type-end check, the
 *    parameter-list check, one-placeholder-per-list, the reference-position whitelist, the single
 *    owner rule) fails it.
 */
class PlaceholderParameterNormalizerTest {

    private fun java(text: String): String = text.trimIndent().replace('§', '$')

    private fun parseErrors(name: String, text: String): List<String> = JavacValidator().validate(name, text)

    /**
     * The exact `target module` shape, as the vendored FernFlower emitted it for `MutableProps` and
     * `SearchValue$Multiple`: the placeholder is the declaration, the assignment and the message.
     */
    private val multiple = java(
        """
        package accept.placeholder;

        import java.util.List;
        import kotlin.jvm.internal.Intrinsics;
        import org.jetbrains.annotations.NotNull;

        public final class Multiple extends SearchValue {
           @NotNull
           private List<Single> values;

           @NotNull
           public final List<Single> getValues() {
              return this.values;
           }

           public final void setValues(@NotNull List<Single> <set-?>) {
              Intrinsics.checkNotNullParameter(<set-?>, "<set-?>");
              this.values = <set-?>;
           }
        }
        """
    )

    /**
     * Stand-ins for everything the fixtures import, so the repair can be handed to the real compiler
     * without this test depending on a jar on the machine — the same approach
     * [WildcardCaptureNormalizerTest] and [EnumNormalizerTest] use. Every signature is the one Kotlin
     * emits a call to.
     */
    private val stubs: Map<String, String> = mapOf(
        "kotlin/jvm/internal/Intrinsics.java" to
            "package kotlin.jvm.internal;\npublic final class Intrinsics { public static void checkNotNullParameter(Object value, String name) { } }",
        "org/jetbrains/annotations/NotNull.java" to
            "package org.jetbrains.annotations;\npublic @interface NotNull { }",
        "org/jetbrains/annotations/Nullable.java" to
            "package org.jetbrains.annotations;\npublic @interface Nullable { }",
        "accept/placeholder/Single.java" to
            "package accept.placeholder;\npublic final class Single { }",
        "accept/placeholder/SearchValue.java" to
            "package accept.placeholder;\npublic abstract class SearchValue { }"
    )

    private fun compile(vararg units: Pair<String, String>): List<String> =
        TestSupport.compileJavaSources(stubs + units.toMap())

    // -- the leaked shape -------------------------------------------------------------------------

    @Test
    fun `the leaked setter parameter does not parse before the transform and parses and compiles after it`() {
        // Axis 1: the raw text cannot parse — this is the defect the parse gate rejects the unit for.
        val rawErrors = parseErrors("Multiple.java", multiple)
        assertTrue(rawErrors.isNotEmpty(), "the raw decompiler output must not parse")
        assertTrue(
            rawErrors.any { it.contains("<identifier> expected") },
            "the placeholder must be rejected as a missing identifier; got $rawErrors"
        )

        val normalized = PlaceholderParameterNormalizer.normalize(multiple)

        // Axis 2: after the transform the same unit parses *and* compiles.
        assertEquals(emptyList<String>(), parseErrors("Multiple.java", normalized), normalized)
        assertEquals(
            emptyList<String>(),
            compile("accept/placeholder/Multiple.java" to normalized),
            "the normalized unit must compile:\n$normalized"
        )
    }

    @Test
    fun `the declaration, the assignment and the Intrinsics message all carry the chosen name`() {
        val normalized = PlaceholderParameterNormalizer.normalize(multiple)

        assertTrue(normalized.contains("public final void setValues(@NotNull List<Single> value) {"), normalized)
        assertTrue(normalized.contains("Intrinsics.checkNotNullParameter(value, \"value\");"), normalized)
        assertTrue(normalized.contains("this.values = value;"), normalized)
        // The message is the parameter's *name*: the placeholder may not survive it, or the unit would
        // still throw an NPE whose message is a decompiler artifact.
        assertFalse(normalized.contains("<set-?>"), normalized)
        assertFalse(normalized.contains("\"<set-?>\""), normalized)

        // ...and the rest of the unit was not reformatted: replacing the placeholder's own characters
        // in the raw text reproduces the normalized text byte for byte.
        assertEquals(multiple.replace("<set-?>", "value"), normalized)
    }

    @Test
    fun `several placeholder methods in one unit, and a nested class body, are all repaired`() {
        val holder = java(
            """
            package accept.placeholder;

            import java.util.List;
            import kotlin.jvm.internal.Intrinsics;
            import org.jetbrains.annotations.NotNull;
            import org.jetbrains.annotations.Nullable;

            public final class MutableHolder {
               private boolean flag;
               private String name;
               private List<String> values;

               public final void setFlag(boolean <set-?>) {
                  this.flag = <set-?>;
               }

               public final void setName(@Nullable String <set-?>) {
                  this.name = <set-?>;
               }

               public final void setValues(@NotNull List<String> <set-?>) {
                  Intrinsics.checkNotNullParameter(<set-?>, "<set-?>");
                  this.values = <set-?>;
               }

               public static final class Nested {
                  private List<String> values;

                  public final void setValues(@NotNull List<String> <set-?>) {
                     Intrinsics.checkNotNullParameter(<set-?>, "<set-?>");
                     this.values = <set-?>;
                  }
               }
            }
            """
        )
        val normalized = PlaceholderParameterNormalizer.normalize(holder)

        assertFalse(normalized.contains("<set-?>"), normalized)
        assertEquals(holder.replace("<set-?>", "value"), normalized)
        assertEquals(emptyList<String>(), parseErrors("MutableHolder.java", normalized), normalized)
        assertEquals(
            emptyList<String>(),
            compile("accept/placeholder/MutableHolder.java" to normalized),
            normalized
        )
    }

    @Test
    fun `a bodyless interface declaration is repaired and still compiles`() {
        val iface = java(
            """
            package accept.placeholder;

            import org.jetbrains.annotations.NotNull;

            public interface MutableLabel {
               @NotNull
               String getLabel();

               void setLabel(@NotNull String <set-?>);
            }
            """
        )
        // The interface shape is the one `IPaginatedSearchCriterias.java` failed with: the placeholder
        // is in the declaration only, and there is no body to attribute anything to.
        assertTrue(parseErrors("MutableLabel.java", iface).isNotEmpty(), iface)

        val normalized = PlaceholderParameterNormalizer.normalize(iface)

        assertTrue(normalized.contains("void setLabel(@NotNull String value);"), normalized)
        assertEquals(iface.replace("<set-?>", "value"), normalized)
        assertEquals(emptyList<String>(), parseErrors("MutableLabel.java", normalized), normalized)
        assertEquals(
            emptyList<String>(),
            compile("accept/placeholder/MutableLabel.java" to normalized),
            normalized
        )
    }

    @Test
    fun `a name the unit already uses is not reused, and it is used everywhere`() {
        val taken = java(
            """
            package accept.placeholder;

            public final class Taken {
               private final String value = null;
               private final String value1 = null;
               private String label;

               public final void setLabel(String <set-?>) {
                  this.label = <set-?>;
               }

               public final void setOther(String <set-?>) {
                  this.label = <set-?>;
               }
            }
            """
        )
        val normalized = PlaceholderParameterNormalizer.normalize(taken)

        assertTrue(normalized.contains("public final void setLabel(String value2) {"), normalized)
        assertTrue(normalized.contains("this.label = value2;"), normalized)
        assertTrue(normalized.contains("public final void setOther(String value2) {"), normalized)
        assertFalse(normalized.contains("value2 = null"), normalized)
        assertEquals(taken.replace("<set-?>", "value2"), normalized)
    }

    @Test
    fun `the string literal alone, with no placeholder parameter, is left alone`() {
        // The same unit compiled without `-java-parameters`: FernFlower renames the parameter to
        // `var1` itself and keeps the message. Nothing here is a proven parameter usage, so the text
        // must not be touched (it parses and compiles as it stands).
        val renamed = java(
            """
            package accept.placeholder;

            import kotlin.jvm.internal.Intrinsics;

            public final class Renamed {
               private String label;

               public final void setLabel(String var1) {
                  Intrinsics.checkNotNullParameter(var1, "<set-?>");
                  this.label = var1;
               }
            }
            """
        )
        assertSame(renamed, PlaceholderParameterNormalizer.normalize(renamed))
        assertEquals(emptyList<String>(), parseErrors("Renamed.java", renamed), "the fixture must parse")
    }

    // -- nothing to do ----------------------------------------------------------------------------

    @Test
    fun `a unit with no placeholder is returned as the same instance`() {
        val valid = java(
            """
            package accept.placeholder;

            import kotlin.jvm.internal.Intrinsics;
            import org.jetbrains.annotations.NotNull;

            public final class Plain {
               @NotNull
               private String values;

               public final void setValues(@NotNull String values) {
                  Intrinsics.checkNotNullParameter(values, "values");
                  this.values = values;
               }
            }
            """
        )
        assertSame(valid, PlaceholderParameterNormalizer.normalize(valid))
        assertEquals(emptyList<String>(), parseErrors("Plain.java", valid))
    }

    // -- shapes that must be refused ---------------------------------------------------------------

    @Test
    fun `a placeholder that is a call argument of a method with no placeholder parameter is refused whole`() {
        val callOnly = java(
            """
            package accept.placeholder;

            public final class CallOnly {
               public final void use(Object other) {
                  System.out.println(<set-?>);
               }
            }
            """
        )
        // The occurrence is in an expression position, but no declaration accounts for it: the unit is
        // returned unchanged rather than renamed on a guess.
        assertEquals(callOnly, PlaceholderParameterNormalizer.normalize(callOnly))
    }

    @Test
    fun `a placeholder in a type position refuses the whole unit, provable occurrences included`() {
        val typePosition = java(
            """
            package accept.placeholder;

            public final class TypePosition {
               private Object values;

               public final void setValues(Object <set-?>) {
                  this.values = <set-?>;
                  Object <set-?> = null;
               }
            }
            """
        )
        // The first two occurrences are provable; the third declares a *local* of that name. One
        // unproven occurrence is enough: the unit stays exactly as it was and the gate reports it.
        assertEquals(typePosition, PlaceholderParameterNormalizer.normalize(typePosition))
    }

    @Test
    fun `two placeholders in one parameter list are refused`() {
        val twoInOneList = java(
            """
            package accept.placeholder;

            public final class TwoInOneList {
               private Object a;

               public final void setBoth(Object <set-?>, Object <set-?>) {
                  this.a = <set-?>;
               }
            }
            """
        )
        assertEquals(twoInOneList, PlaceholderParameterNormalizer.normalize(twoInOneList))
    }

    @Test
    fun `a reference inside two placeholder methods is refused as ambiguous`() {
        val ambiguous = java(
            """
            package accept.placeholder;

            public final class Ambiguous {
               private Object outer;
               private Object inner;

               public final void setOuter(Object <set-?>) {
                  this.outer = <set-?>;
                  class Local {
                     public final void setInner(Object <set-?>) {
                        this.inner = <set-?>;
                     }
                  }
               }
            }
            """
        )
        // `this.inner = <set-?>` sits inside the bodies of *both* placeholder declarations, so neither
        // name may be imposed on it.
        assertEquals(ambiguous, PlaceholderParameterNormalizer.normalize(ambiguous))
    }

    @Test
    fun `a message literal outside every placeholder method refuses the whole unit`() {
        val strayMessage = java(
            """
            package accept.placeholder;

            import kotlin.jvm.internal.Intrinsics;

            public final class StrayMessage {
               private Object values;

               public final void setValues(Object <set-?>) {
                  this.values = <set-?>;
               }

               public final void check(Object other) {
                  Intrinsics.checkNotNullParameter(other, "<set-?>");
               }
            }
            """
        )
        // The message holding the placeholder is *not* an argument of a call inside the one
        // declaration that has a placeholder parameter: renaming it would be a guess about which
        // parameter that message belongs to, so the unit stays as it was.
        assertEquals(strayMessage, PlaceholderParameterNormalizer.normalize(strayMessage))
    }

    @Test
    fun `a placeholder followed by another closing angle bracket is refused`() {
        val stray = java(
            """
            package accept.placeholder;

            public final class Stray {
               private Object values;

               public final void setValues(Object <set-?>>) {
                  this.values = <set-?>;
               }
            }
            """
        )
        // Replacing the five matched tokens would leave the extra `>` behind and invent different
        // invalid Java, so the whole unit is refused.
        assertEquals(stray, PlaceholderParameterNormalizer.normalize(stray))
    }

    @Test
    fun `garbage, a truncated body and a unit with no declaration are returned unchanged`() {
        for (text in listOf(
            "not java at all\n",
            "public final class Truncated { private Object values; public final void setValues(Object <set-?>) { this.values = <set-?>;",
            "Object <set-?>;\n",
            "public final class Initializer { private Object values = <set-?>; }\n"
        )) {
            assertEquals(text, PlaceholderParameterNormalizer.normalize(text), text)
        }
    }
}
