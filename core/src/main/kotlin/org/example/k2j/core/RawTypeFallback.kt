package org.example.k2j.core

import org.example.k2j.core.JavaText.Kind
import org.example.k2j.core.JavaText.Token

/**
 * The raw-type fallback: the compile gate's **second chance** for the one class of javac rejection
 * where a minimal, *named* degradation provably makes the unit compile.
 *
 * Kotlin's `List<out E>` is covariant; Java's `List<E>` is invariant. A class that satisfies two
 * interfaces in Kotlin can therefore be unrepresentable in Java: `List<ActivityTaskItem>
 * getItems()` is not return-type-substitutable for `List<ItemQuantityEntry> getItems()`, and
 * `Provider<Entry>` cannot be inherited beside `OtherProvider<Item>`. Making the *offending
 * declaration* raw is what Java offers: a raw return type **is** return-type-substitutable for a
 * parameterized one (JLS 8.4.8.3, "convertible to R2 by unchecked conversion"), and raw supertypes
 * make both inherited members erase to the same signature. Both are legal with only an `unchecked`
 * / `rawtypes` warning, which is why this is a *degradation* and not a different translation.
 *
 * Two shapes, both driven by javac's own diagnostic and by nothing else — the fallback never
 * pre-emptively de-genericises anything, and it never guesses which declaration is meant:
 *
 * - **(a) method clash.** `getItems() in X clashes with getItems() in Y`,
 *   `m() in X cannot implement m() in Y` (javac's wording for the same conflict on a class), and
 *   the consequence diagnostic `X is not abstract and does not override abstract method m() in Y`.
 *   Repair: strip the type arguments from the **return type of the method javac named**, in the
 *   unit's own text — `List<ImportItemDto> getItems()` becomes `List getItems()`. Parameters, the
 *   body, every other member, every field and every other generic spelling are untouched.
 * - **(b) incompatible supertypes.** `types A and B are incompatible`. Repair: strip the type
 *   arguments from the named interfaces **in this unit's `implements`/`extends` clause only** —
 *   `implements Provider<Entry>, OtherProvider<Item>` becomes `implements Provider, OtherProvider`.
 *
 * Locating the named declaration is the whole risk, so the rules are deliberately strict, and a
 * unit the fallback cannot place is returned **byte-identical** ([repair] returns null, the caller
 * reports javac's original diagnostic):
 *
 * 1. **Every** diagnostic must be one of the two families above. A single `cannot find symbol`, a
 *    `no suitable constructor found`, an `incompatible types` — anything else — refuses the whole
 *    unit, because a rewrite proven only against the *other* errors is a rewrite proven against
 *    nothing. This is what keeps the fallback from masking a second defect.
 * 2. For (a) the named method must be found, declared, in the unit's **own outer type body** — a
 *    method of a nested type is refused. javac reports the clash at the method's own name token
 *    (`file:line:column` points exactly there), so that position locates it; when the position is
 *    unusable the name is accepted only if it is declared **exactly once** at member level. A name
 *    that occurs more than once and is not disambiguated by javac's position is refused.
 * 3. The located declaration must really carry type arguments on its return type. `AssetBarcodeList
 *    getBarcodes()` has none, so there is nothing to make raw and the unit is refused — a no-op
 *    rewrite would be a silent lie about what was degraded.
 * 4. For (b) a named interface must appear in this unit's own supertype clause **with type
 *    arguments**. `IAccessActuatorVariant` (named by javac as `IEquipmentDeviceVariant`, a
 *    transitive supertype) is not in the clause at all, and an entry spelled without arguments has
 *    nothing to strip: both are refusals, never a wider rewrite of a clause javac did not name.
 * 5. A `types A and B are incompatible` diagnostic that yields no edit is **not** on its own a
 *    reason to refuse the rest: javac reports the same conflict twice (once for the supertypes, once
 *    for the declaration that fixes it, `m() in X clashes with...`), so shape (a) and shape (b) are
 *    frequently the same defect. The rewrite is accepted only if *every* located declaration was
 *    rewritten as named, and — decisively — only if javac then compiles the unit cleanly.
 *
 * [[repair]] does **not** decide whether the unit may be converted. It only proposes; [K2j]'s
 * compile gate recompiles the proposal on the same classpath and accepts it **only** when javac
 * reports no error at all. If the recompile still fails, the unit keeps the diagnostic of the
 * *first* attempt (never the rewritten text's) so nothing is masked, and the authoritative batch
 * sees the original unit unchanged.
 */
object RawTypeFallback {

    /**
     * A rewrite the gate may recompile: the text, plus one note per declaration that was made raw
     * (`"getItems() return type `List<Item>` -> `List`"`), in the order the notes were derived.
     */
    data class Repair(val text: String, val notes: List<String>)

    private val TYPE_KEYWORDS = setOf("class", "interface", "enum", "record")

    /** The `file.java:line:column: ` prefix [JavacCompileChecker] puts in front of javac's message. */
    private val POSITION = Regex("""^(.*?\.java):(\d+):(\d+): (.*)$""", RegexOption.DOT_MATCHES_ALL)

    /** `getItems() in a.b.C clashes with getItems() in d.e.F`. */
    private val CLASH =
        Regex("""([\w$]+)\(([^)]*)\) in ([\w$.]+) clashes with ([\w$]+)\(([^)]*)\) in ([\w$.]+)""")

    /** `getEvents() in a.b.C cannot implement getEvents() in d.e.F`. */
    private val CANNOT_IMPLEMENT =
        Regex("""([\w$]+)\(([^)]*)\) in ([\w$.]+) cannot implement ([\w$]+)\(([^)]*)\) in ([\w$.]+)""")

    /** `a.b.C is not abstract and does not override abstract method getItems() in d.e.F`. */
    private val NOT_ABSTRACT =
        Regex("""^[\w$.]+ is not abstract and does not override abstract method """)

    /** `types a.b.C and d.e.F are incompatible`. */
    private val INCOMPATIBLE = Regex("""^types (.+?) and (.+?) are incompatible""")

    /**
     * The proposed repair, or null when the unit is refused (see the object's rules). Returns null
     * — never a partial or unproven rewrite — for anything it cannot place exactly.
     */
    fun repair(fileName: String, javaText: String, diagnostics: List<String>): Repair? = try {
        attempt(fileName, javaText, diagnostics)
    } catch (t: Throwable) {
        // A transform that cannot even tokenize the text is a refusal, not a failure of the run.
        null
    }

    private fun attempt(fileName: String, javaText: String, diagnostics: List<String>): Repair? {
        if (diagnostics.isEmpty()) return null
        val parsed = diagnostics.map { diagnostic(it) ?: return null }
        // Rule 1: one diagnostic outside the two families refuses the whole unit.
        if (parsed.any { !recognized(it.message) }) return null

        val tokens = JavaText.tokenize(javaText)
        val outerType = outerTypeIndex(tokens) ?: return null
        val outerName = JavaText.nextSignificant(tokens, outerType + 1) ?: return null
        if (tokens[outerName].kind != Kind.IDENT || JavaText.isKeyword(tokens[outerName].text)) return null
        val bodyOpen = bodyOpen(tokens, outerType) ?: return null
        val unit = unitFqn(tokens, tokens[outerName].text)

        val edits = mutableListOf<Edit>()

        // -- (a) method clash ---------------------------------------------------------------------
        for (item in parsed) {
            // Groups: 1 = method name, 2 = its parameter list, 3 = its owner, then 4/5/6 for the
            // method it clashes with. The unit must be one of the two owners, and only then is one
            // of the two names a declaration this unit owns.
            val clash = CLASH.find(item.message) ?: CANNOT_IMPLEMENT.find(item.message) ?: continue
            val methodName = when {
                isUnit(clash.groupValues[3], unit, tokens[outerName].text) -> clash.groupValues[1]
                isUnit(clash.groupValues[6], unit, tokens[outerName].text) -> clash.groupValues[4]
                else -> continue
            }
            val nameIndex = nameIndexFor(tokens, javaText, bodyOpen, methodName, item.line, item.column)
                ?: return null
            edits += rawReturnType(tokens, javaText, nameIndex, methodName) ?: return null
        }

        // -- (b) incompatible supertypes -----------------------------------------------------------
        val named = parsed.mapNotNull { INCOMPATIBLE.find(it.message) }
            .flatMap { listOf(it.groupValues[1], it.groupValues[2]) }
            .map { simpleName(it) }
            .toSet()
        if (named.isNotEmpty()) {
            for (supertype in supertypes(tokens, outerType, bodyOpen)) {
                if (supertype.name !in named) continue
                val argsStart = supertype.argsStart ?: continue
                val argsEnd = supertype.argsEnd ?: continue
                edits += Edit(
                    argsStart,
                    argsEnd,
                    "${supertype.name} made raw in the supertype clause: " +
                        "`${javaText.substring(supertype.nameStart, argsEnd)}` -> `${supertype.name}`"
                )
            }
        }

        if (edits.isEmpty()) return null
        // Two edits can never overlap (a return type and a supertype clause are disjoint ranges), but
        // splicing right to left is what keeps the earlier offsets valid regardless.
        var text = javaText
        for (edit in edits.sortedByDescending { it.start }) {
            text = text.substring(0, edit.start) + text.substring(edit.end)
        }
        if (text == javaText) return null
        return Repair(text, edits.map { it.note })
    }

    // -- diagnostics ------------------------------------------------------------------------------

    private data class Diagnostic(val line: Int, val column: Int, val message: String)

    private fun diagnostic(text: String): Diagnostic? {
        val match = POSITION.find(text) ?: return null
        return Diagnostic(
            match.groupValues[2].toIntOrNull() ?: 0,
            match.groupValues[3].toIntOrNull() ?: 0,
            match.groupValues[4]
        )
    }

    /** True when [message] is one of the two families the fallback owns. */
    private fun recognized(message: String): Boolean {
        val first = message.substringBefore('\n')
        if (first.startsWith("types ") && first.contains(" are incompatible")) return true
        if (CLASH.containsMatchIn(first)) return true
        if (CANNOT_IMPLEMENT.containsMatchIn(first)) return true
        return NOT_ABSTRACT.containsMatchIn(first)
    }

    /** The simple name of a type reference as javac prints it, with any type arguments removed. */
    private fun simpleName(reference: String): String =
        reference.substringBefore('<').trim().substringAfterLast('.')

    /**
     * True when [name] is how javac named the unit under test. javac prints a type either simply
     * (`NarrowContext`, when it is in the unit's own package) or fully qualified
     * (`com.example.app.work.ItemWorkContext`, when it is not), so both spellings of *this*
     * unit's name are accepted and nothing else is.
     */
    private fun isUnit(name: String, unitFqn: String, outerSimpleName: String): Boolean {
        if (name == unitFqn) return true
        if (name == outerSimpleName) return true
        val packagePrefix = unitFqn.substringBeforeLast('.', "")
        return packagePrefix.isNotEmpty() && name == "$packagePrefix.$outerSimpleName"
    }

    // -- the unit's own text ----------------------------------------------------------------------

    private fun unitFqn(tokens: List<Token>, outerSimpleName: String): String {
        for (i in tokens.indices) {
            if (tokens[i].kind != Kind.IDENT || tokens[i].text != "package") continue
            val name = StringBuilder()
            var j = i + 1
            while (j < tokens.size && tokens[j].text != ";") {
                if (tokens[j].kind != Kind.COMMENT) name.append(tokens[j].text)
                j++
            }
            return "$name.$outerSimpleName"
        }
        return outerSimpleName
    }

    /**
     * The index of the `class` / `interface` / `enum` / `record` keyword that declares the unit's
     * **outer** type: at file level (no enclosing block), and not a `Foo.class` literal. A unit's
     * nested types come after it.
     */
    private fun outerTypeIndex(tokens: List<Token>): Int? {
        for (i in tokens.indices) {
            val token = tokens[i]
            if (token.kind != Kind.IDENT || token.text !in TYPE_KEYWORDS) continue
            val previous = JavaText.previousSignificant(tokens, i)
            if (previous != null && tokens[previous].text == ".") continue
            if (JavaText.enclosingBlockOpen(tokens, i) != null) continue
            return i
        }
        return null
    }

    /** The index of the `{` that opens [typeIndex]'s body (paren depth 0, so headers are skipped). */
    private fun bodyOpen(tokens: List<Token>, typeIndex: Int): Int? {
        var paren = 0
        for (i in typeIndex until tokens.size) {
            when (tokens[i].text) {
                "(" -> paren++
                ")" -> paren--
                "{" -> if (paren == 0) return i
            }
        }
        return null
    }

    /** Offset of a 1-based `line`/`column` in [text], or null when the position is outside it. */
    private fun offsetOf(text: String, line: Int, column: Int): Int? {
        if (line < 1 || column < 1) return null
        var offset = 0
        var current = 1
        while (current < line) {
            val newline = text.indexOf('\n', offset)
            if (newline < 0) return null
            offset = newline + 1
            current++
        }
        val target = offset + column - 1
        return if (target < text.length) target else null
    }

    /**
     * The token index of the method *declaration* named [methodName], or null when it cannot be
     * placed unambiguously.
     *
     * javac reports a clash at the method's own name token, so `line:column` is the primary
     * disambiguator; the position is trusted only when it lands exactly on an identifier token with
     * that spelling. Otherwise the name is accepted only if exactly one member-level declaration of
     * it exists in the unit's outer body. A declaration in a nested type is never returned (rule 2).
     */
    private fun nameIndexFor(
        tokens: List<Token>,
        text: String,
        bodyOpen: Int,
        methodName: String,
        line: Int,
        column: Int
    ): Int? {
        val offset = offsetOf(text, line, column)
        if (offset != null) {
            for (i in tokens.indices) {
                if (tokens[i].start != offset) continue
                if (tokens[i].kind == Kind.IDENT && tokens[i].text == methodName &&
                    JavaText.enclosingBlockOpen(tokens, i) == bodyOpen
                ) {
                    return i
                }
                return null
            }
        }
        // The diagnostic carried no usable position: the name must be declared exactly once in the
        // unit — twice is ambiguous whatever the reason — and that one declaration must be the unit's
        // own (member level of the outer body, rule 2).
        val candidates = mutableListOf<Int>()
        for (i in tokens.indices) {
            if (tokens[i].kind != Kind.IDENT || tokens[i].text != methodName) continue
            if (!looksLikeDeclaration(tokens, i)) continue
            candidates += i
        }
        if (candidates.size != 1) return null
        val only = candidates.single()
        return if (JavaText.enclosingBlockOpen(tokens, only) == bodyOpen) only else null
    }

    /**
     * True when the identifier token at [index] is a method declaration's name: followed by a
     * parameter list and then a body, a `;` or a `throws`. A call, a method reference or an argument
     * (`this.getItems()`, `getItems()`, `f(getItems)`) is rejected by what precedes the name, so a
     * name that occurs twice in the unit — declared and called, or declared in a nested type — is
     * counted twice and refused rather than guessed at.
     */
    private fun looksLikeDeclaration(tokens: List<Token>, index: Int): Boolean {
        val open = JavaText.nextSignificant(tokens, index + 1) ?: return false
        if (tokens[open].text != "(") return false
        val close = JavaText.matchingParen(tokens, open) ?: return false
        val after = JavaText.nextSignificant(tokens, close + 1) ?: return false
        if (tokens[after].text !in setOf("{", ";", "throws")) return false
        val previous = JavaText.previousSignificant(tokens, index) ?: return false
        return tokens[previous].text !in setOf(".", "(", ",", "=", ":", "new", "return", "&&", "||", "!")
    }

    /**
     * The edit that strips the type arguments from the return type of the method whose name token is
     * [nameIndex], or null when there are none to strip (or the shape is not a declaration).
     *
     * Only the type's outermost `<...>` is removed: `List<Item>` becomes `List`, while
     * `Map<String, List<Item>>` becomes `Map`, and an annotation, a modifier, an array declarator
     * (`List<Item>[]`) or a qualified prefix (`java.util.List<Item>`) is left where it is.
     */
    private fun rawReturnType(tokens: List<Token>, text: String, nameIndex: Int, methodName: String): Edit? {
        val previous = JavaText.previousSignificant(tokens, nameIndex) ?: return null
        // A constructor has no return type; a call/reference is not a declaration at all.
        if (tokens[previous].text in setOf(".", "(", ",", "=", "{", "}", ";", "new", "return")) return null
        var last = previous
        while (last >= 1 && tokens[last].text == "]" && tokens[last - 1].text == "[") last -= 2
        if (tokens[last].text != ">") return null
        val open = matchingAngle(tokens, last) ?: return null
        if (open == 0) return null
        val name = tokens[open - 1]
        if (name.kind != Kind.IDENT || JavaText.isKeyword(name.text)) return null
        val original = text.substring(name.start, tokens[last].end)
        return Edit(
            tokens[open].start,
            tokens[last].end,
            "$methodName() return type `$original` -> `${original.substringBefore('<')}`"
        )
    }

    // -- the supertype clause ----------------------------------------------------------------------

    /** A supertype as written in the unit's clause: its simple name, and its type arguments' range. */
    private data class Supertype(
        val name: String,
        val nameStart: Int,
        val argsStart: Int?,
        val argsEnd: Int?
    )

    /**
     * Every supertype of the outer type, in the order written: the `extends` list of an interface
     * (or a class's single superclass) and the `implements` list of a class, enum or record. Each
     * list is split at its top-level commas, so a type argument containing a comma stays inside its
     * own entry.
     */
    private fun supertypes(tokens: List<Token>, typeIndex: Int, bodyOpen: Int): List<Supertype> {
        val out = mutableListOf<Supertype>()
        var from = -1
        var angle = 0
        var i = typeIndex
        while (i < bodyOpen) {
            val token = tokens[i]
            if (token.kind != Kind.COMMENT) {
                when (token.text) {
                    "<" -> angle++
                    ">" -> angle--
                    "extends", "implements" -> if (angle == 0) {
                        if (from >= 0) out += entries(tokens, from, i)
                        from = i + 1
                    }
                }
            }
            i++
        }
        if (from >= 0) out += entries(tokens, from, bodyOpen)
        return out
    }

    /** The supertype entries of the token range `[from, to)`, split at depth-0 commas. */
    private fun entries(tokens: List<Token>, from: Int, to: Int): List<Supertype> {
        val out = mutableListOf<Supertype>()
        var angle = 0
        var start = -1
        var i = from
        while (i < to) {
            val token = tokens[i]
            if (token.kind != Kind.COMMENT) {
                if (start < 0) start = i
                when (token.text) {
                    "<" -> angle++
                    ">" -> angle--
                    "," -> if (angle == 0) {
                        entry(tokens, start, i)?.let { out += it }
                        start = -1
                    }
                }
            }
            i++
        }
        if (start >= 0) entry(tokens, start, to)?.let { out += it }
        return out
    }

    /** One supertype from `[from, to)`: its name (the identifier before any `<`) and its arguments. */
    private fun entry(tokens: List<Token>, from: Int, to: Int): Supertype? {
        var last = to - 1
        while (last >= from && tokens[last].kind == Kind.COMMENT) last--
        if (last < from) return null
        if (tokens[last].text == ">") {
            val open = matchingAngle(tokens, last) ?: return null
            if (open <= from) return null
            val nameIndex = open - 1
            val name = tokens[nameIndex]
            if (name.kind != Kind.IDENT || JavaText.isKeyword(name.text)) return null
            return Supertype(name.text, name.start, tokens[open].start, tokens[last].end)
        }
        val name = tokens[last]
        if (name.kind != Kind.IDENT || JavaText.isKeyword(name.text)) return null
        return Supertype(name.text, name.start, null, null)
    }

    /** Index of the `<` matching the `>` at [close], depth-counted, or null when unbalanced. */
    private fun matchingAngle(tokens: List<Token>, close: Int): Int? {
        var depth = 0
        for (i in close downTo 0) {
            when (tokens[i].text) {
                ">" -> depth++
                "<" -> {
                    depth--
                    if (depth == 0) return i
                }
            }
        }
        return null
    }

    /** One deletion: remove the byte range `[start, end)` of the original text, and say why. */
    private data class Edit(val start: Int, val end: Int, val note: String)
}
