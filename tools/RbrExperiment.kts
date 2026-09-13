import java.io.File
import java.nio.file.Files
import java.util.jar.Manifest

// rbr experiment: does FernFlower with default options drop bridge methods?
// Reuses the fixture classes compiled by k2j-ij-sandbox-fixture's Gradle build.

val decompilerJar = File("D:/.gradle/caches/9.0.0/transforms/c758eb8dec3a6a10386456b84606aff8/transformed/idea-2026.2.1-win/plugins/java-decompiler/lib/java-decompiler.jar")
val fixtureClasses = File("D:/Work/k2j-ij-sandbox-fixture/build/classes/kotlin/main")
val outDir = File("build/rbr-experiment").apply { deleteRecursively(); mkdirs() }

if (!decompilerJar.exists()) error("decompiler jar missing")
if (!fixtureClasses.exists()) error("fixture classes missing — build the fixture first: cd D:/Work/k2j-ij-sandbox-fixture && gradlew classes")

// Reconstruct the interface + override fixture and compile it with kotlinc? No — use the existing
// compiled fixture: Bridged.kt (interface + override) was compiled in the sandbox project.
val candidates = fixtureClasses.walkTopDown().filter { it.extension == "class" }.map { it.absolutePath }.toList()
println("input classes: ${candidates.size}")

val rbrValues = listOf("1" /* default */, "0" /* viewer behaviour */)
for (rbr in rbrValues) {
    val options = mapOf<String, Any>("rbr" to rbr)
    val sink = File(outDir, "rbr$rbr").apply { mkdirs() }
    var classCount = 0
    val saver = object : org.jetbrains.java.decompiler.main.extern.IResultSaver {
        override fun saveFolder(path: String) {}
        override fun copyFile(source: String, target: String, entry: String) {}
        override fun saveClassFile(path: String, qualifiedName: String, entryName: String, content: String, mapping: IntArray) {
            classCount++
            val target = File(sink, entryName)
            target.parentFile?.mkdirs()
            target.writeText(content)
        }
        override fun createArchive(path: String, archive: String, manifest: Manifest) {}
        override fun saveDirEntry(path: String, archive: String, entry: String) {}
        override fun copyEntry(source: String, path: String, archive: String, entry: String) {}
        override fun saveClassEntry(path: String, archive: String, qualifiedName: String, entryName: String, content: String) {
            classCount++
            val target = File(sink, entryName)
            target.parentFile?.mkdirs()
            target.writeText(content)
        }
        override fun closeArchive(path: String, archive: String) {}
    }
    val provider = object : org.jetbrains.java.decompiler.main.extern.IBytecodeProvider {
        override fun getBytecode(externalPath: String, internalPath: String): ByteArray =
            Files.readAllBytes(File(externalPath).toPath())
    }
    val decompiler = org.jetbrains.java.decompiler.main.decompiler.BaseDecompiler(provider, saver, options, object : org.jetbrains.java.decompiler.main.extern.IFernflowerLogger() {
        override fun writeMessage(message: String, severity: org.jetbrains.java.decompiler.main.extern.IFernflowerLogger.Severity) {
            if (severity == org.jetbrains.java.decompiler.main.extern.IFernflowerLogger.Severity.ERROR) println("[$rbr] FF-ERROR: $message")
        }
        override fun writeMessage(message: String, severity: org.jetbrains.java.decompiler.main.extern.IFernflowerLogger.Severity, t: Throwable) {
            if (severity == org.jetbrains.java.decompiler.main.extern.IFernflowerLogger.Severity.ERROR) println("[$rbr] FF-ERROR: $message (${t.message})")
        }
    })
    candidates.forEach { decompiler.addSource(File(it)) }
    decompiler.decompileContext()
    val bridgeCount = sink.walkTopDown().filter { it.extension == "java" }
        .sumOf { Regex("// \\\$FF: bridge method|// \\\$FF: synthetic method").findAll(it.readText()).count() }
    val objectBridges = sink.walkTopDown().filter { it.extension == "java" }
        .sumOf { Regex("public Object get\\(\\)").findAll(it.readText()).count() }
    println("rbr=$rbr: classes=$classCount bridgeMarkers=$bridgeCount objectGetBridges=$objectBridges")
}
