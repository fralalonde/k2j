package org.example.k2j.gradle

import org.gradle.testkit.runner.GradleRunner
import java.io.File

/**
 * Shared helpers for the Gradle TestKit tests.
 *
 * The subject projects are always built in a temp dir and consume this plugin through a
 * `pluginManagement { includeBuild(...) }` composite — the same way a real consumer would.
 * `--no-configuration-cache` is passed explicitly: the composite (included-build) classloader
 * does not support the configuration cache for this plugin (see IMPLEMENTATION.md, "Known
 * limitations"); plain buildscript consumption with the configuration cache works.
 */
internal object TestKitSupport {

    val pluginProject: File = File("..").canonicalFile

    fun tempProject(name: String, buildScript: String, sources: Map<String, String> = emptyMap()): File {
        val dir = java.nio.file.Files.createTempDirectory(name).toFile().canonicalFile
        dir.resolve("settings.gradle.kts").writeText(
            """
                pluginManagement {
                    includeBuild("${ascii(pluginProject)}")
                }
                rootProject.name = "$name"
            """.trimIndent()
        )
        dir.resolve("build.gradle.kts").writeText(buildScript)
        sources.forEach { (relativePath, content) ->
            val target = dir.resolve(relativePath)
            target.parentFile.mkdirs()
            target.writeText(content)
        }
        return dir
    }

    fun run(projectDir: File, vararg args: String) =
        GradleRunner.create()
            .withProjectDir(projectDir)
            .withArguments(*args, "--no-configuration-cache", "--console=plain")
            .withPluginClasspath()
            .forwardOutput()
            .build()

    fun runAndFail(projectDir: File, vararg args: String) =
        GradleRunner.create()
            .withProjectDir(projectDir)
            .withArguments(*args, "--no-configuration-cache", "--console=plain")
            .withPluginClasspath()
            .forwardOutput()
            .buildAndFail()

    /** Absolute path with forward slashes, as Gradle's includeBuild wants it. */
    private fun ascii(file: File): String = file.invariantSeparatorsPath

    /** The build script fragment that prints everything the wiring tests assert on. */
    val printWiringTask: String = """
        tasks.register("printK2jWiring") {
            doLast {
                val t = project.tasks.named("k2j", org.example.k2j.gradle.K2jTask::class.java).get()
                t.sourceRoots.files.sortedBy { it.absolutePath }
                    .forEach { println("K2J_SOURCE_ROOT=" + it.absolutePath) }
                println("K2J_CLASSES_DIR=" + t.classesDirs.files.joinToString("|"))
                println("K2J_KOTLIN_PLUGIN=" + t.kotlinPluginApplied.getOrElse(false))
                println("K2J_JAR_SHA=" + t.decompilerJarSha256.getOrElse(""))
                println("K2J_RUNTIME=" + (t.runtimeJavaHome.orNull?.asFile ?: "unset"))
                println("K2J_RUNTIME_EXE=" + t.runtimeJavaExecutable.files.joinToString("|"))
            }
        }
    """.trimIndent()
}
