package com.onomatic.k2j.core

/**
 * Validation gate: a generated unit that cannot be parsed as Java never reaches the writer.
 * Deliberately `javax.tools`, not PSI — this pipeline must run with no IDE on the classpath.
 */
interface JavaValidator {
    /** @return parse errors with line/column, empty when the unit parses. */
    fun validate(fileName: String, javaSource: String): List<String>
}
