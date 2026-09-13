package com.onomatic.k2j.core

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/**
 * FernFlower decompilation through a forked JVM. The vendored `java-decompiler.jar` is
 * class-version 69 (Java 25) while this module builds on Java 21, so the jar is never on the
 * compile classpath and never loaded by this process: the forked `java` (from
 * [runtimeJavaHome]) runs [FernFlowerRunner] with the jar on its classpath.
 *
 * Both the class list and the library list travel over stdin (see [FernFlowerRunner]); the command
 * line carries nothing but the report path, because Windows caps a command line near 32,767
 * characters and a real translation has far more classes than that.
 *
 * Warnings FernFlower emitted during the last [decompile] are exposed through [warnings] so the
 * caller can put them in the manifest and the run log.
 */
class FernFlowerDecompiler(
    private val decompilerJar: Path,
    private val runtimeJavaHome: Path,
    /** Hard cap on the forked JVM; a hung decompiler must not hang the build forever. */
    private val forkTimeoutSeconds: Long = DEFAULT_FORK_TIMEOUT_SECONDS
) : Decompiler, WarningReporter {

    override var warnings: List<String> = emptyList()
        private set

    override fun decompile(classFiles: List<Path>, classpath: List<Path>): Map<String, String> {
        warnings = emptyList()
        if (classFiles.isEmpty()) return emptyMap()
        require(Files.isRegularFile(decompilerJar)) { "decompiler jar not found: $decompilerJar" }
        val javaExe = runtimeJavaHome.resolve("bin").resolve(javaExeName())
        require(Files.isRegularFile(javaExe)) { "forked java not found: $javaExe" }

        val workDir = Files.createTempDirectory("k2j-fernflower")
        try {
            // The runner class must be loadable by the forked JVM; extract it from this jar
            // (or the build output) next to the decompiler jar so one -cp covers both.
            val runnerClassFile = extractRunner(workDir)
            val reportFile = workDir.resolve("report.tsv")

            val command = buildCommand(javaExe, decompilerJar, runnerClassFile, reportFile)
            val stdin = buildStdinProtocol(classFiles, classpath)

            val fork = runForked(command, stdin, forkTimeoutSeconds)
            if (fork.exitCode != 0) {
                throw IllegalStateException(
                    "forked decompiler exited ${fork.exitCode}" +
                        (if (fork.stderr.isBlank()) "" else ": ${fork.stderr}")
                )
            }

            val parsed = parseReport(Files.readString(reportFile, StandardCharsets.UTF_8))
            warnings = parsed.warnings
            if (parsed.failures.isNotEmpty()) {
                throw IllegalStateException("decompiler reported failures: ${parsed.failures.joinToString(" ;; ")}")
            }
            return parsed.results
        } finally {
            workDir.toFile().deleteRecursively()
        }
    }

    /** Minimal argv: the forked JVM, its classpath, the runner, and the report path. */
    internal fun buildCommand(
        javaExe: Path,
        decompilerJar: Path,
        runnerClassFile: Path,
        reportFile: Path
    ): List<String> = listOf(
        javaExe.toString(),
        "-cp",
        "$decompilerJar${File.pathSeparator}$runnerClassFile",
        RUNNER_CLASS,
        reportFile.toString()
    )

    /** The stdin protocol: labelled path sections, one path per line, terminated by `#END`. */
    internal fun buildStdinProtocol(classFiles: List<Path>, classpath: List<Path>): ByteArray {
        val sb = StringBuilder()
        sb.append(FernFlowerRunner.CLASSES_MARKER).append('\n')
        for (classFile in classFiles.sortedBy { it.toString() }) {
            sb.append(classFile).append('\n')
        }
        sb.append(FernFlowerRunner.LIBRARIES_MARKER).append('\n')
        for (lib in classpath.sortedBy { it.toString() }) {
            sb.append(lib).append('\n')
        }
        sb.append(FernFlowerRunner.END_MARKER).append('\n')
        return sb.toString().toByteArray(StandardCharsets.UTF_8)
    }

    /**
     * Forks the JVM, feeding [stdinPayload] on stdin. stdout is discarded (nothing reads it, so an
     * undrained pipe could deadlock a chatty child), stderr is drained on a separate thread so the
     * bounded wait below cannot be defeated by a child that stays silent, and the child is killed
     * forcibly if it overruns [timeoutSeconds].
     */
    internal fun runForked(command: List<String>, stdinPayload: ByteArray, timeoutSeconds: Long): ForkResult {
        val process = ProcessBuilder(command)
            .redirectOutput(ProcessBuilder.Redirect.DISCARD)
            .redirectErrorStream(false)
            .start()

        val stderrBuffer = ByteArrayOutputStream()
        val stderrThread = Thread {
            try {
                process.errorStream.use { it.copyTo(stderrBuffer) }
            } catch (_: Throwable) {
                // The child died; whatever it wrote is already in the buffer.
            }
        }
        stderrThread.isDaemon = true
        stderrThread.start()

        try {
            process.outputStream.use { it.write(stdinPayload) }
        } catch (t: IOException) {
            // The child died before reading its input; surface its stderr instead of a bare IOException.
            process.destroyForcibly()
            stderrThread.join(1_000)
            throw IllegalStateException(
                "failed to send the class list to the forked decompiler" + stderrSuffix(stderrBuffer),
                t
            )
        }

        val finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS)
        if (!finished) {
            process.destroyForcibly()
            process.waitFor(10, TimeUnit.SECONDS)
            stderrThread.join(1_000)
            throw IllegalStateException(
                "forked decompiler did not finish within ${timeoutSeconds}s and was killed" +
                    stderrSuffix(stderrBuffer)
            )
        }
        stderrThread.join(2_000)
        return ForkResult(process.exitValue(), stderrBuffer.toString(StandardCharsets.UTF_8))
    }

    private fun stderrSuffix(stderr: ByteArrayOutputStream): String {
        val text = stderr.toString(StandardCharsets.UTF_8).trim()
        return if (text.isEmpty()) "" else ": $text"
    }

    internal data class ForkResult(val exitCode: Int, val stderr: String)

    internal data class ParsedReport(
        val results: Map<String, String>,
        val warnings: List<String>,
        val failures: List<String>
    )

    /**
     * Parses the report file. The `#RESULT` summary fields are escaped exactly like the payload
     * entries, so a warning/failure message containing a newline or tab is unescaped back to a single
     * field instead of blanking it or shifting the tab-separated columns.
     */
    internal fun parseReport(report: String): ParsedReport {
        val results = linkedMapOf<String, String>()
        var warnings = emptyList<String>()
        var failures = emptyList<String>()
        for (line in report.lineSequence()) {
            when {
                line.startsWith("#RESULT\t") -> {
                    val parts = line.split('\t')
                    warnings = splitSummary(parts.getOrElse(1) { "" })
                    failures = splitSummary(parts.getOrElse(2) { "" })
                }
                line.isNotEmpty() -> {
                    val idx = line.indexOf('\t')
                    if (idx > 0) {
                        // FernFlower passes qualifiedName with '/' separators; the contract keys
                        // results by the binary name (com.foo.Bar).
                        val key = unescape(line.substring(0, idx)).replace('/', '.')
                        val text = unescape(line.substring(idx + 1))
                        results[key] = text
                    }
                }
            }
        }
        return ParsedReport(results, warnings, failures)
    }

    private fun splitSummary(field: String): List<String> =
        unescape(field).split(" ;; ").filter { it.isNotBlank() }

    private fun unescape(s: String): String = FernFlowerRunner.unescape(s)

    /**
     * Resolves the directory/jar the runner class was loaded from and puts it on the forked
     * classpath. Covers running from build output (classes dir) and from a packaged jar.
     */
    private fun extractRunner(workDir: Path): Path {
        val codeSource = FernFlowerRunner::class.java.protectionDomain.codeSource?.location
            ?: error("cannot locate the code source of ${RUNNER_CLASS}")
        val path = Path.of(codeSource.toURI())
        check(Files.exists(path)) { "runner code source does not exist: $path" }
        return path
    }

    private fun javaExeName(): String =
        if (System.getProperty("os.name").lowercase().contains("win")) "java.exe" else "java"

    companion object {
        const val RUNNER_CLASS = "com.onomatic.k2j.core.FernFlowerRunner"
        const val RUNNER_CLASS_FILE = "com/onomatic/k2j/core/FernFlowerRunner.class"

        /** 15 minutes: generous for a real corpus, bounded so a hang cannot stall the build forever. */
        const val DEFAULT_FORK_TIMEOUT_SECONDS = 900L
    }
}
