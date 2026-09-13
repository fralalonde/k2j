package com.onomatic.k2j.gradle

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.file.Directory
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.api.tasks.SourceSet
import java.io.File

/**
 * Applies the `k2j { }` extension and registers the `k2j` task.
 *
 * Wiring is reactive to the Kotlin JVM plugin by ID (`withId`), so plugin order does not matter
 * and this plugin compiles against no Kotlin Gradle Plugin API at all (KGP 2.4 marks its
 * implementation classes internal; the plugin ID is the stable public contract). A project
 * without the Kotlin plugin still configures fine — running `k2j` then fails with a
 * `k2j:`-prefixed message instead of a cryptic resolution error.
 *
 * Nothing here points at a path on one particular machine: the decompiler jar is the artifact
 * vendored inside this plugin, and the Java 25 runtime is probed at configuration time
 * ([JavaRuntime.probe]) so an explicit `k2j { runtimeJavaHome = ... }` still wins (it is
 * assigned later, during the same evaluation).
 *
 * The four core collaborators are instantiated lazily at task execution time from the core
 * classes carried on the plugin's own classpath (k2j-core is an `implementation` dependency of
 * this plugin project), so a project applying this plugin needs no extra wiring. Tests can still
 * override each collaborator through the extension.
 */
class K2jPlugin : Plugin<Project> {

    override fun apply(project: Project) {
        val ext = project.extensions.create("k2j", K2jExtension::class.java)

        ext.packages.convention(emptyList())
        ext.outputRoot.convention(project.layout.buildDirectory.dir("k2j"))
        ext.deleteSources.convention(false)
        // decompilerJar / decompilerJarSha256 / runtimeJavaHome are intentionally left without a
        // convention: the task resolves the vendored artifact and probes for a runtime. A value set
        // in k2j { } is an explicit override and wins.

        val taskProvider = project.tasks.register("k2j", K2jTask::class.java) { task ->
            task.group = "k2j"
            task.description = "Convert compiled Kotlin classes to Java sources."
            task.extension.set(ext)
            task.packages.set(ext.packages)
            task.deleteSources.set(ext.deleteSources)
            // Wired through orElse(emptyList()) so an unset override yields an empty input instead
            // of a Gradle "no value available" failure when the task's dependencies are resolved.
            task.decompilerJar.from(
                ext.decompilerJar.map { jar -> listOf(jar.asFile) }.orElse(emptyList<File>())
            )
            // Expected checksum: explicit override > "set jar without a pin means no verification"
            // > the vendored pin.
            task.decompilerJarSha256.set(
                ext.decompilerJarSha256.orElse(
                    ext.decompilerJar.map { "" }.orElse(K2jExtension.VENDORED_DECOMPILER_JAR_SHA256)
                )
            )
            task.outputRoot.set(ext.outputRoot)
            task.artifactCacheDir.set(File(project.gradle.gradleUserHomeDir, "k2j-artifacts"))
            task.kotlinPluginApplied.convention(false)
            task.runtimeJavaHome.set(ext.runtimeJavaHome)
            // The launcher binary and its reported version are the runtime inputs. Wired through
            // orElse(...) so an unresolved runtime yields an empty input instead of a Gradle
            // "no value specified" failure before the action can raise a k2j message.
            task.runtimeJavaExecutable.from(
                ext.runtimeJavaHome
                    .map { home: Directory -> listOfNotNull(JavaRuntime.javaExecutable(home.asFile)) }
                    .orElse(emptyList<File>())
            )
            task.runtimeJavaHomeVersion.set(
                ext.runtimeJavaHome
                    .map { home: Directory -> JavaRuntime.majorVersion(home.asFile)?.toString() ?: "unknown" }
                    .orElse("unknown")
            )
        }

        // Ordered probe: an explicit k2j { runtimeJavaHome = ... } always wins; otherwise accept a
        // probed Java 25 runtime so the task works out of the box on a machine with an IDE installed.
        JavaRuntime.probe()?.let { ext.runtimeJavaHome.set(it) }

        project.plugins.withId("org.jetbrains.kotlin.jvm") {
            val javaExt = project.extensions.getByType(JavaPluginExtension::class.java)
            val main: SourceSet = javaExt.sourceSets.getByName(SourceSet.MAIN_SOURCE_SET_NAME)

            project.afterEvaluate {
                taskProvider.configure { task ->
                    task.kotlinPluginApplied.set(true)
                    // KGP wires the Kotlin classes output into the source set output and the Kotlin
                    // source dirs into allJava, so the Java SourceSet covers both languages.
                    task.classesDirs.from(main.output.classesDirs)
                    task.compileClasspath.from(main.compileClasspath)
                    task.sourceRoots.from(sourceRootsOf(project, main))
                    task.dependsOn(project.tasks.named(main.classesTaskName))
                }
            }
        }
    }

    /**
     * `main.allJava.srcDirs` minus `main.java.srcDirs` is exactly the Kotlin source dirs KGP added.
     * Using `allJava` alone also contains `src/main/java`, which would make any Java edit invalidate
     * the k2j task (and drag it into whatever that invalidation triggers) for no benefit: the survey
     * only maps class files back to `.kt` sources.
     */
    private fun sourceRootsOf(project: Project, main: SourceSet): Set<File> {
        val kotlinDirs = main.allJava.srcDirs.filterNot { it in main.java.srcDirs }
        if (kotlinDirs.isNotEmpty()) return kotlinDirs.toSet()
        // Degenerate case (.kt files living under a Java source dir, or a KGP version that does not
        // register Kotlin dirs in allJava): fall back to every source dir rather than silently
        // dropping the survey's source mapping.
        if (main.allJava.srcDirs.isNotEmpty()) {
            project.logger.info(
                "k2j: could not separate Kotlin from Java source dirs (allJava=${main.allJava.srcDirs}); " +
                    "using all of them for the survey's source mapping"
            )
        }
        return main.allJava.srcDirs
    }
}
