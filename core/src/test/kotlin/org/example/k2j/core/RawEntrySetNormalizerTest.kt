package org.example.k2j.core

import java.nio.file.Files
import java.nio.file.Path
import javax.tools.DiagnosticCollector
import javax.tools.JavaCompiler
import javax.tools.JavaFileObject
import javax.tools.SimpleJavaFileObject
import javax.tools.ToolProvider
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The inlined `Map.filter` loop FernFlower cannot type: a raw `Map` local (Kotlin writes no
 * `LocalVariableTypeTable`, so only the erased signature survives) whose `entrySet()` is iterated by
 * a `Map.Entry` variable (that local *did* carry a signature). javac reads the element type of a raw
 * `Set` as `java.lang.Object`, so the unit parses and is rejected only by the compile gate.
 * [RawEntrySetNormalizer] is the conservative text repair; these tests pin it on the real recorded
 * unit and on the shapes it must refuse.
 *
 * Each behaviour is proved on the axis that makes the test fail when the behaviour is reverted:
 *  - the verbatim `Properties` unit (the real `target module` failure) is compiled *before* and *after*
 *    the transform, with the two recorded diagnostics asserted, so a no-op `normalize` fails it;
 *  - the repaired class file is disassembled: the cast may not survive into the bytecode (its erasure
 *    is the operand's own type) while the compiler's `checkcast Map$Entry` must, so a transform that
 *    aimed the cast at the wrong type or widened the loop fails it;
 *  - every refused shape asserts the unchanged text *and* the javac rejection that follows, so
 *    dropping any one guard (the receiver proof, the `Map.Entry` element type, the `java.util.Map`
 *    name, the all-or-nothing rule) fails it.
 */
class RawEntrySetNormalizerTest {

    /** A Kotlin raw string cannot carry a bare `$`, and `$this$filter$iv` is part of the shape. */
    private fun java(text: String): String = text.trimIndent().replace('§', '$')

    private fun parseErrors(name: String, text: String): List<String> = JavacValidator().validate(name, text)

    /**
     * `com.example.app.properties.Properties`, verbatim as the run that recorded it dumped
     * it (`build/k2j-failures-wired/Properties.java`): package, imports, `@Metadata`,
     * `@SourceDebugExtension` and all. The line numbers of the two rejected `for` headers are the ones
     * the recorded `.failure.txt` names — `:56:64` and `:69:64` — which is why nothing here is elided.
     */
    private val properties = java(
        """
package com.example.app.properties;

import java.util.LinkedHashMap;
import java.util.Map;
import kotlin.Metadata;
import kotlin.collections.MapsKt;
import kotlin.jvm.internal.DefaultConstructorMarker;
import kotlin.jvm.internal.Intrinsics;
import kotlin.jvm.internal.SourceDebugExtension;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

@Metadata(
   mv = {2, 3, 0},
   k = 1,
   xi = 48,
   d1 = {"\u00004\n\u0002\u0018\u0002\n\u0002\u0018\u0002\n\u0000\n\u0002\u0010§\n\u0002\u0018\u0002\n\u0002\u0018\u0002\n\u0002\b\u0005\n\u0002\u0010\u000b\n\u0000\n\u0002\u0010\u0000\n\u0000\n\u0002\u0010\b\n\u0002\b\u0003\n\u0002\u0010\u000e\n\u0000\b\u0086\b\u0018\u00002\u00020\u0001B\u001d\u0012\u0014\b\u0002\u0010\u0002\u001a\u000e\u0012\u0004\u0012\u00020\u0004\u0012\u0004\u0012\u00020\u00050\u0003¢\u0006\u0004\b\u0006\u0010\u0007J\u0014\u0010\n\u001a\u00020\u000b2\b\u0010\f\u001a\u0004\u0018\u00010\rH\u0096\u0082\u0004J\n\u0010\u000e\u001a\u00020\u000fH\u0096\u0080\u0004J\u0015\u0010\u0010\u001a\u000e\u0012\u0004\u0012\u00020\u0004\u0012\u0004\u0012\u00020\u00050\u0003HÆ\u0003J\u001f\u0010\u0011\u001a\u00020\u00002\u0014\b\u0002\u0010\u0002\u001a\u000e\u0012\u0004\u0012\u00020\u0004\u0012\u0004\u0012\u00020\u00050\u0003HÆ\u0001J\n\u0010\u0012\u001a\u00020\u0013HÖ\u0081\u0004R \u0010\u0002\u001a\u000e\u0012\u0004\u0012\u00020\u0004\u0012\u0004\u0012\u00020\u00050\u0003X\u0096\u0004¢\u0006\b\n\u0000\u001a\u0004\b\b\u0010\t¨\u0006\u0014"},
   d2 = {"Lcom/example/properties/Properties;", "Lcom/example/properties/IProperties;", "properties", "", "Lcom/example/properties/IPropertyTypeId;", "Lcom/example/properties/PropertyValue;", "<init>", "(Ljava/util/Map;)V", "getProperties", "()Ljava/util/Map;", "equals", "", "other", "", "hashCode", "", "component1", "copy", "toString", "", "target module"}
)
@SourceDebugExtension({"SMAP\ndefinitions.kt\nKotlin\n*S Kotlin\n*F\n+ 1 definitions.kt\ncom/example/properties/Properties\n+ 2 Maps.kt\nkotlin/collections/MapsKt__MapsKt\n*L\n1#1,806:1\n567#2:807\n552#2,6:808\n567#2:814\n552#2,6:815\n*S KotlinDebug\n*F\n+ 1 definitions.kt\ncom/example/properties/Properties\n*L\n344#1:807\n344#1:808,6\n345#1:814\n345#1:815,6\n*E\n"})
public final class Properties implements IProperties {
   @NotNull
   private final Map<IPropertyTypeId, PropertyValue> properties;

   public Properties(@NotNull Map<IPropertyTypeId, PropertyValue> properties) {super();
      Intrinsics.checkNotNullParameter(properties, "properties");
      
      this.properties = properties;
   }

   // §FF: synthetic method
   public Properties(Map var1, int var2, DefaultConstructorMarker var3) {this(var1);
      if ((var2 & 1) != 0) {
         var1 = MapsKt.emptyMap();
      }

      
   }

   @NotNull
   public Map<IPropertyTypeId, PropertyValue> getProperties() {
      return this.properties;
   }

   public boolean equals(@Nullable Object other) {
      if (this == other) {
         return true;
      } else if (!(other instanceof Properties)) {
         return false;
      } else {
         Map §this§filter§iv = this.getProperties();
         int §i§f§filter = 0;
         Map destination§iv§iv = (Map)(new LinkedHashMap());
         int §i§f§filterTo = 0;

         for(Map.Entry element§iv§iv : §this§filter§iv.entrySet()) {
            int var10 = 0;
            if (!((PropertyValue)element§iv§iv.getValue()).isEmpty()) {
               destination§iv§iv.put(element§iv§iv.getKey(), element§iv§iv.getValue());
            }
         }

         §this§filter§iv = ((Properties)other).getProperties();
         Map var11 = destination§iv§iv;
         §i§f§filter = 0;
         destination§iv§iv = (Map)(new LinkedHashMap());
         §i§f§filterTo = 0;

         for(Map.Entry element§iv§iv : §this§filter§iv.entrySet()) {
            int var18 = 0;
            if (!((PropertyValue)element§iv§iv.getValue()).isEmpty()) {
               destination§iv§iv.put(element§iv§iv.getKey(), element§iv§iv.getValue());
            }
         }

         return Intrinsics.areEqual((Object)var11, (Object)destination§iv§iv);
      }
   }

   public int hashCode() {
      return this.getProperties().hashCode();
   }

   @NotNull
   public final Map<IPropertyTypeId, PropertyValue> component1() {
      return this.properties;
   }

   @NotNull
   public final Properties copy(@NotNull Map<IPropertyTypeId, PropertyValue> properties) {
      Intrinsics.checkNotNullParameter(properties, "properties");
      return new Properties(properties);
   }

   // §FF: synthetic method
   public static Properties copy§default(Properties var0, Map var1, int var2, Object var3) {
      if ((var2 & 1) != 0) {
         var1 = var0.properties;
      }

      return var0.copy(var1);
   }

   @NotNull
   public String toString() {
      return "Properties(properties=" + this.properties + ")";
   }

   public Properties() {
      this((Map)null, 1, (DefaultConstructorMarker)null);
   }
}
        """
    )

    /**
     * Everything the real unit imports, as stubs with the signatures Kotlin emits calls to — the same
     * approach [EnumNormalizerTest] and [WildcardCaptureNormalizerTest] use, so the test can hand the
     * unit to the real compiler without depending on a jar being present on the machine.
     */
    private val stubs: Map<String, String> = mapOf(
        "kotlin/Metadata.java" to
            "package kotlin;\npublic @interface Metadata { int[] mv() default {}; int k() default 0; int xi() default 0; String[] d1() default {}; String[] d2() default {}; }",
        "kotlin/jvm/internal/SourceDebugExtension.java" to
            "package kotlin.jvm.internal;\npublic @interface SourceDebugExtension { String[] value(); }",
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
        "com/example/properties/IPropertyTypeId.java" to
            "package com.example.app.properties;\npublic interface IPropertyTypeId { }",
        "com/example/properties/PropertyValue.java" to
            "package com.example.app.properties;\npublic interface PropertyValue { boolean isEmpty(); }",
        "com/example/properties/IProperties.java" to
            "package com.example.app.properties;\nimport java.util.Map;\npublic interface IProperties { Map<IPropertyTypeId, PropertyValue> getProperties(); }"
    )

    private fun compile(vararg units: Pair<String, String>): List<String> =
        TestSupport.compileJavaSources(stubs + units.toMap())

    // -- the real recorded unit -------------------------------------------------------------------

    @Test
    fun `the real Properties unit is rejected by javac before the transform and compiles after it`() {
        // The defect is invisible to the parse gate: the unit parses, which is why `Properties` was
        // reported as a COMPILE failure and why no parse-level repair could have been the fix.
        assertEquals(
            emptyList<String>(),
            parseErrors("Properties.java", properties),
            "the raw unit must parse; otherwise this fixture is not the defect"
        )

        val before = compile("Properties.java" to properties)
        // The two diagnostics the recorded failure carries, at the recorded lines and columns.
        assertEquals(2, before.size, "the raw unit must fail on exactly the two loops: $before")
        assertTrue(
            before.any { it.contains("Properties.java:56:") && it.contains("java.lang.Object cannot be converted to java.util.Map.Entry") },
            "the first recorded diagnostic must be reproduced; got $before"
        )
        assertTrue(
            before.any { it.contains("Properties.java:69:") && it.contains("java.lang.Object cannot be converted to java.util.Map.Entry") },
            "the second recorded diagnostic must be reproduced; got $before"
        )

        val normalized = RawEntrySetNormalizer.normalize(properties)

        // After the transform the same unit compiles against the same stubs.
        assertEquals(
            emptyList<String>(),
            compile("Properties.java" to normalized),
            "the normalized unit must compile:\n$normalized"
        )
        assertEquals(emptyList<String>(), parseErrors("Properties.java", normalized), normalized)

        // ...and only the two iterable expressions differ: the raw text with the two casts spliced in
        // is the normalized text, character for character.
        assertEquals(
            properties
                .replace(
                    "Map.Entry element\$iv\$iv : \$this\$filter\$iv.entrySet()",
                    "Map.Entry element\$iv\$iv : (java.util.Set<Map.Entry>) \$this\$filter\$iv.entrySet()"
                ),
            normalized,
            "nothing but the two iterable expressions may differ"
        )
        // Idempotent: the casts mean the expression no longer matches the shape.
        assertEquals(normalized, RawEntrySetNormalizer.normalize(normalized))
    }

    @Test
    fun `the repaired class file keeps the compiler's own checkcast and carries no cast of its own`() {
        // A provable raw-map loop, normalized and compiled: the cast the repair adds must be gone from
        // the bytecode (its erasure is the operand's own static type, so javac emits nothing) while the
        // per-element `checkcast Map$Entry` — the one the class file already carried — must remain.
        val raw = java(
            """
            package accept.mapfilter;

            import java.util.LinkedHashMap;
            import java.util.Map;

            public final class Own {
               public boolean equals(Object other) {
                  Map §this§filter§iv = new LinkedHashMap();

                  for(Map.Entry element§iv§iv : §this§filter§iv.entrySet()) {
                     if (!element§iv§iv.getValue().toString().isEmpty()) return false;
                  }

                  return true;
               }
            }
            """
        )
        val normalized = RawEntrySetNormalizer.normalize(raw)
        assertTrue(
            normalized.contains("for(Map.Entry element\$iv\$iv : (java.util.Set<Map.Entry>) \$this\$filter\$iv.entrySet())"),
            normalized
        )
        val bytes = compileToDir(mapOf("accept/mapfilter/Own.java" to normalized), "accept/mapfilter/Own")
        assertEquals(
            listOf("java/util/Map\$Entry"),
            castsIn(bytes, "equals"),
            "the repair may not survive into the bytecode as a new cast; the loop's own checkcast must"
        )
    }

    // -- nothing to do ----------------------------------------------------------------------------

    @Test
    fun `a unit with no entrySet is returned as the same instance`() {
        val plain = java(
            """
            package accept.plain;

            public final class Single {
               private final String name = "single";

               public String getName() {
                  return this.name;
               }
            }
            """
        )
        assertSame(plain, RawEntrySetNormalizer.normalize(plain))

        // `entrySet()` present, but nothing iterates it: still the same instance.
        val queried = java(
            """
            package accept.plain;

            import java.util.Map;

            public final class Counted {
               public int size(Map<String, String> values) {
                  return values.entrySet().size();
               }
            }
            """
        )
        assertSame(queried, RawEntrySetNormalizer.normalize(queried))
    }

    @Test
    fun `text that is not Java, an unbalanced header and a truncated unit are returned unchanged`() {
        for (text in listOf(
            "not java at all\n",
            "public final class Truncated {\n   public boolean equals(Object other) {\n      Map x = null;\n      for(Map.Entry e : x.entrySet()) {\n",
            "for(Map.Entry e : x.entrySet()) { }\n"
        )) {
            assertSame(text, RawEntrySetNormalizer.normalize(text))
        }
    }

    // -- shapes that must be refused --------------------------------------------------------------

    @Test
    fun `a receiver that is a parameter is refused`() {
        // `Map` is raw here too, so the loop is broken the same way — but a parameter is not a
        // declaration this pass can see, so the unit is left for the gate.
        val parameter = java(
            """
            package accept.mapfilter;

            import java.util.Map;

            public final class FromParameter {
               public boolean allEmpty(Map values) {
                  for(Map.Entry entry : values.entrySet()) {
                     if (entry.getValue() != null) return false;
                  }

                  return true;
               }
            }
            """
        )
        assertSame(parameter, RawEntrySetNormalizer.normalize(parameter))
        assertEquals(emptyList<String>(), parseErrors("FromParameter.java", parameter), "the fixture must parse")
        val errors = TestSupport.compileJavaSources(mapOf("accept/mapfilter/FromParameter.java" to parameter))
        assertTrue(
            errors.any { it.contains("java.lang.Object cannot be converted to java.util.Map.Entry") },
            "the refused unit must still be rejected by javac; got $errors"
        )
    }

    @Test
    fun `a parameterized receiver is refused`() {
        // The loop already compiles: `Map<K, V>`'s `entrySet()` is a `Set<Map.Entry<K, V>>`. Proving
        // nothing needs to be done is the reason the declaration has to be exactly `Map name = `.
        val parameterized = java(
            """
            package accept.mapfilter;

            import java.util.HashMap;
            import java.util.Map;

            public final class Typed {
               public boolean equals(Object other) {
                  Map<String, String> §this§filter§iv = new HashMap<String, String>();

                  for(Map.Entry element§iv§iv : §this§filter§iv.entrySet()) {
                     if (!element§iv§iv.getValue().isEmpty()) return false;
                  }

                  return true;
               }
            }
            """
        )
        assertSame(parameterized, RawEntrySetNormalizer.normalize(parameterized))
    }

    @Test
    fun `a receiver declared as another raw type is refused`() {
        // `List`'s `entrySet()` is not `Set` at all: casting it to `java.util.Set` would be a
        // `ClassCastException` the text never had, which is exactly what the proof guards against.
        val wrongType = java(
            """
            package accept.mapfilter;

            import java.util.ArrayList;
            import java.util.List;
            import java.util.Map;

            public final class NotAMap {
               public boolean allEmpty() {
                  List values = new ArrayList();

                  for(Map.Entry entry : values.entrySet()) {
                     if (entry.getValue() != null) return false;
                  }

                  return true;
               }

               public int size(Map other) {
                  return other.size();
               }
            }
            """
        )
        assertSame(wrongType, RawEntrySetNormalizer.normalize(wrongType))
    }

    @Test
    fun `an element type that is not the entry type is refused`() {
        // The same raw receiver, a different (also broken) element type: the cast would claim the
        // elements are `String`, which the bytecode's `checkcast Map$Entry` contradicts.
        val wrongElement = java(
            """
            package accept.mapfilter;

            import java.util.Map;

            public final class WrongElement {
               public boolean allEmpty() {
                  Map §this§filter§iv = null;

                  for(String element§iv§iv : §this§filter§iv.entrySet()) {
                     if (!element§iv§iv.isEmpty()) return false;
                  }

                  return true;
               }
            }
            """
        )
        assertSame(wrongElement, RawEntrySetNormalizer.normalize(wrongElement))
    }

    @Test
    fun `an iterated expression that is not a bare name dot entrySet is refused`() {
        for (expression in listOf(
            "this.properties.entrySet()",
            "values.entrySet().iterator()",
            "values.getMap().entrySet()",
            "values.entrySet(1)",
            "(java.util.Set<Map.Entry>) values.entrySet()"
        )) {
            val text = java(
                """
                package accept.mapfilter;

                import java.util.Map;

                public final class Other {
                   public boolean allEmpty(Map values) {
                      for(Map.Entry entry : $expression) {
                         if (entry.getValue() != null) return false;
                      }

                      return true;
                   }
                }
                """
            )
            assertSame(text, RawEntrySetNormalizer.normalize(text), "must be refused: $expression")
        }
    }

    @Test
    fun `a unit that does not name the fully qualified map type is refused`() {
        // Without the import (or a qualified use) the bare `Map` could be the unit's own class, whose
        // `entrySet()` is not a `java.util.Set` — and the cast would be unsound.
        val unimported = java(
            """
            package accept.mapfilter;

            public final class Unimported {
               public boolean allEmpty() {
                  Map §this§filter§iv = null;

                  for(Map.Entry element§iv§iv : §this§filter§iv.entrySet()) {
                     if (element§iv§iv.getValue() != null) return false;
                  }

                  return true;
               }
            }
            """
        )
        assertSame(unimported, RawEntrySetNormalizer.normalize(unimported))
    }

    @Test
    fun `one candidate that cannot be proved leaves the whole unit unchanged`() {
        // The first loop is provable, the second receiver is a parameter: a partial repair still
        // fails the gate, and a repair applied only where it happens to be provable is a guess.
        val mixed = java(
            """
            package accept.mapfilter;

            import java.util.LinkedHashMap;
            import java.util.Map;

            public final class Mixed {
               public boolean allEmpty(Map values) {
                  Map §this§filter§iv = new LinkedHashMap();

                  for(Map.Entry element§iv§iv : §this§filter§iv.entrySet()) {
                     if (element§iv§iv.getValue() != null) return false;
                  }

                  for(Map.Entry entry : values.entrySet()) {
                     if (entry.getValue() != null) return false;
                  }

                  return true;
               }
            }
            """
        )
        assertSame(mixed, RawEntrySetNormalizer.normalize(mixed))
    }

    @Test
    fun `a name declared both raw and parameterized is refused`() {
        // Shadowing: the loop's receiver could be either declaration, so the cast cannot be proven
        // against one of them.
        val shadowed = java(
            """
            package accept.mapfilter;

            import java.util.HashMap;
            import java.util.LinkedHashMap;
            import java.util.Map;

            public final class Shadowed {
               public boolean outer() {
                  Map values = new LinkedHashMap();

                  return inner(values);
               }

               public boolean inner() {
                  Map<String, String> values = new HashMap<String, String>();

                  for(Map.Entry entry : values.entrySet()) {
                     if (entry.getValue() != null) return false;
                  }

                  return true;
               }
            }
            """
        )
        assertSame(shadowed, RawEntrySetNormalizer.normalize(shadowed))
    }

    // -- every candidate is repaired --------------------------------------------------------------

    @Test
    fun `several provable loops in one unit are all typed`() {
        val two = java(
            """
            package accept.mapfilter;

            import java.util.LinkedHashMap;
            import java.util.Map;

            public final class Twice {
               public boolean allEmpty() {
                  Map §this§filter§iv = new LinkedHashMap();
                  Map §this§map§iv = new LinkedHashMap();

                  for(Map.Entry element§iv§iv : §this§filter§iv.entrySet()) {
                     if (element§iv§iv.getValue() != null) return false;
                  }

                  for(Map.Entry element§iv§iv§iv : §this§map§iv.entrySet()) {
                     if (element§iv§iv§iv.getValue() != null) return false;
                  }

                  return true;
               }
            }
            """
        )
        val normalized = RawEntrySetNormalizer.normalize(two)
        assertEquals(
            two
                .replace(
                    "\$this\$filter\$iv.entrySet())",
                    "(java.util.Set<Map.Entry>) \$this\$filter\$iv.entrySet())"
                )
                .replace(
                    "\$this\$map\$iv.entrySet())",
                    "(java.util.Set<Map.Entry>) \$this\$map\$iv.entrySet())"
                ),
            normalized
        )
        assertEquals(emptyList<String>(), parseErrors("Twice.java", normalized), normalized)
        assertEquals(emptyList<String>(), TestSupport.compileJavaSources(mapOf("accept/mapfilter/Twice.java" to normalized)), normalized)
    }

    // -- bytecode reading -------------------------------------------------------------------------

    /** Compiles [units] to a temp directory and returns the class file of [binaryName]. */
    private fun compileToDir(units: Map<String, String>, binaryName: String): ByteArray {
        val compiler: JavaCompiler = ToolProvider.getSystemJavaCompiler() ?: error("no system javac")
        val out = Files.createTempDirectory("k2j-rawequals-out")
        val diagnostics = DiagnosticCollector<JavaFileObject>()
        val fileManager = compiler.getStandardFileManager(diagnostics, null, null)
        try {
            val sources = units.map { (name, text) ->
                object : SimpleJavaFileObject(
                    java.net.URI.create("string:///$name"), JavaFileObject.Kind.SOURCE
                ) {
                    override fun getCharContent(ignoreEncodingErrors: Boolean): String = text
                }
            }
            val ok = compiler.getTask(
                null, fileManager, diagnostics, listOf("-proc:none", "-d", out.toString()), null, sources
            ).call()
            check(ok) { "the fixture must compile: ${diagnostics.diagnostics}" }
            return Files.readAllBytes(out.resolve("$binaryName.class"))
        } finally {
            fileManager.close()
        }
    }

    /** Every type the class file casts to inside [method], in order, read with ASM. */
    private fun castsIn(classBytes: ByteArray, method: String): List<String> {
        val casts = mutableListOf<String>()
        org.objectweb.asm.ClassReader(classBytes).accept(
            object : org.objectweb.asm.ClassVisitor(org.objectweb.asm.Opcodes.ASM9) {
                override fun visitMethod(
                    access: Int, name: String, descriptor: String,
                    signature: String?, exceptions: Array<out String>?
                ): org.objectweb.asm.MethodVisitor? {
                    if (name != method) return null
                    return object : org.objectweb.asm.MethodVisitor(org.objectweb.asm.Opcodes.ASM9) {
                        override fun visitTypeInsn(opcode: Int, type: String) {
                            if (opcode == org.objectweb.asm.Opcodes.CHECKCAST) casts += type
                        }
                    }
                }
            },
            0
        )
        return casts
    }
}
