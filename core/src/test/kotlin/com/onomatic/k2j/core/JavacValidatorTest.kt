package com.onomatic.k2j.core

import java.nio.file.Files
import java.nio.file.Path

class JavacValidatorTest {

    private val validator = JavacValidator()

    @org.junit.jupiter.api.Test
    fun `accepts valid decompiled text`() {
        val errors = validator.validate(
            "Single.java",
            """
            package accept.plain;

            public final class Single {
                private final int value;

                public Single(int value) {
                    this.value = value;
                }

                public final int getValue() {
                    return this.value;
                }
            }
            """.trimIndent()
        )
        org.junit.jupiter.api.Assertions.assertEquals(emptyList<String>(), errors)
    }

    @org.junit.jupiter.api.Test
    fun `rejects truncated source with line and column`() {
        val errors = validator.validate("Broken.java", "class Broken {")
        org.junit.jupiter.api.Assertions.assertTrue(errors.isNotEmpty(), "truncated source must produce parse errors")
        org.junit.jupiter.api.Assertions.assertTrue(errors[0].matches(Regex("Broken\\.java:\\d+:\\d+: .*")), "expected file:line:col: message, got: $errors")
    }

    @org.junit.jupiter.api.Test
    fun `parse-only does not require resolvable symbols`() {
        // References to unknown types must NOT fail: this is a parse gate, not a compile gate.
        val errors = validator.validate(
            "Unresolved.java",
            """
            package x;

            public class Unresolved {
                public DefinitelyNotOnClasspath thing() {
                    return null;
                }
            }
            """.trimIndent()
        )
        org.junit.jupiter.api.Assertions.assertEquals(emptyList<String>(), errors)
    }
}
