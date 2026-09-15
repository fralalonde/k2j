package org.example.k2j.core

import java.io.File
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import javax.tools.Diagnostic
import javax.tools.DiagnosticCollector
import javax.tools.JavaCompiler
import javax.tools.JavaFileObject
import javax.tools.SimpleJavaFileObject
import javax.tools.ToolProvider

/**
 * Compile gate: a unit that javac rejects never reaches the writer.
 *
 * The parse gate ([JavaValidator]) is deliberately cheap — no classpath, no attribution — so an
 * entire class of defect passes it and only shows up when the generated tree is compiled. The
 * one this pipeline hit is Kotlin's declaration-site variance
 * (`Map<String, ? extends Number>` parameter assigned to a `Map<String, Number>` field): the text
 * parses, and javac then reports
 * `incompatible types: Map(String,CAP#1) cannot be converted to Map(String,Number)`.
 *
 * Opt-in through [ConversionRequest.compileCheck] (`--compile-check` on the CLI, `--compile-check`
 * or `k2j { compileCheck }` in Gradle). Default off, because compiling every unit against a real
 * classpath is materially slower than parsing it.
 */
interface CompileChecker {
    /**
     * @return compile errors as `file:line:col: message`, empty when the unit compiles.
     * @param classpath dependencies to compile against, in classpath order. A directory in this list
     *   that holds a generated `.java` for a class the classes root also carries as a `.class` is the
     *   surface the unit is judged on: the generated source wins (`-Xprefer:source`), because that is
     *   what a real build compiles once the `.kt` is gone.
     */
    fun check(fileName: String, javaSource: String, classpath: List<Path>): List<String>

    /**
     * Compile a complete generated source surface in one javac task. Keys are binary class names.
     *
     * The default preserves compatibility for test doubles and custom collaborators. The production
     * checker overrides it: starting javac and creating a temporary output directory once per unit made
     * whole-module verification slower as more translations became valid.
     */
    fun checkAll(javaSources: Map<String, String>, classpath: List<Path>): Map<String, List<String>> =
        javaSources.mapValues { (className, source) ->
            check(className.substringAfterLast('.') + ".java", source, classpath)
        }
}

/**
 * [CompileChecker] built on `javax.tools` — `JavaCompiler.getTask`, `-proc:none`, `-nowarn`,
 * `-Xprefer:source`, and the supplied classpath. No IDE is involved, exactly like the parse gate.
 *
 * The full javac diagnostic is reported, in the same `file:line:col: message` shape the parse gate
 * uses. javac's in-process message already carries the type detail the error turns on — the message
 * for the variance defect reads `incompatible types: java.util.Map<java.lang.String,capture#1 of
 * ? extends java.lang.Number> cannot be converted to java.util.Map<java.lang.String,java.lang.Number>`
 * — so the wildcard that has to be stripped is named by the diagnostic itself.
 *
 * `-Xprefer:source` is what makes a *generated* unit win over the class file it was derived from.
 * javac otherwise prefers a `.class` it finds anywhere on the classpath over a `.java` on the
 * classpath, whatever their order: compiling a caller against the generated tree that also carries
 * the original classes root would still resolve the *class file*, and the class file of a Kotlin class
 * is exactly where a synthetic member is invisible (`java.util.Map,int,kotlin.jvm.internal.
 * DefaultConstructorMarker` — javac does not offer a synthetic member of a class file during overload
 * resolution). With `-Xprefer:source` the generated `.java` is read instead, which is the surface a
 * real build compiles: there the `.kt` is gone and only the generated source exists. Measured with
 * javac 21: without the flag the caller fails against the class file whichever classpath order is
 * used; with it, both orders resolve the generated source.
 */
class JavacCompileChecker : CompileChecker {

    override fun check(fileName: String, javaSource: String, classpath: List<Path>): List<String> {
        val className = fileName.removeSuffix(".java")
        return checkAll(mapOf(className to javaSource), classpath).getValue(className)
    }

    override fun checkAll(javaSources: Map<String, String>, classpath: List<Path>): Map<String, List<String>> {
        if (javaSources.isEmpty()) return emptyMap()
        val compiler: JavaCompiler = ToolProvider.getSystemJavaCompiler()
            ?: return javaSources.mapValues { (className, _) ->
                listOf("${className.substringAfterLast('.')}.java:0:0: no system Java compiler available (JRE without javac?)")
            }

        val diagnostics = DiagnosticCollector<JavaFileObject>()
        val fileManager = compiler.getStandardFileManager(diagnostics, null, null)
        val classesOut = Files.createTempDirectory("k2j-compile-check")
        try {
            val sourcesByUri = linkedMapOf<URI, Pair<String, JavaFileObject>>()
            for ((className, javaSource) in javaSources) {
                val uri = URI.create("string:///" + className.replace('.', '/') + ".java")
                val source = object : SimpleJavaFileObject(uri, JavaFileObject.Kind.SOURCE) {
                    override fun getCharContent(ignoreEncodingErrors: Boolean): String = javaSource
                }
                sourcesByUri[uri] = className to source
            }
            val options = mutableListOf("-proc:none", "-nowarn", "-Xprefer:source", "-d", classesOut.toString())
            if (classpath.isNotEmpty()) {
                options += "-cp"
                options += classpath.joinToString(File.pathSeparator) { it.toString() }
            }
            compiler.getTask(null, fileManager, diagnostics, options, null, sourcesByUri.values.map { it.second }).call()

            val result = javaSources.keys.associateWith { mutableListOf<String>() }
            for (error in diagnostics.diagnostics.filter { it.kind == Diagnostic.Kind.ERROR }) {
                val owner = error.source?.toUri()?.let(sourcesByUri::get)?.first
                val formatted = if (owner != null) {
                    "${owner.substringAfterLast('.')}.java:${error.lineNumber}:${error.columnNumber}: ${error.getMessage(null)}"
                } else {
                    "<javac>:${error.lineNumber}:${error.columnNumber}: ${error.getMessage(null)}"
                }
                if (owner != null) {
                    result.getValue(owner) += formatted
                } else {
                    // A compiler error with no source cannot be attributed safely. Fail every candidate.
                    result.values.forEach { it += formatted }
                }
            }
            return result
        } finally {
            fileManager.close()
            deleteQuietly(classesOut)
        }
    }

    /** Best-effort cleanup: the check's output classes are written only to prove the unit compiles. */
    private fun deleteQuietly(directory: Path) {
        try {
            Files.walk(directory).use { stream ->
                stream.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
            }
        } catch (t: Throwable) {
            // A leftover temp directory is not a reason to fail a conversion.
        }
    }
}
