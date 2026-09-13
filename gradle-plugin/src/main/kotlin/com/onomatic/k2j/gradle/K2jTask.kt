package com.onomatic.k2j.gradle

import com.onomatic.k2j.core.AsmSurveyor
import com.onomatic.k2j.core.ConversionFailure
import com.onomatic.k2j.core.ConversionRequest
import com.onomatic.k2j.core.Decompiler
import com.onomatic.k2j.core.FernFlowerDecompiler
import com.onomatic.k2j.core.FileSystemWriter
import com.onomatic.k2j.core.JavaValidator
import com.onomatic.k2j.core.JavacValidator
import com.onomatic.k2j.core.K2j
import com.onomatic.k2j.core.OutputWriter
import com.onomatic.k2j.core.RunLog
import com.onomatic.k2j.core.Surveyor
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.FileSystemOperations
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
import java.io.File
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
    abstract val packages: ListProperty<String>

    /** Delete survey sources whose classes were all converted and validated. */
    @get:Input
    abstract val deleteSources: Property<Boolean>

    /**
     * Override for the decompiler jar — empty means "use the artifact vendored in the plugin".
     * Declared as an input so a different pin invalidates the task.
     */
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val decompilerJar: ConfigurableFileCollection

    /**
     * Expected SHA-256 of the resolved decompiler jar. Carries the vendored pin by default, so a
     * re-pinned artifact invalidates the task. Empty means "no verification" (only reachable when
     * the build overrides [decompilerJar] without saying which build it means).
     */
    @get:Input
    @get:Optional
    abstract val decompilerJarSha256: Property<String>

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

    /** Resolved Java home used to fork the decompiler; derived from [runtimeJavaExecutable]'s parent. */
    @get:Internal
    abstract val runtimeJavaHome: DirectoryProperty

    /** Where the vendored jar is extracted when the plugin is loaded from a jar. */
    @get:Internal
    abstract val artifactCacheDir: DirectoryProperty

    @get:OutputDirectory
    abstract val outputRoot: DirectoryProperty

    @get:Inject
    abstract val fileSystemOperations: FileSystemOperations

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
            sourceRoots = sourceRoots.files.map { it.toPath() }
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
            throw GradleException(
                "k2j: conversion failed for ${manifest.failures.size} class(es) " +
                    "(manifest: ${manifestFile.absolutePath}):\n$detail"
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
        val override = decompilerJar.files.singleOrNull()
        val expectedSha256 = decompilerJarSha256.getOrElse("")
        val jar = if (override != null) {
            if (!override.isFile) {
                fail(
                    "decompiler jar not found: ${override.absolutePath}\n" +
                        "  (k2j { decompilerJar = ... } was set explicitly; leave it unset to use the " +
                        "artifact vendored in the k2j plugin)"
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
