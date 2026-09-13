package com.onomatic.k2j.core

import java.nio.file.Files
import java.nio.file.Path
import kotlin.system.exitProcess

/**
 * CLI entry point: survey → decompile → validate → write (+ optional source deletion).
 * Prints the manifest summary to stdout; exit code 0 iff manifest.success.
 */
fun main(args: Array<String>) {
    var classesRoot: Path? = null
    var outputRoot: Path? = null
    var deleteSources = false
    val packages = mutableListOf<String>()
    val classpathJars = mutableListOf<Path>()
    val sourceRoots = mutableListOf<Path>()
    var decompilerJar = Path.of(DEFAULT_DECOMPILER_JAR)
    var runtimeJavaHome = Path.of(DEFAULT_RUNTIME_JAVA_HOME)

    var i = 0
    fun need(value: String?, flag: String): String =
        value ?: error("missing value for $flag")

    while (i < args.size) {
        when (args[i]) {
            "--classes" -> classesRoot = Path.of(need(args.getOrNull(++i), "--classes"))
            "--out" -> outputRoot = Path.of(need(args.getOrNull(++i), "--out"))
            "--package" -> packages += need(args.getOrNull(++i), "--package")
            "--classpath" -> classpathJars.add(Path.of(need(args.getOrNull(++i), "--classpath")))
            "--delete-sources" -> deleteSources = true
            "--source-root" -> sourceRoots.add(Path.of(need(args.getOrNull(++i), "--source-root")))
            "--decompiler-jar" -> decompilerJar = Path.of(need(args.getOrNull(++i), "--decompiler-jar"))
            "--runtime-java-home" -> runtimeJavaHome = Path.of(need(args.getOrNull(++i), "--runtime-java-home"))
            else -> error("unknown argument: ${args[i]}")
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
            sourceRoots = sourceRoots
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
