package com.onomatic.k2j.core

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

/**
 * Owns every filesystem side effect. Writes one `<outputRoot>/<package path>/<SimpleName>.java`
 * per entry, refuses to overwrite, writes a hand-rolled pretty JSON manifest, and deletes only
 * exactly the source files it is given (never directories, never outside the list).
 */
class FileSystemWriter : OutputWriter {

    override fun write(outputs: Map<String, String>, outputRoot: Path): List<ConvertedClass> {
        val written = mutableListOf<ConvertedClass>()
        for ((className, text) in outputs) {
            val target = targetFor(className, outputRoot)
            if (Files.exists(target)) {
                throw IllegalStateException("refusing to overwrite existing file: $target")
            }
            Files.createDirectories(target.parent)
            Files.writeString(target, text, StandardCharsets.UTF_8)
            written += ConvertedClass(className, target.toString())
        }
        return written
    }

    override fun writeManifest(manifest: ConversionManifest, outputRoot: Path): Path {
        Files.createDirectories(outputRoot)
        val target = outputRoot.resolve("k2j-manifest.json")
        Files.writeString(target, toJson(manifest), StandardCharsets.UTF_8)
        return target
    }

    override fun deleteVerifiedSources(sources: List<Path>): List<String> {
        val deleted = mutableListOf<String>()
        for (source in sources) {
            if (Files.isDirectory(source)) continue
            if (Files.isRegularFile(source)) {
                Files.delete(source)
                deleted += source.toString()
            }
        }
        return deleted
    }

    private fun targetFor(className: String, outputRoot: Path): Path {
        val packagePath = className.substringBeforeLast('.', "").replace('.', '/')
        val simpleName = className.substringAfterLast('.')
        val dir = if (packagePath.isEmpty()) outputRoot else outputRoot.resolve(packagePath)
        return dir.resolve("$simpleName.java")
    }

    private fun toJson(manifest: ConversionManifest): String = buildString {
        append("{\n")
        append("  \"converted\": [\n")
        manifest.converted.forEachIndexed { i, c ->
            append("    {\"className\": ${jsonString(c.className)}, \"outputPath\": ${jsonString(c.outputPath)}}")
            append(if (i < manifest.converted.lastIndex) ",\n" else "\n")
        }
        append("  ],\n")
        append("  \"failures\": [\n")
        manifest.failures.forEachIndexed { i, f ->
            append("    {\"className\": ${jsonString(f.className)}, \"phase\": ${jsonString(f.phase)}, \"message\": ${jsonString(f.message)}}")
            append(if (i < manifest.failures.lastIndex) ",\n" else "\n")
        }
        append("  ],\n")
        append("  \"deletableSources\": [\n")
        manifest.deletableSources.forEachIndexed { i, s ->
            append("    ${jsonString(s)}")
            append(if (i < manifest.deletableSources.lastIndex) ",\n" else "\n")
        }
        append("  ],\n")
        append("  \"deletedSources\": [\n")
        manifest.deletedSources.forEachIndexed { i, s ->
            append("    ${jsonString(s)}")
            append(if (i < manifest.deletedSources.lastIndex) ",\n" else "\n")
        }
        append("  ],\n")
        append("  \"warnings\": [\n")
        manifest.warnings.forEachIndexed { i, s ->
            append("    ${jsonString(s)}")
            append(if (i < manifest.warnings.lastIndex) ",\n" else "\n")
        }
        append("  ],\n")
        append("  \"sources\": [\n")
        manifest.sources.forEachIndexed { i, d ->
            append("    {\"source\": ${jsonString(d.source)}, \"deletable\": ${d.deletable}, \"reason\": ${jsonString(d.reason)}}")
            append(if (i < manifest.sources.lastIndex) ",\n" else "\n")
        }
        append("  ],\n")
        append("  \"success\": ${manifest.success}\n")
        append("}\n")
    }

    private fun jsonString(s: String): String = buildString {
        append('"')
        for (c in s) {
            when (c) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                '\b' -> append("\\b")
                '' -> append("\\f")
                else ->
                    if (c < ' ') append("\\u%04x".format(c.code)) else append(c)
            }
        }
        append('"')
    }
}
