package org.example.k2j.core

import java.nio.file.Files
import java.nio.file.Path

/**
 * Dumps the generated text of every unit that failed a phase, so the offending Java can be *read*
 * instead of reproduced by hand-running the decompiler.
 *
 * The parse gate rejects a unit with a message like
 * `CountActivityType.java:18:12: <identifier> expected` — which names a line in text nobody can
 * see, because the unit was never written. The manifest deliberately stays "one bad class never
 * aborts the run", so without a dump the only way back to the text was to fork FernFlower again on
 * that one class file with the right classpath. This class removes that step:
 *
 * - `<ClassName>.java` — the generated text exactly as the failing phase saw it (after the text
 *   transforms, so what you read is what the parse gate judged), written only when there is text;
 * - `<ClassName>.failure.txt` — `class`, `phase` and the reason, always written;
 * - the file name is the class's simple name, falling back to the binary name with `.` -> `_` when
 *   two packages of the same run declare the same simple name.
 *
 * Off unless the caller asked for it ([ConversionRequest.dumpFailures], `--dump-failures` on the CLI,
 * `k2j { dumpFailures }` in Gradle). A dump that cannot be written is reported as a warning — it
 * never turns a reported failure into a success, and never hides one.
 */
class FailureDumper(private val root: Path, private val log: RunLog) {

    /**
     * Writes the text of the failed unit [className] (null when the phase failed before any text
     * existed, e.g. the decompiler returned nothing) plus its sidecar.
     */
    fun dump(className: String, phase: String, reason: String, text: String?) {
        try {
            Files.createDirectories(root)
            val simple = className.substringAfterLast('.').ifEmpty { "unnamed" }
            val base =
                if (Files.exists(root.resolve("$simple.java")) || Files.exists(root.resolve("$simple.failure.txt")))
                    className.replace('.', '_')
                else simple
            val javaFile = root.resolve("$base.java")
            if (text != null) Files.writeString(javaFile, text)
            val sidecar = root.resolve("$base.failure.txt")
            Files.writeString(sidecar, "class: $className\nphase: $phase\nreason: $reason\n")
            log.info(
                if (text != null) "k2j: dumped the failing unit $className ($phase) to $javaFile"
                else "k2j: dumped the failure of $className ($phase) to $sidecar"
            )
        } catch (t: Throwable) {
            log.warn("k2j: could not dump the failed unit $className to $root: ${t.message}")
        }
    }
}
