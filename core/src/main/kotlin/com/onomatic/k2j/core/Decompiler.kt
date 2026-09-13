package com.onomatic.k2j.core

import java.nio.file.Path

/**
 * The decompiler seam. Implementations pin one exact FernFlower artifact and expose only the options
 * the pipeline needs; nothing else in `core` touches the decompiler directly.
 */
interface Decompiler {
    /**
     * @return generated Java text per top-level class, keyed by binary name (`com.foo.Bar`).
     *   Inner classes appear nested inside their outer unit, not as separate entries.
     */
    fun decompile(classFiles: List<Path>, classpath: List<Path>): Map<String, String>
}

/**
 * Optional capability: a decompiler that collected non-fatal diagnostics during the last
 * [Decompiler.decompile] call. Kept as a separate interface so the frozen [Decompiler] signature
 * stays untouched; [K2j] surfaces these in the manifest and the run log.
 */
interface WarningReporter {
    /** Warnings from the most recent decompilation; empty before the first call. */
    val warnings: List<String>
}
