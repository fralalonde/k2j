package com.onomatic.k2j.core

import java.nio.file.Files
import java.nio.file.Path
import javax.tools.JavaCompiler
import javax.tools.JavaFileObject
import javax.tools.SimpleJavaFileObject
import javax.tools.ToolProvider
import java.net.URI

object TestSupport {
    /** Corpus classes dir; tests degrade gracefully when it has not been built yet. */
    val corpusClasses: Path? =
        Path.of("D:/Work/k2j/corpus/app/build/classes/kotlin/main").takeIf { Files.isDirectory(it) }

    val corpusSources: Path = Path.of("D:/Work/k2j/corpus/app/src/main/kotlin")

/**
     * Writes a minimal Kotlin-looking facade class file (kotlin/Metadata annotation, no members)
     * built with ASM. Used to exercise survey rules the corpus does not cover.
     */
    fun writeFacadeClassFile(classesRoot: Path, packagePath: String, simpleName: String, sourceFile: String?) {
        val cw = org.objectweb.asm.ClassWriter(0)
        cw.visit(
            org.objectweb.asm.Opcodes.V1_8,
            org.objectweb.asm.Opcodes.ACC_PUBLIC + org.objectweb.asm.Opcodes.ACC_FINAL + org.objectweb.asm.Opcodes.ACC_SUPER,
            "$packagePath/$simpleName".replace('.', '/'),
            null,
            "java/lang/Object",
            null
        )
        if (sourceFile != null) cw.visitSource(sourceFile, null)
        cw.visitAnnotation("Lkotlin/Metadata;", true).visitEnd()
        cw.visitMethod(org.objectweb.asm.Opcodes.ACC_PUBLIC, "ping", "()I", null, null)
            .apply {
                visitCode()
                visitInsn(org.objectweb.asm.Opcodes.ICONST_0)
                visitInsn(org.objectweb.asm.Opcodes.IRETURN)
                visitMaxs(1, 1)
                visitEnd()
            }
        cw.visitEnd()
        val target = classesRoot.resolve("$packagePath/$simpleName.class")
        Files.createDirectories(target.parent)
        Files.write(target, cw.toByteArray())
    }

    /** Compiles a tiny Java class at test time so the survey has a non-Kotlin file to reject. */
    fun compileJavaClass(dir: Path, packageName: String, className: String): Path {
        Files.createDirectories(dir)
        val compiler: JavaCompiler = ToolProvider.getSystemJavaCompiler()
            ?: error("no system java compiler in test JVM")
        val source = """
            package $packageName;

            public class $className {
                public int plain() { return 1; }
            }
        """.trimIndent()
        val fileObject = object : SimpleJavaFileObject(
            URI.create("string:///$className.java"), JavaFileObject.Kind.SOURCE
        ) {
            override fun getCharContent(ignoreEncodingErrors: Boolean): String = source
        }
        val task = compiler.getTask(null, null, null, listOf("-d", dir.toString()), null, listOf(fileObject))
        val ok = task.call()
        check(ok) { "test fixture compile failed" }
        return dir.resolve("$packageName/$className.class")
    }

    /**
     * Compiles in-memory Java sources with the JDK compiler and returns the ERROR diagnostics
     * (`file:line: message`). Empty list means the compilation succeeded. Used to prove generated
     * Java is not just parseable but compilable.
     */
    fun compileJavaSources(sources: Map<String, String>, classpath: List<Path> = emptyList()): List<String> {
        val compiler: JavaCompiler = ToolProvider.getSystemJavaCompiler()
            ?: error("no system java compiler in test JVM")
        val outDir = Files.createTempDirectory("k2j-javac-out")
        val diagnostics = javax.tools.DiagnosticCollector<JavaFileObject>()
        val fileManager = compiler.getStandardFileManager(diagnostics, null, null)
        try {
            val units = sources.map { (name, text) ->
                object : SimpleJavaFileObject(URI.create("string:///$name"), JavaFileObject.Kind.SOURCE) {
                    override fun getCharContent(ignoreEncodingErrors: Boolean): String = text
                }
            }
            val options = mutableListOf("-proc:none", "-d", outDir.toString())
            if (classpath.isNotEmpty()) {
                options += "-cp"
                options += classpath.joinToString(java.io.File.pathSeparator) { it.toString() }
            }
            compiler.getTask(null, fileManager, diagnostics, options, null, units).call()
            return diagnostics.diagnostics
                .filter { it.kind == javax.tools.Diagnostic.Kind.ERROR }
                .map { "${it.source?.name ?: "?"}:${it.lineNumber}: ${it.getMessage(null)}" }
        } finally {
            fileManager.close()
        }
    }
}
