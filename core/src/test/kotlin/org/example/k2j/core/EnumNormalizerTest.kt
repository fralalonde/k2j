package org.example.k2j.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * FernFlower emits every Kotlin enum with its instance fields **before** the constant list, which
 * javac rejects ("enum constant expected here"), and references the synthetic `$VALUES` field it
 * never emits. [EnumNormalizer] is the conservative text repair for both; these tests pin it to the
 * shapes the real decompiler produced (the fixtures below are that output, verbatim).
 *
 * Each behaviour is proved on the axis that makes the test fail when the behaviour is reverted: the
 * fixture itself must be rejected by the parse gate / the compiler *before* the transform and
 * accepted after it. A no-op `normalize` therefore fails every test in this class.
 *
 * A Kotlin raw string cannot contain a bare `$`, and `$VALUES` / `$values()` / `$ENTRIES` are exactly
 * what these fixtures are about, so [java] maps `§` to the dollar sign.
 */
class EnumNormalizerTest {

    private fun java(text: String): String = text.trimIndent().replace('§', '$')

    private fun parseErrors(vararg units: Pair<String, String>): List<String> =
        units.flatMap { (name, text) -> JavacValidator().validate(name, text) }

    /** The line separator of a fixture, so the assertions hold whatever the checkout's line endings are. */
    private val String.nl: String get() = if (contains("\r\n")) "\r\n" else "\n"

    /**
     * `target module`'s `CountActivityType`, as the vendored FernFlower emitted it (the `d1`/`d2`
     * metadata literals carry no shape and are elided; every other line is verbatim).
     */
    private val countActivityType = java(
        """
        package com.example.app.activity.count;

        import com.example.app.activity.ActivityType;
        import com.example.app.properties.IProperties;
        import com.example.app.workflow.step.WorkflowActivityType;
        import kotlin.Metadata;
        import kotlin.enums.EnumEntries;
        import kotlin.enums.EnumEntriesKt;
        import org.jetbrains.annotations.NotNull;

        @Metadata(
           mv = {2, 3, 0},
           k = 1,
           xi = 48
        )
        public enum CountActivityType implements WorkflowActivityType {
           @NotNull
           private final String alias;
           @NotNull
           private final Class<? extends IProperties> type;
           INVENTORY("inventory", CountInventoryProperties.class);

           // §FF: synthetic field
           private static final EnumEntries §ENTRIES = EnumEntriesKt.enumEntries(§VALUES);

           private CountActivityType(String alias, Class<? extends IProperties> type) {
              this.alias = alias;
              this.type = type;
           }

           @NotNull
           public String getAlias() {
              return this.alias;
           }

           @NotNull
           public Class<? extends IProperties> getType() {
              return this.type;
           }

           @NotNull
           public ActivityType getParent() {
              return ActivityType.Count;
           }

           @NotNull
           public static EnumEntries<CountActivityType> getEntries() {
              return §ENTRIES;
           }

           // §FF: synthetic method
           private static final CountActivityType[] §values() {
              CountActivityType[] var0 = new CountActivityType[]{INVENTORY};
              return var0;
           }
        }
        """
    )

    /**
     * `corpus/app`'s `accept.enums.EnumWithFields`, as the vendored FernFlower emitted it: the
     * two-constant, multi-line list the corpus fixture was added for.
     */
    private val enumWithFields = java(
        """
        package accept.enums;

        import kotlin.Metadata;
        import kotlin.enums.EnumEntries;
        import kotlin.enums.EnumEntriesKt;
        import org.jetbrains.annotations.NotNull;

        @Metadata(
           mv = {2, 4, 0},
           k = 1,
           xi = 48
        )
        public enum EnumWithFields implements HasAlias {
           @NotNull
           private final String alias;
           @NotNull
           private final Class<? extends CharSequence> type;
           ALPHA("alpha", String.class),
           BETA("beta", StringBuilder.class);

           // §FF: synthetic field
           private static final EnumEntries §ENTRIES = EnumEntriesKt.enumEntries(§VALUES);

           private EnumWithFields(String alias, Class<? extends CharSequence> type) {
              this.alias = alias;
              this.type = type;
           }

           @NotNull
           public String getAlias() {
              return this.alias;
           }

           @NotNull
           public final Class<? extends CharSequence> getType() {
              return this.type;
           }

           @NotNull
           public String getParent() {
              return "root";
           }

           @NotNull
           public static EnumEntries<EnumWithFields> getEntries() {
              return §ENTRIES;
           }

           // §FF: synthetic method
           private static final EnumWithFields[] §values() {
              EnumWithFields[] var0 = new EnumWithFields[]{ALPHA, BETA};
              return var0;
           }
        }
        """
    )

    /** `accept.enums.PlainEnum`: already valid member order, same dangling reference. */
    private val plainEnum = java(
        """
        package accept.enums;

        import kotlin.Metadata;
        import kotlin.enums.EnumEntries;
        import kotlin.enums.EnumEntriesKt;
        import org.jetbrains.annotations.NotNull;

        @Metadata(
           mv = {2, 4, 0},
           k = 1,
           xi = 48
        )
        public enum PlainEnum {
           ONE,
           TWO,
           THREE;

           // §FF: synthetic field
           private static final EnumEntries §ENTRIES = EnumEntriesKt.enumEntries(§VALUES);

           @NotNull
           public static EnumEntries<PlainEnum> getEntries() {
              return §ENTRIES;
           }

           // §FF: synthetic method
           private static final PlainEnum[] §values() {
              PlainEnum[] var0 = new PlainEnum[]{ONE, TWO, THREE};
              return var0;
           }
        }
        """
    )

    /**
     * Stand-ins for the dependencies on `target module`'s classpath, so a fixture can be handed to the
     * real compiler instead of only to the parse gate. `EnumEntriesKt.enumEntries(E[])` is the shape
     * kotlin-stdlib declares.
     */
    private val stubs: Map<String, String> = mapOf(
        "kotlin/Metadata.java" to
            "package kotlin;\npublic @interface Metadata { int[] mv() default {}; int k() default 0; int xi() default 0; String[] d1() default {}; String[] d2() default {}; }",
        "kotlin/enums/EnumEntries.java" to
            "package kotlin.enums;\npublic interface EnumEntries<E> { }",
        "kotlin/enums/EnumEntriesKt.java" to
            "package kotlin.enums;\npublic final class EnumEntriesKt { public static <E extends Enum<E>> EnumEntries<E> enumEntries(E[] entries) { return null; } }",
        "org/jetbrains/annotations/NotNull.java" to
            "package org.jetbrains.annotations;\npublic @interface NotNull { }",
        "com/example/properties/IProperties.java" to
            "package com.example.app.properties;\npublic interface IProperties { }",
        "com/example/workflow/step/WorkflowActivityType.java" to
            "package com.example.app.workflow.step;\npublic interface WorkflowActivityType { }",
        "com/example/activity/ActivityType.java" to
            "package com.example.app.activity;\npublic final class ActivityType { public static final ActivityType Count = new ActivityType(); }",
        "com/example/activity/count/CountInventoryProperties.java" to
            "package com.example.app.activity.count;\nimport com.example.app.properties.IProperties;\npublic final class CountInventoryProperties implements IProperties { }",
        "accept/enums/HasAlias.java" to
            "package accept.enums;\npublic interface HasAlias { String getAlias(); String getParent(); }"
    )

    // -- the exact target module shape ----------------------------------------------------------

    @Test
    fun `the exact target module enum shape is repaired, parses and keeps every member`() {
        // Axis 1: the raw decompiler output is illegal Java — the defect is really reproduced.
        val before = parseErrors("CountActivityType.java" to countActivityType)
        assertTrue(
            before.any { it.contains("enum constant expected here") || it.contains("<identifier> expected") },
            "the raw text must be rejected where the constant list belongs; got $before"
        )

        val normalized = EnumNormalizer.normalize(countActivityType)

        // Axis 2: the repaired text passes the same gate the pipeline uses.
        assertEquals(
            emptyList<String>(),
            parseErrors("CountActivityType.java" to normalized),
            "the normalized unit must parse:\n$normalized"
        )

        // The constant list is first, immediately after the enum's `{`.
        val brace = normalized.indexOf("{", normalized.indexOf("public enum CountActivityType"))
        val constants = normalized.indexOf("INVENTORY(\"inventory\", CountInventoryProperties.class);")
        val firstField = normalized.indexOf("private final String alias;")
        assertTrue(
            brace < constants && constants < firstField,
            "the constant list must be the first thing in the body:\n$normalized"
        )

        // The dangling reference is repaired to the accessor that exists; nothing is invented or dropped.
        assertTrue(
            normalized.contains("private static final EnumEntries \$ENTRIES = EnumEntriesKt.enumEntries(\$values());"),
            "the dangling \$VALUES reference must become \$values():\n$normalized"
        )
        assertFalse(
            Regex("""(?<![\w$.])\${'$'}VALUES""").containsMatchIn(normalized),
            "no \$VALUES reference may survive:\n$normalized"
        )
        assertTrue(normalized.contains("public static EnumEntries<CountActivityType> getEntries()"))
        assertTrue(normalized.contains("private static final CountActivityType[] \$values()"))

        // Everything else keeps its relative order: fields, then constructor, then methods.
        assertTrue(firstField < normalized.indexOf("private final Class<? extends IProperties> type;"))
        assertTrue(
            normalized.indexOf("private final Class<? extends IProperties> type;") <
                normalized.indexOf("private CountActivityType(String alias")
        )
        assertTrue(
            normalized.indexOf("private CountActivityType(String alias") <
                normalized.indexOf("public String getAlias()")
        )
        assertTrue(
            normalized.indexOf("public String getAlias()") <
                normalized.indexOf("public static EnumEntries<CountActivityType> getEntries()")
        )
        assertTrue(
            normalized.indexOf("public static EnumEntries<CountActivityType> getEntries()") <
                normalized.indexOf("\$values() {")
        )
        // ...and the annotation that belonged to the field still precedes the field.
        assertTrue(normalized.contains("   @NotNull${normalized.nl}   private final String alias;"))

        // Axis 3: not just parseable — the unit compiles, with the members it declares.
        assertEquals(
            emptyList<String>(),
            TestSupport.compileJavaSources(stubs + ("CountActivityType.java" to normalized)),
            "the normalized unit must compile:\n$normalized"
        )
    }

    // -- the dangling dollar-VALUES reference --------------------------------------------------

    @Test
    fun `the dangling reference is rewritten to the emitted accessor`() {
        val normalized = EnumNormalizer.normalize(countActivityType)
        assertFalse(normalized.contains("\$VALUES"))
        assertTrue(normalized.contains("EnumEntriesKt.enumEntries(\$values())"))

        // The rewrite is load-bearing: put the dangling reference back and the compiler rejects it,
        // so a normalizer that skipped this step cannot pass the assertion above.
        val reverted = normalized.replace(
            "EnumEntriesKt.enumEntries(\$values())",
            "EnumEntriesKt.enumEntries(\$VALUES)"
        )
        val errors = TestSupport.compileJavaSources(stubs + ("CountActivityType.java" to reverted))
        assertTrue(
            errors.any { it.contains("cannot find symbol") && it.contains("\$VALUES") },
            "the dangling reference must not compile; got $errors"
        )
    }

    @Test
    fun `a unit that declares its own field is left alone`() {
        val text = java(
            """
            public enum Legacy {
               A,
               B;

               // §FF: synthetic field
               private static final Legacy[] §VALUES = new Legacy[]{A, B};

               public static Legacy[] values() {
                  return §VALUES;
               }
            }
            """
        )
        assertEquals(text, EnumNormalizer.normalize(text))
    }

    // -- already-valid enums ------------------------------------------------------------------

    @Test
    fun `an already-valid enum is returned unchanged`() {
        val valid = java(
            """
            public enum Colour {
               RED,
               GREEN,
               BLUE;

               private final int code = 0;

               public int code() {
                  return this.code;
               }
            }
            """
        )
        assertEquals(valid, EnumNormalizer.normalize(valid))
    }

    @Test
    fun `an enum whose constants are already first is byte-identical apart from the dangling reference`() {
        // `accept.enums.PlainEnum` as emitted: the constant list must not move by one byte. The
        // reference must still be repaired, because `$VALUES` *parses* (so the gate cannot see it)
        // and then fails to compile — leaving it would be a silent pass that does not build.
        val expected = plainEnum.replace("\$VALUES", "\$values()")
        assertEquals(expected, EnumNormalizer.normalize(plainEnum))
        assertTrue(
            EnumNormalizer.normalize(plainEnum).contains("   ONE,${plainEnum.nl}   TWO,${plainEnum.nl}   THREE;"),
            "the constant list must not be reflowed:\n${EnumNormalizer.normalize(plainEnum)}"
        )
    }

    // -- multi-constant, multi-line lists -----------------------------------------------------

    @Test
    fun `a multi-constant multi-line list moves intact`() {
        val before = parseErrors("EnumWithFields.java" to enumWithFields)
        assertTrue(before.isNotEmpty(), "the raw text must not parse; got $before")

        val normalized = EnumNormalizer.normalize(enumWithFields)
        assertEquals(emptyList<String>(), parseErrors("EnumWithFields.java" to normalized), normalized)

        val nl = normalized.nl
        // Moved as one block, verbatim: both constants, their arguments, the commas and the `;`.
        val block = "   ALPHA(\"alpha\", String.class),${nl}   BETA(\"beta\", StringBuilder.class);"
        assertTrue(
            normalized.contains("{$nl$block$nl   @NotNull"),
            "the constant list must be lifted whole, immediately after the body brace:\n$normalized"
        )
        // ...and it is no longer where the fields left it.
        assertFalse(normalized.contains("type;${nl}$block"), "the list must not stay after the fields")
        assertTrue(
            normalized.indexOf("ALPHA(\"alpha\", String.class),") <
                normalized.indexOf("private final String alias;")
        )
        assertTrue(
            normalized.indexOf("BETA(\"beta\", StringBuilder.class);") <
                normalized.indexOf("private EnumWithFields(")
        )

        // The members it passed keep their own order.
        assertTrue(
            normalized.indexOf("private final String alias;") <
                normalized.indexOf("private final Class<? extends CharSequence> type;")
        )
        assertTrue(
            normalized.indexOf("private final Class<? extends CharSequence> type;") <
                normalized.indexOf("private EnumWithFields(String alias")
        )
        assertTrue(normalized.contains("EnumEntriesKt.enumEntries(\$values())"))
        assertEquals(
            emptyList<String>(),
            TestSupport.compileJavaSources(stubs + ("EnumWithFields.java" to normalized)),
            "the normalized unit must compile:\n$normalized"
        )
    }

    // -- shapes beyond the sample --------------------------------------------------------------

    @Test
    fun `an enum with no explicit constructor and a static field is repaired`() {
        val text = java(
            """
            public enum Level {
               private static final int BASE = 0;
               LOW,
               HIGH;

               public int number() {
                  return BASE + ordinal();
               }
            }
            """
        )
        assertTrue(
            parseErrors("Level.java" to text).isNotEmpty(),
            "the fixture must be illegal before the transform"
        )

        val normalized = EnumNormalizer.normalize(text)
        assertEquals(emptyList<String>(), parseErrors("Level.java" to normalized), normalized)
        assertTrue(normalized.indexOf("LOW,") < normalized.indexOf("private static final int BASE = 0;"))
        assertTrue(normalized.indexOf("HIGH;") < normalized.indexOf("public int number()"))
    }

    @Test
    fun `a nested enum is repaired inside its own body, not the outer type`() {
        // `accept.enums.EnumHolder` and its nested `Inner`, as emitted: the outer class is valid, the
        // nested enum's field precedes its constants.
        val text = java(
            """
            package accept.enums;

            import kotlin.Metadata;
            import kotlin.enums.EnumEntries;
            import kotlin.enums.EnumEntriesKt;
            import org.jetbrains.annotations.NotNull;

            public final class EnumHolder {
               @Metadata(
                  mv = {2, 4, 0},
                  k = 1,
                  xi = 48
               )
               public static enum Inner {
                  private final int code;
                  A(1),
                  B(2);

                  // §FF: synthetic field
                  private static final EnumEntries §ENTRIES = EnumEntriesKt.enumEntries(§VALUES);

                  private Inner(int code) {
                     this.code = code;
                  }

                  public final int getCode() {
                     return this.code;
                  }

                  @NotNull
                  public static EnumEntries<Inner> getEntries() {
                     return §ENTRIES;
                  }

                  // §FF: synthetic method
                  private static final Inner[] §values() {
                     Inner[] var0 = new Inner[]{A, B};
                     return var0;
                  }
               }
            }
            """
        )
        assertTrue(
            parseErrors("EnumHolder.java" to text).isNotEmpty(),
            "the fixture must be illegal before the transform"
        )

        val normalized = EnumNormalizer.normalize(text)
        assertEquals(emptyList<String>(), parseErrors("EnumHolder.java" to normalized), normalized)

        val innerStart = normalized.indexOf("public static enum Inner {")
        assertTrue(
            normalized.indexOf("A(1),") in (innerStart + 1) until normalized.indexOf("private final int code;"),
            "the nested constants must be first inside the nested body:\n$normalized"
        )
        assertTrue(normalized.indexOf("private final int code;") < normalized.indexOf("private Inner(int code)"))
        // The outer type is untouched, character for character, up to the nested declaration.
        assertEquals(
            text.substring(0, text.indexOf("public static enum Inner")),
            normalized.substring(0, normalized.indexOf("public static enum Inner"))
        )
    }

    @Test
    fun `a shape the normalizer cannot prove is returned unchanged`() {
        // The constant list never terminates with `;` — moving it would mean guessing where it ends.
        val unterminated = java(
            """
            public enum Unterminated {
               private final int n;
               A(1),
               B(2)
               public int n() {
                  return this.n;
               }
            }
            """
        )
        assertEquals(unterminated, EnumNormalizer.normalize(unterminated))

        // A member shares the first constant's line: there is no line to lift.
        val sameLine = "public enum SameLine { private final int n; A, B; }"
        assertEquals(sameLine, EnumNormalizer.normalize(sameLine))

        // Unbalanced body: nothing can be spliced.
        val unbalanced = "public enum Truncated { private final int n; A, B;"
        assertEquals(unbalanced, EnumNormalizer.normalize(unbalanced))

        // Not Java at all: returned as-is rather than half-repaired.
        val garbage = "not java at all\n"
        assertEquals(garbage, EnumNormalizer.normalize(garbage))
    }
}
