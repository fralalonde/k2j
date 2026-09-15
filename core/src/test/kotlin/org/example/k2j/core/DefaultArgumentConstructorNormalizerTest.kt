package org.example.k2j.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * [DefaultArgumentConstructorNormalizer] — FernFlower's *synthetic-local delegation* shape: a
 * constructor whose first statement delegates with an identifier the same body declares below it.
 *
 * Every "repairs" test proves the transform on three axes: the raw text really does fail javac
 * (`cannot find symbol: variable varN` — it *parses*, so the parse gate cannot see it), the
 * normalized text compiles, and the normalized text is the raw text with exactly the expected
 * splices, byte for byte. Every "refuses" test pins the other half of the contract: a shape that is
 * not proven comes back **identical**, so the unit is left for the gate to report instead of being
 * silently half-rewritten.
 *
 * The compiled fixture set is the unit's own text plus a stand-in for `kotlin.jvm.internal.Intrinsics`
 * (the only stdlib surface these units use), so the compile axis needs no jar cache.
 */
class DefaultArgumentConstructorNormalizerTest {

    /** Compiles with javac; returns the error messages (empty means success). */
    private fun compile(sources: Map<String, String>): List<String> = TestSupport.compileJavaSources(sources)

    /** The one stdlib class the units under test reference, reduced to the two methods they call. */
    private val intrinsics = """
        package kotlin.jvm.internal;

        public final class Intrinsics {
           public static void checkNotNullExpressionValue(Object value, String message) {
              if (value == null) throw new NullPointerException(message);
           }

           public static void checkNotNullParameter(Object value, String message) {
              if (value == null) throw new NullPointerException(message);
           }
        }
    """.trimIndent() + "\n"

    /** Compiles one unit (plus the Intrinsics stand-in) and returns javac's errors. */
    private fun compileUnit(name: String, text: String): List<String> =
        compile(mapOf("kotlin/jvm/internal/Intrinsics.java" to intrinsics, name to text))

    /**
     * Byte-exact proof that the transform did exactly the listed splices and nothing else: applying
     * them to the raw text by hand must reproduce the normalized text character for character. The
     * statements are deleted *between* their surrounding whitespace, so the blank lines the deleted
     * statements leave behind are part of the expectation on purpose.
     */
    private fun assertSpliced(raw: String, normalized: String, vararg edits: Pair<String, String>) {
        var expected = raw
        for ((old, new) in edits) {
            assertTrue(expected.contains(old), "the raw text must contain <$old>:\n$raw")
            expected = expected.replace(old, new)
        }
        assertEquals(expected, normalized, "only the matched spans may change:\n$normalized")
    }

    // -- the shape the real module fails on: ActivityId -------------------------------------------

    /** `data class ActivityId(override val objectId: UUID) : IObjectId { constructor() : this(UUID.randomUUID()) }` */
    private val activityId = """
        package accept.da;

        import java.util.UUID;
        import kotlin.jvm.internal.Intrinsics;

        public final class ActivityId {
           private final UUID objectId;

           public ActivityId(UUID objectId) {super();
              Intrinsics.checkNotNullParameter(objectId, "objectId");
              this.objectId = objectId;
           }

           public ActivityId() {this(var10001);
              UUID var10001 = UUID.randomUUID();
              Intrinsics.checkNotNullExpressionValue(var10001, "randomUUID(...)");
              
           }

           public UUID getObjectId() {
              return this.objectId;
           }
        }
    """.trimIndent() + "\n"

    @Test
    fun `the delegation is inlined, the declaration is deleted, the null-check is preserved, and the unit compiles`() {
        // Axis 1: the raw text parses (so the default gate passes it) and javac rejects it.
        assertEquals(emptyList<String>(), JavacValidator().validate("ActivityId.java", activityId))
        val before = compileUnit("accept/da/ActivityId.java", activityId)
        assertTrue(
            before.any { it.contains("cannot find symbol") && it.contains("var10001") },
            "the raw FernFlower text must reproduce the defect; got $before"
        )

        val normalized = DefaultArgumentConstructorNormalizer.normalize(activityId)

        // Axis 2: the initializer is inlined into the delegation and the unit compiles.
        assertEquals(
            emptyList<String>(),
            compileUnit("accept/da/ActivityId.java", normalized),
            "the normalized unit must compile:\n$normalized"
        )
        assertTrue(
            normalized.contains("public ActivityId() {this(java.util.Objects.requireNonNull(UUID.randomUUID(), \"randomUUID(...)\"));"),
            "the delegation must carry the inlined expression:\n$normalized"
        )
        assertFalse(normalized.contains("var10001"), "the synthetic local must be gone:\n$normalized")

        // Axis 3: every other byte is the original's — the reference is inlined and the two
        // statements are deleted, and nothing else in the unit moved.
        assertSpliced(
            activityId,
            normalized,
            "this(var10001);" to "this(java.util.Objects.requireNonNull(UUID.randomUUID(), \"randomUUID(...)\"));",
            "UUID var10001 = UUID.randomUUID();" to "",
            "Intrinsics.checkNotNullExpressionValue(var10001, \"randomUUID(...)\");" to ""
        )
    }

    @Test
    fun `normalizing twice changes nothing more`() {
        val once = DefaultArgumentConstructorNormalizer.normalize(activityId)
        assertEquals(once, DefaultArgumentConstructorNormalizer.normalize(once))
    }

    // -- the same defect through super(...), with the reference nested in the argument list --------

    /** The real `AssetBarcodeList`/`RandomIdList` shape: `constructor(n: Int) : super(arrayListOf(UUID.randomUUID()))`. */
    private val randomIdList = """
        package accept.da;

        import java.util.ArrayList;
        import java.util.Collection;
        import java.util.UUID;
        import kotlin.collections.CollectionsKt;

        public final class RandomIdList extends ArrayList<UUID> {
           public RandomIdList(int count) {super((Collection)CollectionsKt.arrayListOf(var2));
              UUID[] var2 = new UUID[]{UUID.randomUUID()};
              
           }
        }
    """.trimIndent() + "\n"

    @Test
    fun `a super delegation whose argument list references the local is inlined in place`() {
        val classpath = compileClasspath
        org.junit.jupiter.api.Assumptions.assumeTrue(
            classpath != null,
            "kotlin-stdlib-2.4.0.jar not in the Gradle module cache"
        )
        val before = TestSupport.compileJavaSources(
            mapOf("accept/da/RandomIdList.java" to randomIdList),
            classpath!!
        )
        assertTrue(
            before.any { it.contains("cannot find symbol") && it.contains("var2") },
            "the raw text must reproduce the defect; got $before"
        )

        val normalized = DefaultArgumentConstructorNormalizer.normalize(randomIdList)

        assertEquals(
            emptyList<String>(),
            TestSupport.compileJavaSources(mapOf("accept/da/RandomIdList.java" to normalized), classpath!!),
            "the normalized unit must compile:\n$normalized"
        )
        assertTrue(
            normalized.contains("super((Collection)CollectionsKt.arrayListOf(new UUID[]{UUID.randomUUID()}));"),
            "the array expression must be inlined into the delegation:\n$normalized"
        )
        assertFalse(normalized.contains("var2"), "the synthetic array local must be gone:\n$normalized")
        assertSpliced(
            randomIdList,
            normalized,
            "arrayListOf(var2)" to "arrayListOf(new UUID[]{UUID.randomUUID()})",
            "UUID[] var2 = new UUID[]{UUID.randomUUID()};" to ""
        )
    }

    // -- several constructors in one unit ---------------------------------------------------------

    /**
     * The real `InternalIdentifierDto`: three delegating constructors, two of them carrying the
     * spill, one (`(UUID var1, int var2, Object var3)`, the default-argument form) referencing a
     * declared **parameter** and therefore out of scope.
     */
    private val internalIdentifierDto = """
        package accept.da;

        import java.util.UUID;
        import kotlin.jvm.internal.Intrinsics;

        public final class InternalIdentifierDto {
           private final String id;

           public InternalIdentifierDto(String id) {super();
              Intrinsics.checkNotNullParameter(id, "id");
              this.id = id;
           }

           public InternalIdentifierDto(UUID id) {this(var10001);
              Intrinsics.checkNotNullParameter(id, "id");
              String var10001 = id.toString();
              Intrinsics.checkNotNullExpressionValue(var10001, "toString(...)");
              
           }

           public InternalIdentifierDto() {this(var10001);
              UUID var10001 = UUID.randomUUID();
              Intrinsics.checkNotNullExpressionValue(var10001, "randomUUID(...)");
              
           }

           public InternalIdentifierDto(UUID var1, int var2, Object var3) {this(var1);
              if ((var2 & 1) != 0) {
                 UUID var10000 = UUID.randomUUID();
                 Intrinsics.checkNotNullExpressionValue(var10000, "randomUUID(...)");
                 var1 = var10000;
              }

              
           }
        }
    """.trimIndent() + "\n"

    @Test
    fun `each proven constructor is repaired and the one that is not stays byte-identical`() {
        val normalized = DefaultArgumentConstructorNormalizer.normalize(internalIdentifierDto)

        assertTrue(
            normalized.contains("public InternalIdentifierDto(UUID id) {this(java.util.Objects.requireNonNull(id.toString(), \"toString(...)\"));"),
            "the later declaration (with a null-check in between) must still be inlined:\n$normalized"
        )
        assertTrue(
            normalized.contains("public InternalIdentifierDto() {this(java.util.Objects.requireNonNull(UUID.randomUUID(), \"randomUUID(...)\"));"),
            "the no-argument constructor must be inlined too:\n$normalized"
        )
        assertFalse(normalized.contains("var10001"), "both synthetic locals must be gone:\n$normalized")

        // The default-argument constructor references a parameter, has nothing to inline, and must be
        // handed to the gate untouched — a transform that guessed here would change behaviour.
        val untouched = "public InternalIdentifierDto(UUID var1, int var2, Object var3) {this(var1);"
        assertTrue(normalized.contains(untouched), "the parameter-delegation must be left alone:\n$normalized")
        assertTrue(normalized.contains("if ((var2 & 1) != 0) {"), normalized)

        assertEquals(
            emptyList<String>(),
            compileUnit("accept/da/InternalIdentifierDto.java", normalized),
            "the unit must compile once both proven constructors are repaired:\n$normalized"
        )
        val before = compileUnit("accept/da/InternalIdentifierDto.java", internalIdentifierDto)
        assertTrue(
            before.any { it.contains("cannot find symbol") && it.contains("var10001") },
            "the raw unit must be rejected by javac; got $before"
        )
    }

    // -- what must survive the deletion -----------------------------------------------------------

    /**
     * The deletion stops at the first statement that is not a null-check of the local, so a
     * constructor that does something else after the check keeps doing it.
     */
    private val keepsOthers = """
        package accept.da;

        import kotlin.jvm.internal.Intrinsics;

        public final class KeepsOthers {
           private String name;

           public KeepsOthers(String name) {super();
              Intrinsics.checkNotNullParameter(name, "name");
              this.name = name;
           }

           public KeepsOthers() {this(var10001);
              String var10001 = compute();
              Intrinsics.checkNotNullExpressionValue(var10001, "compute(...)");
              this.name = "kept";
           }

           private static String compute() {
              return "x";
           }
        }
    """.trimIndent() + "\n"

    @Test
    fun `a following statement with an effect of its own is not deleted`() {
        val normalized = DefaultArgumentConstructorNormalizer.normalize(keepsOthers)

        assertTrue(normalized.contains("public KeepsOthers() {this(java.util.Objects.requireNonNull(compute(), \"compute(...)\"));"), normalized)
        assertFalse(normalized.contains("var10001"), normalized)
        assertTrue(
            normalized.contains("this.name = \"kept\";"),
            "the statement after the null-check must survive the deletion:\n$normalized"
        )
        assertEquals(
            emptyList<String>(),
            compileUnit("accept/da/KeepsOthers.java", normalized),
            "the normalized unit must compile:\n$normalized"
        )
    }

    /** A jar from the Gradle module cache, so the `super`/`CollectionsKt` unit can be compiled. */
    private val compileClasspath: List<java.nio.file.Path>? =
        listOfNotNull(
            TestSupport.cachedJar("kotlin-stdlib-2.4.0.jar"),
            TestSupport.cachedJar("annotations-13.0.jar")
        ).takeIf { it.size == 2 }

    // -- the compiler's own order: declaration first, delegation last ------------------------------

    /**
     * The order the class file (and FernFlower, faithfully) really has: the spill and its check come
     * first, the delegation last. javac rejects it with "call to this must be first statement in
     * constructor". The same repair fixes it, so the normalizer does not depend on being applied
     * after [ConstructorNormalizer] — which is what hoists the delegation and turns this unit into
     * the undeclared-local shape the `k2j-failures` dump shows.
     */
    private val compilerOrder = """
        package accept.da;

        import java.util.UUID;
        import kotlin.jvm.internal.Intrinsics;

        public final class CompilerOrder {
           private final UUID objectId;

           public CompilerOrder(UUID objectId) {super();
              Intrinsics.checkNotNullParameter(objectId, "objectId");
              this.objectId = objectId;
           }

           public CompilerOrder() {
              UUID var10001 = UUID.randomUUID();
              Intrinsics.checkNotNullExpressionValue(var10001, "randomUUID(...)");
              this(var10001);
           }
        }
    """.trimIndent() + "\n"

    @Test
    fun `the compiler's order is repaired too, so the chain order cannot matter`() {
        val before = compileUnit("accept/da/CompilerOrder.java", compilerOrder)
        assertTrue(
            before.any { it.contains("call to this must be first statement") },
            "the compiler order must be rejected for the ordering rule first; got $before"
        )

        val normalized = DefaultArgumentConstructorNormalizer.normalize(compilerOrder)
        assertEquals(
            emptyList<String>(),
            compileUnit("accept/da/CompilerOrder.java", normalized),
            "the normalized unit must compile:\n$normalized"
        )
        assertTrue(
            normalized.contains("public CompilerOrder() {\n      \n      \n      this(java.util.Objects.requireNonNull(UUID.randomUUID(), \"randomUUID(...)\"));"),
            "the delegation must become the first statement:\n$normalized"
        )
        assertSpliced(
            compilerOrder,
            normalized,
            "this(var10001);" to "this(java.util.Objects.requireNonNull(UUID.randomUUID(), \"randomUUID(...)\"));",
            "UUID var10001 = UUID.randomUUID();" to "",
            "Intrinsics.checkNotNullExpressionValue(var10001, \"randomUUID(...)\");" to ""
        )
    }

    @Test
    fun `refuses the compiler order when a statement with its own effect precedes the delegation`() {
        assertRefused(
            """
            package accept.da;

            public final class PrecededCompilerOrder {
               public PrecededCompilerOrder(int n) {
                  log(n);
                  String var10001 = compute();
                  this(var10001);
               }

               public PrecededCompilerOrder(String s) {super();
               }

               private static String compute() {
                  return "x";
               }

               private static void log(int n) {
               }
            }
            """.trimIndent() + "\n"
        )
    }

    // -- refusals: every unproven shape comes back byte-identical ---------------------------------

    /** The other half of the contract: an unproven shape is left exactly as it arrived. */
    private fun assertRefused(text: String) {
        assertEquals(
            text,
            DefaultArgumentConstructorNormalizer.normalize(text),
            "an unproven shape must be returned unchanged:\n$text"
        )
    }

    /** Kotlin's name-based parser would confuse a reference in another constructor's body; it must not. */
    private val crossBody = """
        package accept.da;

        import kotlin.jvm.internal.Intrinsics;

        public final class CrossBody {
           public CrossBody(int n) {this(var10001);
              Intrinsics.checkNotNullParameter(n, "n");
           }

           public CrossBody() {super();
              String var10001 = "x";
              Intrinsics.checkNotNullExpressionValue(var10001, "x");
           }
        }
    """.trimIndent() + "\n"

    /** A delegation that is not the body's first statement is [ConstructorNormalizer]'s shape, not this one. */
    private val preceded = """
        package accept.da;

        public final class Preceded {
           public Preceded() {
              int n = 1;
              this(var10001);
              String var10001 = "x";
           }
        }
    """.trimIndent() + "\n"

    /** The real default-argument shape: `var1` **is** a declared parameter, the array holds the default. */
    private val parameterShape = """
        package accept.da;

        import kotlin.jvm.internal.Intrinsics;

        public final class ParameterShape {
           public ParameterShape(java.util.UUID var1, int var2, Object var3) {this(var1);
              if ((var2 & 1) != 0) {
                 java.util.UUID var10000 = java.util.UUID.randomUUID();
                 Intrinsics.checkNotNullExpressionValue(var10000, "randomUUID(...)");
                 var1 = var10000;
              }

              
           }
        }
    """.trimIndent() + "\n"

    @Test
    fun `refuses a local declared in another constructor body`() = assertRefused(crossBody)

    @Test
    fun `refuses a delegation that is not the first statement`() = assertRefused(preceded)

    @Test
    fun `refuses the default-argument shape whose identifier is a declared parameter`() =
        assertRefused(parameterShape)

    @Test
    fun `refuses when the local is used again after its declaration`() {
        assertRefused(
            """
            package accept.da;

            import kotlin.jvm.internal.Intrinsics;

            public final class UsedLater {
               private String name;

               public UsedLater(String name) {super();
                  this.name = name;
               }

               public UsedLater() {this(var10001);
                  String var10001 = compute();
                  Intrinsics.checkNotNullExpressionValue(var10001, "compute(...)");
                  this.name = var10001;
               }

               private static String compute() {
                  return "x";
               }
            }
            """.trimIndent() + "\n"
        )
    }

    @Test
    fun `refuses two declarators of the same name in one body`() {
        assertRefused(
            """
            package accept.da;

            public final class DeclaredTwice {
               public DeclaredTwice(int n) {this(var10001);
                  String var10001 = a();
                  String var10001 = b();
               }

               private static String a() {
                  return "a";
               }

               private static String b() {
                  return "b";
               }
            }
            """.trimIndent() + "\n"
        )
    }

    @Test
    fun `refuses an initializer that references a local the body declares later`() {
        assertRefused(
            """
            package accept.da;

            public final class UsesLater {
               public UsesLater(int n) {this(var10001);
                  String var10001 = compute(var9);
                  String var9 = "x";
               }

               private static String compute(String s) {
                  return s;
               }
            }
            """.trimIndent() + "\n"
        )
    }

    @Test
    fun `refuses a declaration that is not a single simple declarator`() {
        assertRefused(
            """
            package accept.da;

            public final class MultiDeclarator {
               public MultiDeclarator(int n) {this(var10001);
                  String var10001 = a(), var10002 = b();
               }

               private static String a() {
                  return "a";
               }

               private static String b() {
                  return "b";
               }
            }
            """.trimIndent() + "\n"
        )
    }

    @Test
    fun `refuses a declaration carrying a modifier or sitting in a nested block`() {
        assertRefused(
            """
            package accept.da;

            public final class ModifiedDeclaration {
               public ModifiedDeclaration(int n) {this(var10001);
                  final String var10001 = a();
               }

               private static String a() {
                  return "a";
               }
            }
            """.trimIndent() + "\n"
        )
        assertRefused(
            """
            package accept.da;

            public final class NestedDeclaration {
               public NestedDeclaration(int n) {this(var10001);
                  if (flag()) {
                     String var10001 = a();
                  }
               }

               private static boolean flag() {
                  return true;
               }

               private static String a() {
                  return "a";
               }
            }
            """.trimIndent() + "\n"
        )
    }

    @Test
    fun `repairs multiple independent spilled arguments in left-to-right order`() {
        val text = """
            package accept.da;

            public final class TwoLocals {
               public TwoLocals(int n) {this(var10001, var10002);
                  String var10001 = a();
                  String var10002 = b();
               }

               public TwoLocals(String first, String second) {super();
               }

               private static String a() {
                  return "a";
               }

               private static String b() {
                  return "b";
               }
            }
            """.trimIndent() + "\n"
        val normalized = DefaultArgumentConstructorNormalizer.normalize(text)
        assertTrue(normalized.contains("this(a(), b());"), normalized)
        assertFalse(normalized.contains("var10001"), normalized)
        assertFalse(normalized.contains("var10002"), normalized)
        assertEquals(emptyList<String>(), compileUnit("accept/da/TwoLocals.java", normalized), normalized)
        assertSpliced(
            text,
            normalized,
            "this(var10001, var10002);" to "this(a(), b());",
            "String var10001 = a();" to "",
            "String var10002 = b();" to ""
        )
    }

    @Test
    fun `never deletes a null-check that touches anything else`() {
        assertRefused(
            """
            package accept.da;

            import kotlin.jvm.internal.Intrinsics;

            public final class ForeignCheck {
               private static final Object marker = new Object();

               public ForeignCheck(int n) {this(var10001);
                  String var10001 = compute();
                  Intrinsics.checkNotNullExpressionValue(var10001, "compute(...)", marker);
               }

               private static String compute() {
                  return "x";
               }
            }
            """.trimIndent() + "\n"
        )
    }

    @Test
    fun `a constructor with a real local variable, and a delegation to a parameter, is not touched`() {
        val text = """
            package accept.da;

            import kotlin.jvm.internal.Intrinsics;

            public final class RealLocal {
               private final String name;

               public RealLocal(String name) {super();
                  Intrinsics.checkNotNullParameter(name, "name");
                  String trimmed = name.trim();
                  Intrinsics.checkNotNullExpressionValue(trimmed, "trim(...)");
                  this.name = trimmed;
               }

               public RealLocal(String name, boolean ignored) {this(name);
                  Intrinsics.checkNotNullParameter(name, "name");
               }
            }
        """.trimIndent() + "\n"

        assertEquals(
            emptyList<String>(),
            compileUnit("accept/da/RealLocal.java", text),
            "the fixture must be legal Java to begin with:\n$text"
        )
        assertRefused(text)
    }

    @Test
    fun `a fragment with no constructor is returned unchanged`() {
        assertRefused("public void run() { this(var10001); }\n")
        assertRefused("")
    }
}
