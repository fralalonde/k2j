package org.example.k2j.core

import java.nio.file.Files
import java.nio.file.Path
import kotlin.system.exitProcess

/**
 * CLI entry point: survey → decompile → normalize → validate → write (+ optional source deletion).
 * Prints the manifest summary to stdout; exit code 0 iff manifest.success.
 *
 * `--dump-failures <dir>` writes the generated text of every unit that failed a phase into `<dir>`
 * (one `.java` per class plus a `.failure.txt` sidecar with the phase and the reason), so a unit the
 * parse gate rejects can be read instead of reproduced by hand-running the decompiler. Off by default.
 *
 * `--compile-check` upgrades the gate from a parse to a real compile: every unit that parses is
 * compiled with `javax.tools` before it is written, against `--compile-classpath` (repeatable; the
 * classes root is always added). A unit javac rejects is reported as a per-class failure carrying
 * javac's file/line/column/message and is not written. Off by default.
 */
fun main(args: Array<String>) {
    var classesRoot: Path? = null
    var outputRoot: Path? = null
    var deleteSources = false
    var dumpFailures: Path? = null
    var compileCheck = false
    val packages = mutableListOf<String>()
    val classpathJars = mutableListOf<Path>()
    val sourceRoots = mutableListOf<Path>()
    val compileClasspath = mutableListOf<Path>()
    var decompilerJar = Path.of(DEFAULT_DECOMPILER_JAR)
    var runtimeJavaHome = Path.of(DEFAULT_RUNTIME_JAVA_HOME)

    var i = 0
    fun need(value: String?, flag: String): String =
        value ?: error("missing value for $flag")

    while (i < args.size) {
        // Both spellings are accepted: `--out <dir>` and `--out=<dir>`.
        val argument = args[i]
        val equals = if (argument.startsWith("--")) argument.indexOf('=') else -1
        val flag = if (equals > 0) argument.substring(0, equals) else argument
        val inline = if (equals > 0) argument.substring(equals + 1) else null
        fun value(name: String): String = inline ?: need(args.getOrNull(++i), name)
        when (flag) {
            "--classes" -> classesRoot = Path.of(value("--classes"))
            "--out" -> outputRoot = Path.of(value("--out"))
            "--package" -> packages += value("--package")
            "--classpath" -> classpathJars.add(Path.of(value("--classpath")))
            "--delete-sources" -> deleteSources = true
            "--source-root" -> sourceRoots.add(Path.of(value("--source-root")))
            "--decompiler-jar" -> decompilerJar = Path.of(value("--decompiler-jar"))
            "--runtime-java-home" -> runtimeJavaHome = Path.of(value("--runtime-java-home"))
            "--dump-failures" -> dumpFailures = Path.of(value("--dump-failures"))
            "--compile-check" -> compileCheck = true
            "--compile-classpath" -> compileClasspath.add(Path.of(value("--compile-classpath")))
            else -> error("unknown argument: $argument")
        }
        i++
    }

    val classes = classesRoot ?: error("--classes is required")
    val out = outputRoot ?: error("--out is required")

    val k2j = K2j(
        surveyor = AsmSurveyor(),
        decompiler = FernFlowerDecompiler(decompilerJar, runtimeJavaHome),
        validator = JavacValidator(),
        writer = FileSystemWriter(),
        log = object : RunLog {
            override fun info(message: String) = println(message)
            override fun warn(message: String) = println("WARN: $message")
            override fun error(message: String, cause: Throwable?) {
                println("ERROR: $message")
                cause?.printStackTrace()
            }
        }
    )

    val manifest = k2j.convert(
        ConversionRequest(
            classesRoot = classes,
            classpath = classpathJars,
            outputRoot = out,
            packages = packages,
            deleteSources = deleteSources,
            sourceRoots = sourceRoots,
            dumpFailures = dumpFailures,
            compileCheck = compileCheck,
            compileClasspath = compileClasspath
        )
    )

    println("converted: ${manifest.converted.size}")
    for (c in manifest.converted) println("  ${c.className} -> ${c.outputPath}")
    println("failures: ${manifest.failures.size}")
    for (f in manifest.failures) println("  [${f.phase}] ${f.className}: ${f.message}")
    println("deletable sources: ${manifest.deletableSources.size}")
    for (s in manifest.deletableSources) println("  $s")
    println("deleted sources: ${manifest.deletedSources.size}")
    for (s in manifest.deletedSources) println("  $s")
    println("warnings: ${manifest.warnings.size}")
    for (w in manifest.warnings) println("  $w")
    println("sources: ${manifest.sources.size}")
    for (d in manifest.sources) println("  ${if (d.deletable) "deletable" else "kept"}: ${d.source} (${d.reason})")
    println("success: ${manifest.success}")

    if (!manifest.success) exitProcess(1)
}

/**
 * Development-machine default: a hardcoded path into this machine's Gradle transform cache. It is
 * NOT vendored or checksum-pinned — production callers must pass `--decompiler-jar` (the Gradle
 * plugin resolves the jar from the project's dependencies). Kept only so `core` can be run by hand
 * on the machine it was developed on.
 */
const val DEFAULT_DECOMPILER_JAR =
    "D:/.gradle/caches/9.0.0/transforms/c758eb8dec3a6a10386456b84606aff8/transformed/idea-2026.2.1-win/plugins/java-decompiler/lib/java-decompiler.jar"

/** Development-machine default: the JBR of the IDE installed on this machine. Not portable. */
const val DEFAULT_RUNTIME_JAVA_HOME =
    "C:/Users/FrancisLalonde/AppData/Local/Programs/IntelliJ IDEA Ultimate/jbr"
