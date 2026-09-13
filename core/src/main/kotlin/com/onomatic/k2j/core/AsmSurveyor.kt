package com.onomatic.k2j.core

import org.objectweb.asm.ClassReader
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.name
import kotlin.streams.asSequence

/**
 * ASM-based survey: walks the classes root, keeps only classes carrying `kotlin/Metadata`, groups
 * each class file with its top-level unit, and maps every accepted class file back to its `.kt`
 * source when one can be found unambiguously.
 *
 * Bytecode view of a Kotlin unit: `Outer.class` plus one file per nested/inner/local/anonymous class
 * (`Outer$Nested`, `Outer$Inner`, `Outer$makeLocal$Local`, `Outer$makeAnon$1`). All of them carry
 * `SourceFile = "Outer.kt"`. A unit's target is the top-level class; the whole sibling group is
 * handed to the decompiler so the generated Java contains the nested types (defect A). Every class
 * file of the group is recorded on the [SourceMapping] so a class that produced no output blocks
 * deletion (defect B). Sources the bytecode cannot fully account for — files declaring a `typealias`
 * (no member is emitted for it), or a class whose source is ambiguous across several roots — are
 * recorded as deletion blockers (defects C/F).
 */
class AsmSurveyor : Surveyor {

    override fun survey(request: ConversionRequest): SurveyResult {
        val targets = mutableListOf<ClassTarget>()
        val excluded = mutableListOf<ExcludedClass>()
        val sourcesByClassFile = linkedMapOf<Path, Path>()
        val blockersBySource = linkedMapOf<Path, MutableList<String>>()

        val classFiles = Files.walk(request.classesRoot).use { stream ->
            stream.asSequence()
                .filter { it.name.endsWith(".class") && Files.isRegularFile(it) }
                .sorted()
                .toList()
        }

        // One in-package Kotlin class file, grouped later by its top-level class.
        data class Candidate(
            val classFile: Path,
            val className: String,
            val topLevelName: String,
            val sourceFileAttribute: String?
        )

        val candidates = mutableListOf<Candidate>()
        for (classFile in classFiles) {
            val bytes = try {
                Files.readAllBytes(classFile)
            } catch (t: Throwable) {
                null
            }
            if (bytes == null) {
                excluded += ExcludedClass(classFile, classFile.name, "not a readable class file")
                continue
            }
            val className = try {
                // ClassReader.className is the internal name (com/foo/Bar); the contract wants the
                // binary name (com.foo.Bar).
                ClassReader(bytes).className.replace('/', '.')
            } catch (t: Throwable) {
                null
            }
            if (className == null) {
                excluded += ExcludedClass(classFile, classFile.name, "not a readable class file")
                continue
            }
            if (className == "module-info" || className == "package-info") {
                excluded += ExcludedClass(classFile, className, "module/package descriptor")
                continue
            }
            if (!hasKotlinMetadata(bytes)) {
                excluded += ExcludedClass(classFile, className, "no kotlin/Metadata annotation")
                continue
            }
            if (!inRequestedPackages(className, request.packages)) {
                excluded += ExcludedClass(classFile, className, "outside requested packages")
                continue
            }
            candidates += Candidate(classFile, className, topLevelName(className), readSourceFileAttribute(bytes))
        }

        // Group by top-level class: `com.foo.Outer`, `com.foo.Outer$Nested`, ... all belong to the
        // single target `com.foo.Outer`.
        val byTopLevel = candidates.groupBy { it.topLevelName }
        for ((topLevel, group) in byTopLevel.entries.sortedBy { it.key }) {
            val sortedGroup = group.sortedBy { it.classFile.toString() }
            val unitFiles = sortedGroup.map { it.classFile }
            val outer = sortedGroup.firstOrNull { it.className == topLevel }?.classFile
            if (outer == null) {
                // Nested classes without their outer class file: there is no unit to decompile.
                // Record the exclusion; the source mappings below still block any deletion.
                for (c in sortedGroup) {
                    excluded += ExcludedClass(
                        c.classFile, c.className,
                        "nested class whose outer class $topLevel is not among the conversion targets"
                    )
                }
            } else {
                targets += ClassTarget(outer, topLevel, unitFiles)
            }

            // Record EVERY class file of the source, not just the ones that convert, so a class that
            // produced zero outputs cannot slip past the deletion guard.
            for (c in sortedGroup) {
                when (val lookup = findSource(c.className, c.sourceFileAttribute, request.sourceRoots)) {
                    is SourceLookup.Found ->
                        sourcesByClassFile[c.classFile] = lookup.source
                    is SourceLookup.Ambiguous -> {
                        val detail = lookup.matched.joinToString(", ") { it.toString() }
                        excluded += ExcludedClass(
                            c.classFile, c.className,
                            "ambiguous source mapping: ${lookup.matched.size} source roots matched ($detail)"
                        )
                        for (source in lookup.matched) {
                            blockersBySource.getOrPut(source) { mutableListOf() } +=
                                "ambiguous source mapping for ${c.className}: ${lookup.matched.size} source roots matched"
                        }
                    }
                    SourceLookup.NotFound -> Unit
                }
            }
        }

        // Sources declaring a `typealias`: no member is emitted for an alias, so the generated output
        // never represents it. Detect it in the source text and refuse deletion (fail closed).
        for (source in sourcesByClassFile.values.toSet()) {
            val declarations = unrepresentedDeclarations(source)
            if (declarations.isNotEmpty()) {
                blockersBySource.getOrPut(source) { mutableListOf() } += declarations
            }
        }

        targets.sortBy { it.className }
        excluded.sortWith(compareBy({ it.classFile.toString() }, { it.className }))

        val sourceSet = LinkedHashSet<Path>()
        sourceSet.addAll(sourcesByClassFile.values)
        sourceSet.addAll(blockersBySource.keys)
        val sources = sourceSet
            .map { source ->
                SourceMapping(
                    source = source,
                    classFiles = sourcesByClassFile.filterValues { it == source }.keys.sorted(),
                    deletionBlockers = blockersBySource[source]?.distinct()?.sorted() ?: emptyList()
                )
            }
            .sortedBy { it.source.toString() }

        return SurveyResult(targets, excluded, sources)
    }

    private fun inRequestedPackages(className: String, packages: List<String>): Boolean =
        packages.isEmpty() || packages.any { pkg -> className.startsWith("$pkg.") }

    /** `com.foo.Outer$Nested` -> `com.foo.Outer`; a top-level class maps to itself. */
    private fun topLevelName(className: String): String {
        val simpleName = className.substringAfterLast('.')
        val outerSimple = simpleName.substringBefore('$')
        return className.substringBeforeLast('.', "") .let { pkg ->
            if (pkg.isEmpty()) outerSimple else "$pkg.$outerSimple"
        }
    }

    private fun hasKotlinMetadata(bytes: ByteArray): Boolean = try {
        val reader = ClassReader(bytes)
        var found = false
        reader.accept(object : org.objectweb.asm.ClassVisitor(org.objectweb.asm.Opcodes.ASM9) {
            override fun visitAnnotation(descriptor: String, visible: Boolean): org.objectweb.asm.AnnotationVisitor? {
                if (descriptor == "Lkotlin/Metadata;") found = true
                return null
            }
        }, ClassReader.SKIP_CODE or ClassReader.SKIP_FRAMES)
        found
    } catch (t: Throwable) {
        false
    }

    /**
     * Maps a class to its `.kt` source. Primary signal: the class file's own `SourceFile` attribute
     * (Kotlin sets it to the `.kt` file every class of the unit was compiled from — outer classes,
     * interfaces, and facades alike). Fallbacks keep the contract's rules: `com.foo.Bar` ->
     * `<sourceRoot>/com/foo/Bar.kt`, facade `com.foo.XxxKt` -> `Xxx.kt`, inner/anonymous
     * `com.foo.Bar$1` -> their outer's source. No match is not a failure; a match in more than one
     * source root is ambiguous and fails closed (no mapping, recorded reason) rather than picking one
     * at random and risking the wrong file's deletion.
     */
    private fun findSource(className: String, sourceFileAttribute: String?, sourceRoots: List<Path>): SourceLookup {
        if (sourceRoots.isEmpty()) return SourceLookup.NotFound
        val packagePath = className.substringBeforeLast('.', "").replace('.', '/')
        val simpleName = className.substringAfterLast('.')
        val topLevelSimple = simpleName.substringBefore('$')

        val candidates = linkedSetOf<String>()
        if (!sourceFileAttribute.isNullOrBlank()) {
            candidates += "$packagePath/$sourceFileAttribute"
        }
        if (topLevelSimple.endsWith("Kt")) {
            candidates += "$packagePath/${topLevelSimple.removeSuffix("Kt")}.kt"
        }
        candidates += "$packagePath/$topLevelSimple.kt"

        val matched = mutableListOf<Path>()
        for (candidate in candidates) {
            for (root in sourceRoots) {
                val path = root.resolve(candidate)
                if (Files.isRegularFile(path) && path !in matched) matched.add(path)
            }
        }
        return when (matched.size) {
            0 -> SourceLookup.NotFound
            1 -> SourceLookup.Found(matched.first())
            else -> SourceLookup.Ambiguous(matched)
        }
    }

    /**
     * Declarations a `.kt` source contains that the generated Java cannot represent.
     *
     * Limit (stated deliberately): a `typealias` is the only Kotlin declaration kind that produces
     * no member at all in the emitted Java — every other top-level declaration (class, object,
     * interface, function, property, annotation) lands in a bytecode-backed class that the class-file
     * guard already covers. Detecting aliases textually is therefore a sound stopgap, but it is
     * textual: it does not decode the class file's `kotlin.Metadata` `d1`/`d2` strings, so a future
     * classless declaration kind would need this extended. Anything we cannot read is treated as a
     * blocker (fail closed).
     */
    private fun unrepresentedDeclarations(source: Path): List<String> = try {
        val text = Files.readString(source)
        val code = stripCommentsAndStrings(text)
        TYPEALIAS.findAll(code)
            .map { "typealias '${it.groupValues[1]}' has no representation in the generated output" }
            .toList()
    } catch (t: Throwable) {
        listOf("source could not be read to verify its declarations (${t.javaClass.simpleName})")
    }

    /** Blanks out comments and string/char literals so declaration keywords inside them don't match. */
    private fun stripCommentsAndStrings(text: String): String {
        val out = StringBuilder(text.length)
        var i = 0
        val n = text.length
        while (i < n) {
            val c = text[i]
            when {
                c == '/' && i + 1 < n && text[i + 1] == '/' -> {
                    while (i < n && text[i] != '\n') { out.append(' '); i++ }
                }
                c == '/' && i + 1 < n && text[i + 1] == '*' -> {
                    var depth = 0
                    out.append(' ')
                    i++
                    out.append(' ')
                    i++
                    while (i < n) {
                        if (text[i] == '/' && i + 1 < n && text[i + 1] == '*') {
                            depth++; out.append(' '); i++; out.append(' '); i++
                        } else if (text[i] == '*' && i + 1 < n && text[i + 1] == '/') {
                            if (depth == 0) { out.append(' '); i++; out.append(' '); i++; break }
                            depth--; out.append(' '); i++; out.append(' '); i++
                        } else {
                            out.append(if (text[i] == '\n') '\n' else ' '); i++
                        }
                    }
                }
                c == '"' && text.startsWith("\"\"\"", i) -> {
                    out.append("   "); i += 3
                    while (i < n && !text.startsWith("\"\"\"", i)) { out.append(if (text[i] == '\n') '\n' else ' '); i++ }
                    if (i < n) { out.append("   "); i += 3 }
                }
                c == '"' -> {
                    out.append(' '); i++
                    while (i < n && text[i] != '"' && text[i] != '\n') {
                        if (text[i] == '\\' && i + 1 < n) { out.append("  "); i += 2 } else { out.append(' '); i++ }
                    }
                    if (i < n && text[i] == '"') { out.append(' '); i++ }
                }
                c == '\'' -> {
                    out.append(' '); i++
                    while (i < n && text[i] != '\'' && text[i] != '\n') {
                        if (text[i] == '\\' && i + 1 < n) { out.append("  "); i += 2 } else { out.append(' '); i++ }
                    }
                    if (i < n && text[i] == '\'') { out.append(' '); i++ }
                }
                else -> { out.append(c); i++ }
            }
        }
        return out.toString()
    }

    /** Reads the `SourceFile` attribute (e.g. `Bridge.kt`), or null when absent. */
    private fun readSourceFileAttribute(bytes: ByteArray): String? = try {
        var name: String? = null
        ClassReader(bytes).accept(object : org.objectweb.asm.ClassVisitor(org.objectweb.asm.Opcodes.ASM9) {
            override fun visitSource(source: String, debug: String?) {
                name = source
            }
        }, ClassReader.SKIP_CODE or ClassReader.SKIP_FRAMES)
        name
    } catch (t: Throwable) {
        null
    }

    private sealed interface SourceLookup {
        data class Found(val source: Path) : SourceLookup
        data class Ambiguous(val matched: List<Path>) : SourceLookup
        data object NotFound : SourceLookup
    }

    private companion object {
        private val TYPEALIAS = Regex(
            """(?:^|\n)[ \t]*(?:(?:public|private|internal|protected)\s+)?typealias\s+([A-Za-z_][A-Za-z0-9_]*)"""
        )
    }
}
