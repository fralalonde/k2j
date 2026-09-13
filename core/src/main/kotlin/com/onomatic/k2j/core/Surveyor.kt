package com.onomatic.k2j.core

import java.nio.file.Path

/**
 * Enumerates the conversion targets and the deletion candidates.
 *
 * A target is a `.class` file that (a) carries `kotlin/Metadata` — Kotlin classes only, Java classes
 * are never touched — and (b) falls inside one of the requested packages. A target is one *unit*:
 * the top-level class plus its `Outer$Nested` / `Outer$1` siblings, all of which FernFlower
 * decompiles into a single generated type (nested/inner/local/anonymous classes are not separate
 * targets). The survey also maps class files back to their source `.kt` files.
 *
 * DELETION INVARIANT (the contract every implementation must uphold):
 *
 *   a source is deletable only when every declaration it contains is represented in the written,
 *   validated output.
 *
 * In practice that means two independent conditions:
 *  1. every Kotlin class file the source produced — outer, nested, inner, local and anonymous —
 *     was decompiled into a unit that was written and validated ([SourceMapping.classFiles]), and
 *  2. the survey found no declaration the generated output cannot represent, and no ambiguity about
 *     which source a class belongs to ([SourceMapping.deletionBlockers]).
 *
 * A source that cannot be fully accounted for must never be deleted: this interface is fail-closed.
 */
interface Surveyor {
    fun survey(request: ConversionRequest): SurveyResult
}

data class SurveyResult(
    val targets: List<ClassTarget>,
    val excluded: List<ExcludedClass>,
    val sources: List<SourceMapping>
)

/**
 * One conversion unit.
 *
 * @param classFile the top-level class file (the unit's identity and output key)
 * @param className the top-level class's binary name (`com.foo.Outer`)
 * @param classFiles every class file of the unit — the top-level file plus its `Outer$*` siblings.
 *   All of them are handed to the decompiler together so the generated unit contains the nested
 *   types; passing only [classFile] drops them from the output (they would be neither converted nor
 *   mapped, yet the source would still be deleted).
 */
data class ClassTarget(
    val classFile: Path,
    val className: String,
    val classFiles: List<Path> = listOf(classFile)
)

data class ExcludedClass(val classFile: Path, val className: String, val reason: String)

/**
 * One `.kt` source and every class file it produced.
 *
 * @param classFiles every Kotlin class file of the source inside the requested packages — including
 *   nested/inner/local/anonymous ones. If any of these has no written output the source is not
 *   deletable, so a class that produced zero outputs can never be deleted silently.
 * @param deletionBlockers survey-side reasons the source must never be deleted even when every
 *   class file converted: declarations the generated output cannot represent (a `typealias` has no
 *   member in the emitted Java) and ambiguous source mappings (more than one source root matched, so
 *   the class cannot be attributed safely).
 */
data class SourceMapping(
    val source: Path,
    val classFiles: List<Path>,
    val deletionBlockers: List<String> = emptyList()
)
