package org.example.k2j.core

import java.net.URI
import javax.tools.Diagnostic
import javax.tools.DiagnosticCollector
import javax.tools.JavaCompiler
import javax.tools.JavaFileObject
import javax.tools.SimpleJavaFileObject
import javax.tools.ToolProvider

/**
 * Parse-only validation with `javax.tools`: uses `JavacTask.parse()` so the source is parsed
 * but never compiled (no classpath needed, no resolution — pure syntax gate).
 */
class JavacValidator : JavaValidator {

    override fun validate(fileName: String, javaSource: String): List<String> {
        val compiler: JavaCompiler = ToolProvider.getSystemJavaCompiler()
            ?: return listOf("$fileName:0:0: no system Java compiler available (JRE without javac?)")

        val diagnostics = DiagnosticCollector<JavaFileObject>()
        val fileManager = compiler.getStandardFileManager(diagnostics, null, null)
        try {
            val source = object : SimpleJavaFileObject(
                URI.create("string:///$fileName"), JavaFileObject.Kind.SOURCE
            ) {
                override fun getCharContent(ignoreEncodingErrors: Boolean): String = javaSource
            }
            val task = compiler.getTask(null, fileManager, diagnostics, listOf("-proc:none"), null, listOf(source))
                as? com.sun.source.util.JavacTask
                ?: return listOf("$fileName:0:0: compiler tool does not support parse-only mode")
            task.parse()
            return diagnostics.diagnostics
                .filter { it.kind == Diagnostic.Kind.ERROR }
                .map { d ->
                    "$fileName:${d.lineNumber}:${d.columnNumber}: ${d.getMessage(null)}"
                }
        } finally {
            fileManager.close()
        }
    }
}
