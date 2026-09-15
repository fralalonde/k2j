package org.example.k2j.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Kotlin's declaration-site variance (`Map<K, out V>`) makes FernFlower emit a constructor (and
 * `copy(...)`) **parameter** with `? extends V` while emitting the **field** invariant, so
 * `this.properties = properties;` fails javac's capture rules — in text that *parses*, which is why
 * the parse gate cannot see it. [WildcardCaptureNormalizer] is the conservative text repair for that
 * one shape; these tests pin it on the shapes the real decompiler produced and on the shapes it must
 * refuse.
 *
 * Each behaviour is proved on the axis that makes the test fail when the behaviour is reverted:
 *  - the exact `VarianceProps` fixture is compiled *before* and *after* the transform, so a no-op
 *    `normalize` fails it;
 *  - the no-match fixture is asserted byte-identical, so a normalizer that strips without a wildcard
 *    fails it;
 *  - the refused shapes assert both the unchanged text and the javac rejection that follows, so
 *    dropping any one guard (same name, same class body, invariant field, same erasure) fails it.
 */
class WildcardCaptureNormalizerTest {

    /** A Kotlin raw string cannot carry a bare `$`, and `copy$default` / `$FF` are part of the shape. */
    private fun java(text: String): String = text.trimIndent().replace('§', '$')

    private fun parseErrors(name: String, text: String): List<String> = JavacValidator().validate(name, text)

    /**
     * The exact `accept.variance.VarianceProps` unit, as the vendored FernFlower emitted it (the
     * `d1`/`d2` metadata literals carry no shape and are elided; every other line is verbatim).
     */
    private val varianceProps = java(
        """
        package accept.variance;

        import java.util.Map;
        import kotlin.Metadata;
        import kotlin.collections.MapsKt;
        import kotlin.jvm.internal.DefaultConstructorMarker;
        import kotlin.jvm.internal.Intrinsics;
        import org.jetbrains.annotations.NotNull;
        import org.jetbrains.annotations.Nullable;

        @Metadata(
           mv = {2, 4, 0},
           k = 1,
           xi = 48
        )
        public final class VarianceProps implements Props {
           @NotNull
           private final Map<String, Number> properties;

           public VarianceProps(@NotNull Map<String, ? extends Number> properties) {super();
              Intrinsics.checkNotNullParameter(properties, "properties");

              this.properties = properties;
           }

           // §FF: synthetic method
           public VarianceProps(Map var1, int var2, DefaultConstructorMarker var3) {this(var1);
              if ((var2 & 1) != 0) {
                 var1 = MapsKt.emptyMap();
              }


           }

           @NotNull
           public Map<String, Number> getProperties() {
              return this.properties;
           }

           public final int total() {
              return this.getProperties().size();
           }

           @NotNull
           public final Map<String, Number> component1() {
              return this.properties;
           }

           @NotNull
           public final VarianceProps copy(@NotNull Map<String, ? extends Number> properties) {
              Intrinsics.checkNotNullParameter(properties, "properties");
              return new VarianceProps(properties);
           }

           // §FF: synthetic method
           public static VarianceProps copy§default(VarianceProps var0, Map var1, int var2, Object var3) {
              if ((var2 & 1) != 0) {
                 var1 = var0.properties;
              }

              return var0.copy(var1);
           }

           @NotNull
           public String toString() {
              return "VarianceProps(properties=" + this.properties + ")";
           }

           public int hashCode() {
              return this.properties.hashCode();
           }

           public boolean equals(@Nullable Object other) {
              if (this == other) {
                 return true;
              } else if (!(other instanceof VarianceProps)) {
                 return false;
              } else {
                 VarianceProps var2 = (VarianceProps)other;
                 return Intrinsics.areEqual(this.properties, var2.properties);
              }
           }

           public VarianceProps() {
              this((Map)null, 1, (DefaultConstructorMarker)null);
           }
        }
        """
    )

    /**
     * Stand-ins for everything the fixture imports, so the transform can be handed to the real
     * compiler without this test depending on a jar on the machine — the same approach
     * [EnumNormalizerTest] uses. Every signature is the one Kotlin emits a call to.
     */
    private val stubs: Map<String, String> = mapOf(
        "kotlin/Metadata.java" to
            "package kotlin;\npublic @interface Metadata { int[] mv() default {}; int k() default 0; int xi() default 0; String[] d1() default {}; String[] d2() default {}; }",
        "kotlin/collections/MapsKt.java" to
            "package kotlin.collections;\nimport java.util.Map;\npublic final class MapsKt { public static <K, V> Map<K, V> emptyMap() { return null; } }",
        "kotlin/jvm/internal/DefaultConstructorMarker.java" to
            "package kotlin.jvm.internal;\npublic final class DefaultConstructorMarker { private DefaultConstructorMarker() { } }",
        "kotlin/jvm/internal/Intrinsics.java" to
            "package kotlin.jvm.internal;\npublic final class Intrinsics { public static void checkNotNullParameter(Object value, String name) { } public static boolean areEqual(Object a, Object b) { return a == b; } }",
        "org/jetbrains/annotations/NotNull.java" to
            "package org.jetbrains.annotations;\npublic @interface NotNull { }",
        "org/jetbrains/annotations/Nullable.java" to
            "package org.jetbrains.annotations;\npublic @interface Nullable { }",
        "accept/variance/Props.java" to
            "package accept.variance;\nimport java.util.Map;\npublic interface Props { Map<String, Number> getProperties(); }"
    )

    /**
     * A caller of the repaired unit: exercising the getter, `copy`, `copy$default` and the
     * constructor is what proves the public API still works after the parameter was narrowed to the
     * field's type (a raw `copy$default` argument is an unchecked call, which javac accepts).
     */
    private val caller = java(
        """
        package accept.variance;

        import java.util.Map;

        public final class UseVariance {
           public Map<String, Number> use(VarianceProps props, Map<String, Number> values) {
              Map<String, Number> viaGetter = props.getProperties();
              VarianceProps viaCopy = props.copy(values);
              VarianceProps viaCopyDefault = VarianceProps.copy§default(props, null, 1, null);
              VarianceProps viaConstructor = new VarianceProps(values);
              return viaCopyDefault.getProperties().isEmpty()
                 ? viaCopy.getProperties()
                 : viaConstructor.getProperties().isEmpty() ? viaGetter : props.component1();
           }
        }
        """
    )

    private fun compile(vararg units: Pair<String, String>): List<String> =
        TestSupport.compileJavaSources(stubs + units.toMap())

    // -- the exact VarianceProps shape ----------------------------------------------------------

    @Test
    fun `the VarianceProps shape fails to compile before the transform and compiles after it`() {
        // Axis 1: the raw text *parses* — the parse gate cannot see this defect at all ...
        assertEquals(
            emptyList<String>(),
            parseErrors("VarianceProps.java", varianceProps),
            "the raw decompiler output must parse; otherwise this fixture is not the defect"
        )
        // ... and javac then rejects the assignment the wildcard breaks.
        val before = compile("accept/variance/VarianceProps.java" to varianceProps)
        assertTrue(
            before.any { it.contains("incompatible types") && it.contains("capture") },
            "the raw unit must fail javac's capture rules; got $before"
        )

        val normalized = WildcardCaptureNormalizer.normalize(varianceProps)

        // Axis 2: after the transform the same unit compiles, together with a caller of its API.
        assertEquals(
            emptyList<String>(),
            compile(
                "accept/variance/VarianceProps.java" to normalized,
                "accept/variance/UseVariance.java" to caller
            ),
            "the normalized unit must compile:\n$normalized"
        )
        // Axis 3: still parses (the repair cannot trade one gate for the other).
        assertEquals(emptyList<String>(), parseErrors("VarianceProps.java", normalized), normalized)
    }

    @Test
    fun `the field stays invariant and the parameter loses only the wildcard`() {
        val normalized = WildcardCaptureNormalizer.normalize(varianceProps)

        // The field is not touched — it was already the invariant type javac wants.
        assertTrue(
            normalized.contains("@NotNull\n   private final Map<String, Number> properties;") ||
                normalized.contains("@NotNull\r\n   private final Map<String, Number> properties;"),
            "the invariant field must be left exactly as emitted:\n$normalized"
        )
        // The constructor and copy parameters now declare the field's type.
        assertTrue(
            normalized.contains("public VarianceProps(@NotNull Map<String, Number> properties) {super();"),
            normalized
        )
        assertTrue(
            normalized.contains("public final VarianceProps copy(@NotNull Map<String, Number> properties) {"),
            normalized
        )
        assertFalse(normalized.contains("? extends"), "no wildcard may survive on these parameters:\n$normalized")

        // ...and *only* the wildcards changed: replacing the two occurrences in the raw text
        // reproduces the normalized text character for character — nothing was reformatted, renamed,
        // reordered or dropped.
        assertEquals(
            varianceProps.replace("? extends Number", "Number"),
            normalized,
            "the only difference may be the two wildcards"
        )
    }

    @Test
    fun `the getter, component1, copy, copy$default and both constructors survive intact`() {
        val normalized = WildcardCaptureNormalizer.normalize(varianceProps)
        for (member in listOf(
            "public Map<String, Number> getProperties() {",
            "public final int total() {",
            "public final Map<String, Number> component1() {",
            "public final VarianceProps copy(@NotNull Map<String, Number> properties) {",
            "public static VarianceProps copy\$default(VarianceProps var0, Map var1, int var2, Object var3) {",
            "public VarianceProps(Map var1, int var2, DefaultConstructorMarker var3) {this(var1);",
            "public VarianceProps() {",
            "return this.properties;",
            "return new VarianceProps(properties);",
            "return var0.copy(var1);"
        )) {
            assertTrue(normalized.contains(member), "'$member' must survive the transform:\n$normalized")
        }
        // `copy$default`'s raw argument is still an unchecked call to the narrowed parameter, not an
        // error — proved by the caller stub compiling in the previous test.
    }

    // -- nothing to do -------------------------------------------------------------------------

    @Test
    fun `an already-valid unit that carries no wildcard is byte-identical`() {
        // Field and parameter already declare the same type; only the whitespace differs, so a
        // normalizer that rewrote "just in case" would be visible here.
        val valid = java(
            """
            public final class Spacing {
               private final Map<String,Number> properties;

               public Spacing(Map<String, Number> properties) {
                  this.properties = properties;
               }

               public Map<String,Number> getProperties() {
                  return this.properties;
               }
            }
            """
        )
        assertEquals(valid, WildcardCaptureNormalizer.normalize(valid))

        // No wildcard anywhere: the same string instance-level guarantee, with the pipeline's own
        // already-valid corpus shapes.
        assertEquals(varianceProps.replace("? extends Number", "Number"), WildcardCaptureNormalizer.normalize(varianceProps.replace("? extends Number", "Number")))
    }

    @Test
    fun `garbage, an unbalanced body and a unit with no class declaration are returned unchanged`() {
        for (text in listOf(
            "not java at all\n",
            "public final class Truncated { private final Map<String, Number> properties; public Truncated(Map<String, ? extends Number> properties) { this.properties = properties;",
            "Map<String, ? extends Number> properties;\n"
        )) {
            assertEquals(text, WildcardCaptureNormalizer.normalize(text))
        }
    }

    // -- shapes that must be refused ------------------------------------------------------------

    @Test
    fun `a parameter whose name differs from the field is refused`() {
        // The defect shape with the names out of step: stripping would narrow a public API whose
        // parameter no longer has anything to do with the field, so the unit must be left alone and
        // fail the gate instead.
        val mismatched = java(
            """
            package accept.variance;

            import java.util.Map;

            public final class Renamed {
               private final Map<String, Number> properties;

               public Renamed(Map<String, ? extends Number> props) {
                  this.properties = props;
               }
            }
            """
        )
        assertEquals(mismatched, WildcardCaptureNormalizer.normalize(mismatched))
        assertEquals(emptyList<String>(), parseErrors("Renamed.java", mismatched), "the fixture must parse")
        val errors = TestSupport.compileJavaSources(mapOf("accept/variance/Renamed.java" to mismatched))
        assertTrue(
            errors.any { it.contains("incompatible types") },
            "the refused unit must still be rejected by javac (that is what the compile gate reports); got $errors"
        )
    }

    @Test
    fun `a field that is itself wildcarded is refused`() {
        val wildcardedField = java(
            """
            public final class WildcardField {
               private final Map<String, ? extends Number> properties;

               public WildcardField(Map<String, ? extends Number> properties) {
                  this.properties = properties;
               }
            }
            """
        )
        assertEquals(wildcardedField, WildcardCaptureNormalizer.normalize(wildcardedField))
    }

    @Test
    fun `a field with a different erasure, arity or argument is refused`() {
        val differentErasure = java(
            """
            public final class DifferentErasure {
               private final java.util.List<String> properties;

               public DifferentErasure(java.util.Map<String, ? extends String> properties) {
                  this.properties = properties;
               }
            }
            """
        )
        assertEquals(differentErasure, WildcardCaptureNormalizer.normalize(differentErasure))

        // Same erasure, but the stripped argument would not reproduce the field's argument.
        val differentArgument = java(
            """
            public final class DifferentArgument {
               private final Map<String, Object> properties;

               public DifferentArgument(Map<String, ? extends Number> properties) {
                  this.properties = properties;
               }
            }
            """
        )
        assertEquals(differentArgument, WildcardCaptureNormalizer.normalize(differentArgument))

        // A raw field is not "the same after stripping": the parameter is parameterized.
        val rawField = java(
            """
            public final class RawField {
               private final Map properties;

               public RawField(Map<String, ? extends Number> properties) {
                  this.properties = properties;
               }
            }
            """
        )
        assertEquals(rawField, WildcardCaptureNormalizer.normalize(rawField))
    }

    @Test
    fun `a bare wildcard is refused because it has no bound to substitute`() {
        val bare = java(
            """
            public final class BareWildcard {
               private final Map<String, Number> properties;

               public BareWildcard(Map<String, ?> properties) {
                  this.properties = properties;
               }
            }
            """
        )
        assertEquals(bare, WildcardCaptureNormalizer.normalize(bare))
    }

    @Test
    fun `a field of another class body does not license the outer constructor`() {
        val nested = java(
            """
            public final class Outer {
               private final Map<String, Number> properties;

               public Outer(Map<String, ? extends Number> properties) {
                  this.properties = properties;
               }

               public static final class Inner {
                  private final Map<String, Number> properties;

                  public Inner(Map<String, ? extends Number> properties) {
                     this.properties = properties;
                  }
               }
            }
            """
        )
        val normalized = WildcardCaptureNormalizer.normalize(nested)
        assertEquals(
            nested.replace("? extends Number", "Number"),
            normalized,
            "both bodies have a matching field, so both parameters may be stripped"
        )
        // ...and the outer field/constructor pair is the one the outer parameter was matched with:
        // the nested class's own parameter did not leak out of its body.
        val innerStart = normalized.indexOf("public static final class Inner")
        assertFalse(normalized.substring(0, innerStart).contains("? extends"))

        // The refusal case proper: a field exists, but in a DIFFERENT class body.
        val foreign = java(
            """
            public final class Owner {
               public static final class Holder {
                  private final Map<String, Number> properties;
               }

               public Owner(Map<String, ? extends Number> properties) {
                  this.properties = properties;
               }
            }
            """
        )
        assertEquals(foreign, WildcardCaptureNormalizer.normalize(foreign))
    }

    @Test
    fun `a constructor that never assigns the parameter to the field is refused`() {
        val notAssigned = java(
            """
            public final class NotAssigned {
               private final Map<String, Number> properties = null;

               public NotAssigned(Map<String, ? extends Number> properties) {
                  System.out.println(properties);
               }
            }
            """
        )
        assertEquals(notAssigned, WildcardCaptureNormalizer.normalize(notAssigned))
    }

    @Test
    fun `a copy method that does not pass the parameter to the constructor is refused`() {
        val notPassed = java(
            """
            public final class NotPassed {
               private final Map<String, Number> properties;

               public NotPassed(Map<String, ? extends Number> properties) {
                  this.properties = properties;
               }

               public NotPassed copy(Map<String, ? extends Number> properties) {
                  return this;
               }
            }
            """
        )
        val normalized = WildcardCaptureNormalizer.normalize(notPassed)
        // The constructor's parameter is stripped (it assigns the field) ...
        assertTrue(
            normalized.contains("public NotPassed(Map<String, Number> properties) {"),
            normalized
        )
        // ... the copy parameter is not: its body passes nothing to the constructor.
        assertTrue(
            normalized.contains("public NotPassed copy(Map<String, ? extends Number> properties) {"),
            normalized
        )
        assertEquals(notPassed.replace("public NotPassed(Map<String, ? extends Number> properties) {", "public NotPassed(Map<String, Number> properties) {"), normalized)
    }

    @Test
    fun `a copy method that passes the parameter to another class's constructor is refused`() {
        val first = java(
            """
            public final class First {
               private final Map<String, Number> properties;

               public First(Map<String, ? extends Number> properties) {
                  this.properties = properties;
               }
            }
            """
        )
        val second = java(
            """
            public final class Second {
               private final Map<String, Number> properties;

               public Second(Map<String, ? extends Number> properties) {
                  this.properties = properties;
               }

               public Second copy(Map<String, ? extends Number> properties) {
                  return new First(properties);
               }
            }
            """
        )
        val normalized = WildcardCaptureNormalizer.normalize("$first\n$second")

        // Both constructors are stripped: each assigns its own class's field.
        assertTrue(normalized.contains("public First(Map<String, Number> properties) {"), normalized)
        assertTrue(normalized.contains("public Second(Map<String, Number> properties) {"), normalized)
        // The copy is NOT: it passes its parameter to a constructor declared in a different class
        // body, which this transform never reaches across.
        assertTrue(
            normalized.contains("public Second copy(Map<String, ? extends Number> properties) {"),
            normalized
        )
        assertEquals(
            "$first\n$second".replace("Map<String, ? extends Number> properties) {\n      this", "Map<String, Number> properties) {\n      this"),
            normalized,
            "only the two constructors' parameters may differ"
        )
    }

    @Test
    fun `a copy parameter with no matching field is refused`() {
        val noField = java(
            """
            public final class NoField {
               public NoField() { }

               public NoField copy(Map<String, ? extends Number> properties) {
                  return new NoField();
               }
            }
            """
        )
        assertEquals(noField, WildcardCaptureNormalizer.normalize(noField))
    }

    @Test
    fun `a super-wildcard is only stripped when it reproduces the field exactly`() {
        val matching = java(
            """
            public final class SuperMatch {
               private final Map<String, Number> properties;

               public SuperMatch(Map<String, ? super Number> properties) {
                  this.properties = properties;
               }
            }
            """
        )
        assertEquals(
            matching.replace("? super Number", "Number"),
            WildcardCaptureNormalizer.normalize(matching)
        )

        val notMatching = java(
            """
            public final class SuperMismatch {
               private final Map<String, Object> properties;

               public SuperMismatch(Map<String, ? super Number> properties) {
                  this.properties = properties;
               }
            }
            """
        )
        assertEquals(notMatching, WildcardCaptureNormalizer.normalize(notMatching))
    }
}
