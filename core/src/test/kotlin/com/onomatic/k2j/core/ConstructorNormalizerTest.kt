package com.onomatic.k2j.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * DEFECT G: FernFlower reproduces Kotlin's bytecode statement order, in which the parameter
 * null-check precedes the `super()`/`this(...)` delegation — legal in bytecode, illegal in Java
 * ("call to super must be first statement in constructor").
 *
 * Each test proves the transform on two axes: the delegation is hoisted to the first statement, AND
 * the original text really does fail javac while the normalized text compiles. That second axis is
 * what makes these tests fail if the transform is reverted (normalize returns the input untouched).
 */
class ConstructorNormalizerTest {

    /** Compiles the sources with the JDK compiler; returns the error messages (empty == success). */
    private fun compile(sources: Map<String, String>): List<String> =
        TestSupport.compileJavaSources(sources)

    @Test
    fun `hoists super ahead of the Kotlin null-check`() {
        val base = "public class Base { public Base() {} }"
        val holder = """
            public final class Holder extends Base {
               private final String id;

               public Holder(String id) {
                  check(id);
                  super();
                  this.id = id;
               }

               private static void check(String s) {}
            }
        """.trimIndent()

        val before = compile(mapOf("Base.java" to base, "Holder.java" to holder))
        assertTrue(
            before.any { it.contains("super must be first statement") },
            "fixture must be illegal Java before the transform; got $before"
        )

        val normalized = ConstructorNormalizer.normalize(holder)
        val after = compile(mapOf("Base.java" to base, "Holder.java" to normalized))
        assertEquals(emptyList<String>(), after, "normalized constructor must compile: $normalized")

        val ctor = normalized.indexOf("public Holder(String id)")
        val superIdx = normalized.indexOf("super();", ctor)
        val checkIdx = normalized.indexOf("check(id);", ctor)
        assertTrue(superIdx in (ctor + 1) until checkIdx, "super() must precede the check: $normalized")
    }

    @Test
    fun `hoists an explicit super with arguments`() {
        val base = "public class Base { private final String s; public Base(String s) { this.s = s; } public String s() { return s; } }"
        val holder = """
            public final class Sub extends Base {
               private final int n;

               public Sub(String s, int n) {
                  validate(n);
                  super(s);
                  this.n = n;
               }

               private static void validate(int n) {}
            }
        """.trimIndent()

        val before = compile(mapOf("Base.java" to base, "Sub.java" to holder))
        assertTrue(
            before.any { it.contains("super must be first statement") },
            "fixture must be illegal Java before the transform; got $before"
        )

        val normalized = ConstructorNormalizer.normalize(holder)
        val after = compile(mapOf("Base.java" to base, "Sub.java" to normalized))
        assertEquals(emptyList<String>(), after, "normalized constructor must compile: $normalized")
        assertTrue(
            normalized.indexOf("super(s);") < normalized.indexOf("validate(n);"),
            "super(args) must be hoisted: $normalized"
        )
    }

    @Test
    fun `hoists a this delegation`() {
        val base = "public class Base { public Base() {} }"
        val deleg = """
            public final class Deleg extends Base {
               private final int n;

               public Deleg(String s) {
                  helper(s);
                  this(s, 1);
               }

               public Deleg(String s, int n) {
                  super();
                  this.n = n;
                  helper(s);
               }

               private static void helper(String s) {}
            }
        """.trimIndent()

        val before = compile(mapOf("Base.java" to base, "Deleg.java" to deleg))
        assertTrue(
            before.any { it.contains("must be first statement") },
            "fixture must be illegal Java before the transform; got $before"
        )

        val normalized = ConstructorNormalizer.normalize(deleg)
        val after = compile(mapOf("Base.java" to base, "Deleg.java" to normalized))
        assertEquals(emptyList<String>(), after, "normalized constructor must compile: $normalized")
        assertTrue(
            normalized.indexOf("this(s, 1);") < normalized.indexOf("helper(s);"),
            "this(...) must be hoisted: $normalized"
        )
    }

    @Test
    fun `leaves a constructor that already leads with the delegation unchanged`() {
        val text = """
            public final class Ok extends Base {
               public Ok() {
                  super();
                  doWork();
               }
               private static void doWork() {}
            }
        """.trimIndent()
        assertEquals(text, ConstructorNormalizer.normalize(text))
    }

    @Test
    fun `leaves a constructor with no explicit delegation unchanged`() {
        // Java's implicit super() is already first, so nothing needs hoisting.
        val text = """
            public final class Plain {
               private final int n;
               public Plain(int n) {
                  this.n = n;
               }
            }
        """.trimIndent()
        assertEquals(text, ConstructorNormalizer.normalize(text))
    }

    @Test
    fun `reaches nested and local class constructors and ignores super method calls`() {
        val base = "public class Base { public Base() {} public void ping() {} }"
        val outer = """
            public final class Outer extends Base {
               public Outer() {
                  super();
               }

               static final class Nested extends Base {
                  Nested(boolean flag) {
                     check(flag);
                     super();
                  }
                  void callSuper() { super.ping(); }
                  private static void check(boolean b) {}
               }

               Runnable make() {
                  class Local extends Base {
                     Local() {
                        check(true);
                        super();
                     }
                     private void check(boolean b) {}
                  }
                  return new Runnable() {
                     public void run() { Outer.this.ping(); }
                  };
               }
            }
        """.trimIndent()

        val before = compile(mapOf("Base.java" to base, "Outer.java" to outer))
        assertTrue(before.isNotEmpty(), "fixture must be illegal before the transform; got $before")

        val normalized = ConstructorNormalizer.normalize(outer)
        val after = compile(mapOf("Base.java" to base, "Outer.java" to normalized))
        assertEquals(emptyList<String>(), after, "nested/local constructors must compile: $normalized")
        // `super.ping()` is a method call, not a delegation: it must stay where it was.
        assertEquals(1, Regex("super\\.ping\\(\\);").findAll(normalized).count())
    }

    @Test
    fun `is conservative and returns unrecognizable text unchanged`() {
        val text = "public class Broken { public Broken() { super( }"
        assertEquals(text, ConstructorNormalizer.normalize(text))
    }
}
