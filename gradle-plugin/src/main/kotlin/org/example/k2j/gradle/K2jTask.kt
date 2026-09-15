package org.example.k2j.gradle

import org.example.k2j.core.AsmSurveyor
import org.example.k2j.core.ConversionFailure
import org.example.k2j.core.ConversionRequest
import org.example.k2j.core.Decompiler
import org.example.k2j.core.FernFlowerDecompiler
import org.example.k2j.core.FileSystemWriter
import org.example.k2j.core.JavaValidator
import org.example.k2j.core.JavacValidator
import org.example.k2j.core.K2j
import org.example.k2j.core.OutputWriter
import org.example.k2j.core.RunLog
import org.example.k2j.core.Surveyor
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.FileSystemOperations
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.options.Option
import java.io.File
import java.nio.file.Path
import javax.inject.Inject

/**
 * Converts the compiled Kotlin classes of the main source set into Java sources.
 *
 * Wired by [K2jPlugin]; all configuration comes from the `k2j { }` extension. The heavy lifting
 * is done by the core `K2j` orchestrator — this task assembles a [ConversionRequest], picks the
 * classes root that actually holds Kotlin classes ([ClassRoots]), clears its own output so the
 * task is repeatable, and guarantees a manifest exists before it fails.
 *
 * Core collaborators default to the real implementations (instantiated lazily at execution time,
 * on this task's classpath — k2j-core rides in with the plugin). Each can be overridden through
 * the extension for tests or alternative engines; overriding the decompiler also removes the
 * requirement that the vendored jar and a Java 25 runtime be present.
 *
 * **Every parameter of this task is also a command-line option** (`@Option`), so a run needs no
 * build-script edit: `--packages`, `--output-root`, `--delete-sources`, `--dump-failures`,
 * `--compile-check`, `--compile-classpath`, `--decompiler-jar`, `--decompiler-jar-sha256`,
 * `--runtime-java-home`. [K2jPlugin] wires the
 * extension into the task's *convention*s, so an option value — which Gradle applies with `set` —
 * always wins over the extension. `classesDirs`, `compileClasspath` and `sourceRoots` are
 * build-derived (fed from the source set by [K2jPlugin]) and are deliberately not options.
 */
abstract class K2jTask : DefaultTask() {

    /** The `k2j { }` extension, carrying the pluggable core collaborators. */
    @get:Internal
    abstract val extension: Property<K2jExtension>

    /** Set by [K2jPlugin] when `org.jetbrains.kotlin.jvm` is on the project; drives an explicit check. */
    @get:Input
    @get:Optional
    abstract val kotlinPluginApplied: Property<Boolean>

    /** Compiled classes: the Kotlin and Java outputs of the main source set. */
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val classesDirs: ConfigurableFileCollection

    /** Compile classpath the classes were built against (needed to resolve signatures). */
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NAME_ONLY)
    abstract val compileClasspath: ConfigurableFileCollection

    /** Kotlin source dirs of the main source set, for the survey's source mapping. */
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val sourceRoots: ConfigurableFileCollection

    /** Packages to restrict conversion to; empty means every Kotlin class under the classes root. */
    @get:Input
    @get:Option(
        option = "packages",
        description = "Package to convert to Java; repeat the option for several. " +
            "Omit to convert every Kotlin class under the classes root."
    )
    abstract val packages: ListProperty<String>

    /** Delete survey sources whose classes were all converted and validated. */
    @get:Input
    @get:Option(
        option = "delete-sources",
        description = "Delete the .kt sources whose declarations are all represented in the " +
            "written output. Default: keep them."
    )
    abstract val deleteSources: Property<Boolean>

    /**
     * Override for the decompiler jar — empty means "use the artifact vendored in the plugin".
     * Declared as an input so a different pin invalidates the task.
     */
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val decompilerJar: ConfigurableFileCollection

    /**
     * The `--decompiler-jar` option's value (command line only; the extension's is [decompilerJar]).
     *
     * A file *collection* cannot carry an option: Gradle's option machinery accepts only
     * `Property`, `HasMultipleValues` and `FileSystemLocationProperty`, and
     * `ConfigurableFileCollection.from` is additive, so a flag could never *replace* the
     * extension's value. This single-file property is therefore the option's storage, and it is a
     * declared input of its own ([decompilerJar] stays extension-only) so a different
     * `--decompiler-jar` invalidates the task instead of leaving it UP-TO-DATE.
     */
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NONE)
    @get:Optional
    @get:Option(
        option = "decompiler-jar",
        description = "Override the decompiler jar. Default: the artifact vendored in the k2j " +
            "plugin. An override is verified only against --decompiler-jar-sha256."
    )
    abstract val decompilerJarOverride: RegularFileProperty

    /**
     * Expected SHA-256 of the resolved decompiler jar. Carries the vendored pin by default, so a
     * re-pinned artifact invalidates the task. Empty means "no verification" (only reachable when
     * the build overrides [decompilerJar] without saying which build it means).
     */
    @get:Input
    @get:Optional
    abstract val decompilerJarSha256: Property<String>

    /**
     * The `--decompiler-jar-sha256` option's value (command line only). Declared as an input:
     * a digest supplied on the command line must change the task's inputs, or a previous
     * successful run would leave the flag with no effect at all.
     */
    @get:Input
    @get:Optional
    @get:Option(
        option = "decompiler-jar-sha256",
        description = "Expected SHA-256 of the decompiler jar; the run fails when the resolved " +
            "jar does not match."
    )
    abstract val decompilerJarSha256Override: Property<String>

    /**
     * The Java launcher used to fork the decompiler JVM. This is the runtime's *identity* as an
     * input — hashing a whole 300 MB JDK directory would be a heavy tax on a daily-driver task,
     * so the launcher binary and its reported version are the inputs instead of the directory.
     */
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val runtimeJavaExecutable: ConfigurableFileCollection

    /** Major version reported by the selected runtime (`release` file), e.g. `25`. */
    @get:Input
    @get:Optional
    abstract val runtimeJavaHomeVersion: Property<String>

    /**
     * Resolved Java home used to fork the decompiler. It is the single source of the derived
     * inputs [runtimeJavaExecutable] / [runtimeJavaHomeVersion], so `--runtime-java-home` is
     * tracked like the extension's value is.
     */
    @get:Internal
    @get:Option(
        option = "runtime-java-home",
        description = "JDK/JRE home used to fork the decompiler; needs Java 25. " +
            "Default: probe IDE JBRs, then JAVA_HOME."
    )
    abstract val runtimeJavaHome: DirectoryProperty

    /** Where the vendored jar is extracted when the plugin is loaded from a jar. */
    @get:Internal
    abstract val artifactCacheDir: DirectoryProperty

    /** Where the generated `.java` files and the manifest are written. */
    @get:OutputDirectory
    @get:Option(
        option = "output-root",
        description = "Directory for the generated .java files and the manifest. " +
            "Default: <project>/build/k2j."
    )
    abstract val outputRoot: DirectoryProperty

    /**
     * The `--dump-failures` option: where the generated text of units that fail a phase is written
     * (one `.java` per failed class plus a `.failure.txt` sidecar with the phase and the reason).
     * Also settable as `k2j { dumpFailures = ... }`.
     *
     * Declared as an optional output directory: the dump is what this task writes there, and an
     * unset value — the default — means the run dumps nothing.
     */
    @get:Optional
    @get:OutputDirectory
    @get:Option(
        option = "dump-failures",
        description = "Write the generated text of every unit that fails a phase into this " +
            "directory (one file per class plus a .failure.txt sidecar). Default: no dump."
    )
    abstract val dumpFailures: DirectoryProperty

    @get:Inject
    abstract val fileSystemOperations: FileSystemOperations

    /**
     * `--compile-check`: compile every generated unit with `javax.tools` before writing it, and
     * report a unit javac rejects as a per-class failure instead of writing it. Default `false`, so
     * the gate stays the parse it has always been.
     *
     * Declared as an input so a `--compile-check` run is not left UP-TO-DATE by a previous
     * parse-only run (and vice versa).
     */
    @get:Input
    @get:Option(
        option = "compile-check",
        description = "Compile every generated unit with javac before writing it; a unit that does " +
            "not compile is reported as a per-class failure and is not written. " +
            "Default: parse-only gate."
    )
    abstract val compileCheck: Property<Boolean>

    /**
     * Extra dependencies (jars or class directories) the compile check resolves against, on top of
     * the module's own compile classpath, which this task already carries as [compileClasspath].
     */
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NAME_ONLY)
    abstract val compileClasspathExtra: ConfigurableFileCollection

    /**
     * The `--compile-classpath` option's value (command line only; the extension's
     * [K2jExtension.compileClasspath] feeds [compileClasspathExtra]).
     *
     * A file *collection* cannot carry an option (Gradle's option machinery accepts only `Property`,
     * `HasMultipleValues` and `FileSystemLocationProperty`, and `ConfigurableFileCollection.from` is
     * additive), so the flag's storage is this repeatable string list. Declared as an input: a path
     * supplied on the command line must change the task's inputs, or a previously successful run
     * would leave the flag with no effect at all.
     */
    @get:Input
    @get:Option(
        option = "compile-classpath",
        description = "Path (jar or class directory) to compile the generated units against when " +
            "--compile-check is on; repeat for several. Added to the module's compile classpath."
    )
    abstract val compileClasspathOption: ListProperty<String>

    @TaskAction
    fun run() {
        val ext = extension.get()
        if (!kotlinPluginApplied.getOrElse(true)) {
            throw GradleException(
                "k2j: the Kotlin JVM plugin is not applied to $path, so there are no Kotlin " +
                    "classes to convert. Apply the plugin id 'org.jetbrains.kotlin.jvm' (and let it " +
                    "compile), or remove the k2j plugin from this project."
            )
        }
        val outputRootDir = outputRoot.get().asFile
        val manifestFile = outputRootDir.resolve(MANIFEST_NAME)

        val surveyor: Surveyor = ext.surveyor.orNull ?: AsmSurveyor()
        val validator: JavaValidator = ext.validator.orNull ?: JavacValidator()
        val writer: OutputWriter = ext.writer.orNull ?: FileSystemWriter()
        val decompiler: Decompiler = ext.decompiler.orNull ?: resolveDecompiler()

        // BLOCKER 1 / MAJOR 3: never take the first classes dir blindly.
        val selection = ClassRoots.select(classesDirs.files) { stagingDir(outputRootDir) }
        if (selection.isMerged) {
            logger.info(
                "k2j: ${selection.mergedFrom.size} classes roots contain Kotlin classes " +
                    "(${selection.mergedFrom.joinToString(", ") { it.absolutePath }}) - staged a merged " +
                    "copy at ${selection.root.absolutePath}"
            )
        }

        // BLOCKER 2: Gradle does not wipe a declared @OutputDirectory between executions, and the
        // core writer refuses to overwrite, so a second run would fail and stale .java files from
        // classes that disappeared would survive. Clear our own output first (after input
        // validation, so a misconfigured run never destroys the previous good output).
        fileSystemOperations.delete { it.delete(outputRootDir) }

        val request = ConversionRequest(
            classesRoot = selection.root.toPath(),
            classpath = compileClasspath.files.map { it.toPath() },
            outputRoot = outputRootDir.toPath(),
            packages = packages.get(),
            deleteSources = deleteSources.get(),
            sourceRoots = sourceRoots.files.map { it.toPath() },
            dumpFailures = dumpFailures.orNull?.asFile?.toPath(),
            // getOrElse: an unset property means the default (parse-only), so the action also works
            // for a caller that builds the task itself (K2jTaskRunTest) without the plugin wiring.
            compileCheck = compileCheck.getOrElse(false),
            compileClasspath = compileClasspathForCheck(),
            // The compile gate must resolve against EVERY classes directory of the main source set,
            // not just the one root selected for the survey. A module's own classes are split across
            // Gradle's outputs (`build/classes/java/main` for the Java sources beside
            // `build/classes/kotlin/main` for the Kotlin ones), so a generated unit that references a
            // Java annotation or utility compiled into the other directory was rejected with
            // `cannot find symbol` while the class file sat on disk. Same list as the declared
            // classesDirs the survey chose from; the selection still surveys one root.
            classesRoots = ClassRoots.compileClasspathRoots(classesDirs.files).map { it.toPath() }
        )

        val log = object : RunLog {
            override fun info(message: String) = logger.info(message)
            override fun warn(message: String) = logger.warn(message)
            override fun error(message: String, cause: Throwable?) {
                if (cause != null) logger.error(message, cause) else logger.error(message)
            }
        }

        val converter = K2j(surveyor, decompiler, validator, writer, log)
        val manifest = converter.convert(request)

        if (manifest.failures.isNotEmpty()) {
            // MAJOR 4: on a whole-run failure core returns *before* writing the manifest, so the
            // path named below would not exist exactly when the user needs it. Write it here
            // (through the same writer) before failing.
            if (!manifestFile.isFile) {
                writer.writeManifest(manifest, outputRootDir.toPath())
            }
            val detail = manifest.failures.joinToString(separator = "\n") { f: ConversionFailure ->
                "  ${f.className} [${f.phase}]: ${f.message}"
            }
            // Point at the dump when the run was asked for one: the failure messages name a position
            // in text that was never written, and that text is exactly what the dump holds.
            val dumpNote = dumpFailures.orNull?.asFile
                ?.let { "\nThe generated text of each failed unit is in ${it.absolutePath}" }
                .orEmpty()
            throw GradleException(
                "k2j: conversion failed for ${manifest.failures.size} class(es) " +
                    "(manifest: ${manifestFile.absolutePath}):\n$detail$dumpNote"
            )
        }
        logger.lifecycle("k2j: converted ${manifest.converted.size} class(es) to $outputRootDir")
    }

    /**
     * The real decompiler: vendored-or-overridden jar, verified, forked on a probed Java 25 runtime.
     *
     * Reads only this task's own declared properties — [decompilerJar], [decompilerJarSha256],
     * [runtimeJavaHome] — never the extension directly, so the inputs the task reports are the
     * inputs it acts on.
     */
    private fun resolveDecompiler(): Decompiler {
        // --decompiler-jar (this task's option) wins over `k2j { decompilerJar = ... }`.
        val override = decompilerJarOverride.orNull?.asFile ?: decompilerJar.files.singleOrNull()
        // One value, whatever its source: --decompiler-jar-sha256 > `k2j { decompilerJarSha256 }`
        // > "" when *any* jar was overridden (an override is not verified against the vendored
        // pin) > the vendored pin. K2jPlugin builds that chain.
        val expectedSha256 = decompilerJarSha256.getOrElse("")
        val jar = if (override != null) {
            if (!override.isFile) {
                fail(
                    "decompiler jar not found: ${override.absolutePath}\n" +
                        "  (--decompiler-jar / k2j { decompilerJar = ... } was set explicitly; " +
                        "leave it unset to use the artifact vendored in the k2j plugin)"
                )
            }
            override
        } else {
            DecompilerArtifact.resolveBundled(artifactCacheDir.orNull?.asFile ?: defaultArtifactCacheDir())
        }
        if (expectedSha256.isNotBlank()) {
            DecompilerArtifact.verify(jar, expectedSha256)
        }

        val home = runtimeJavaHome.orNull?.asFile
            ?: JavaRuntime.probe()
            ?: throw GradleException(JavaRuntime.notFoundMessage())
        JavaRuntime.javaExecutable(home) ?: fail(
            "no java executable under ${home.absolutePath}\\bin - " +
                "k2j { runtimeJavaHome } must point at a JDK/JRE home"
        )
        val major = JavaRuntime.majorVersion(home)
        if (major != null && major < K2jExtension.REQUIRED_JAVA_MAJOR) {
            fail(
                "the runtime at ${home.absolutePath} is Java $major, but the vendored decompiler is " +
                    "class-version 69 and needs Java ${K2jExtension.REQUIRED_JAVA_MAJOR}+ " +
                    "(${DecompilerArtifact.PIN_DESCRIPTION})."
            )
        }
        return FernFlowerDecompiler(jar.toPath(), home.toPath())
    }

    /**
     * The compile check's extra classpath, in one place: the extension's `k2j { compileClasspath }`
     * plus every `--compile-classpath` value, de-duplicated. The module's own compile classpath
     * ([compileClasspath]) is handed to core separately — core adds it, plus the classes root, to
     * whatever it is given here.
     */
    private fun compileClasspathForCheck(): List<Path> {
        val paths = LinkedHashSet<Path>()
        paths.addAll(compileClasspathExtra.files.map { it.toPath() })
        paths.addAll(compileClasspathOption.getOrElse(emptyList()).map { File(it).toPath() })
        return paths.toList()
    }

    private fun stagingDir(outputRootDir: File): File {
        // A plain read of the already-resolved output dir: querying a *mapped* provider (e.g.
        // outputRoot.map { ... }) from inside the action is rejected by Gradle with
        // "Querying the mapped value of property ... before task ... has completed".
        val base = outputRootDir.parentFile ?: File(System.getProperty("java.io.tmpdir"))
        val staging = File(base, "k2j-classes-staging")
        staging.deleteRecursively()
        return staging
    }

    private fun defaultArtifactCacheDir(): File =
        File(System.getProperty("java.io.tmpdir"), "k2j-artifacts")

    private fun fail(message: String): Nothing = throw GradleException("k2j: $message")

    companion object {
        /** Manifest written next to the generated sources (core writes it on success; the task on failure). */
        const val MANIFEST_NAME: String = "k2j-manifest.json"
    }
}
