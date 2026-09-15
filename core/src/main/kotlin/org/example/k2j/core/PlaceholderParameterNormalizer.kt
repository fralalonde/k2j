package org.example.k2j.core

import org.example.k2j.core.JavaText.Kind
import org.example.k2j.core.JavaText.Token

/**
 * Text-level repair for the property-setter parameter whose name the class file never carried.
 *
 * Kotlin's compiler does not write the name of a `var` property's setter parameter into the bytecode
 * it emits: the setter's `LocalVariableTable` holds the literal `<set-?>` instead, and the string
 * constant the compiler hands to `Intrinsics.checkNotNullParameter(<param>, ...)` is `<set-?>` as
 * well. FernFlower reproduces the class file faithfully, so the placeholder is leaked into the
 * generated Java:
 *
 * ```
 * public final void setValues(@NotNull List<Single> <set-?>) {
 *    Intrinsics.checkNotNullParameter(<set-?>, "<set-?>");
 *    this.values = <set-?>;
 * }
 * ```
 *
 * javac: `<identifier> expected`, `> expected`, `> or ',' expected`. The unit does not *parse*, so
 * [JavacValidator] rejects it and [K2j] reports it as a per-class failure. Measured over the 670
 * failed units of the `target module` module, 223 units carry this one shape, in 3276 occurrences:
 * 1616 in a parameter list (223 of them a bodyless `;` interface declaration), 1609 assignments to
 * `this.<field>`, 51 `Intrinsics` arguments and 51 message string literals.
 *
 * [normalize] gives that one parameter a valid Java name and rewrites it everywhere that parameter is
 * used — the declaration, every reference in the method body, and the `Intrinsics` message string,
 * which is the parameter's *name* and must not be left holding the placeholder. The name is `value`,
 * or `value1`, `value2`, ... when `value` is already taken by anything else in the unit. The
 * replacement is a splice of the placeholder's own character range, so every other byte of the unit —
 * spacing, comments, the other parameters, the rest of the body — survives unchanged.
 *
 * Deliberately conservative. The rule, in full — **all** of these must hold or the unit is returned
 * unchanged:
 *
 * 1. the placeholder is the five tokens `<`, `set`, `-`, `?`, `>` in exactly that order, and is not
 *    immediately followed by another `>` (replacing only the five matched tokens would leave that
 *    `>` behind and invent different invalid Java);
 * 2. a **declaration** occurrence sits at the top level of a parameter list, preceded by the last
 *    token of a type (an identifier, `>` or `]`) and followed by `,` or `)`, and the list it sits in
 *    belongs to a *declaration*: its `(` follows an identifier no `.` precedes (so it is not a call)
 *    and its `)` is followed by `{`, `;` or `throws`;
 * 3. at most **one** placeholder per parameter list — two parameters may not share a name, so two
 *    placeholders in one list is not a shape this pass can name;
 * 4. a **reference** occurrence follows a token only an expression can start in (`=`, `(`, `,`,
 *    `return`, ...) and lies inside the body of **exactly one** declaration that declared a
 *    placeholder parameter. An occurrence preceded by a type token is a *declaration* of a local
 *    named `<set-?>` and is refused: a type position is not proof of a parameter usage;
 * 5. a **string literal** `"<set-?>"` (or `"<set-?>>"`) is an argument of a call — preceded by `(`
 *    or `,` — inside the body of exactly one such declaration. Any other literal is refused.
 *
 * Any occurrence none of those rules claims — a call argument of a method that declares no
 * placeholder, a placeholder outside every method body, an unbalanced or truncated unit — makes
 * [normalize] return its input **unchanged** (byte for byte). Such a unit is then left for
 * [JavacValidator] to reject and [K2j] to report as a per-class failure, which is strictly better
 * than an identifier invented from an unproven shape.
 *
 * Tokenization is shared with [ConstructorNormalizer], [EnumNormalizer] and
 * [WildcardCaptureNormalizer] through [JavaText], so all four agree on what an identifier, a literal
 * or a comment is, and none of them ever re-prints the text it edits.
 */
object PlaceholderParameterNormalizer {

    /** The five tokens FernFlower leaks in place of the setter parameter's name. */
    private val PLACEHOLDER = listOf("<", "set", "-", "?", ">")

    /**
     * The type token a parameter's *name* follows: the last token of a type is an identifier (a
     * simple or qualified name), a `>` (a parameterized type), a `]` (an array type) or one of the
     * primitives — `setCount(int <set-?>)` is as much a parameter declaration as
     * `setName(String <set-?>)`, and `int` is a Java keyword, not an identifier.
     */
    private val TYPE_ENDS = setOf(
        ">", "]", "boolean", "byte", "char", "short", "int", "long", "float", "double"
    )

    /** What a declaration's `)` may be followed by. Anything else is a call, not a declaration. */
    private val DECLARATION_TAILS = setOf("{", ";", "throws")

    /**
     * What a *reference* to the parameter may directly follow — positions only an expression can
     * start in. Every token that can end a **type** (an identifier, `>`, `]`) is deliberately
     * absent: a placeholder in a type position declares a local variable named `<set-?>`, and that is
     * not the parameter this pass may rename.
     */
    private val REFERENCE_LEADERS = setOf(
        "=", "(", ",", "return", "[", "?", ":", "!", "+", "-", "*", "/", "%", "&", "|", "^",
        "==", "!=", "&&", "||", "instanceof", ".", "{", ";"
    )

    /** The string literals FernFlower uses as the parameter's name in `Intrinsics` calls. */
    private val MESSAGES = setOf("\"<set-?>\"", "\"<set-?>>\"")

    /** The preferred name; the first one the unit does not already use wins. */
    private const val PREFERRED = "value"

    /** How many `value1`, `value2`, ... names to try before giving up on a unit. */
    private const val NAME_LIMIT = 1000

    /** Renames the leaked setter parameter; returns [javaText] unchanged when no rule applies. */
    fun normalize(javaText: String): String = try {
        repair(javaText)
    } catch (t: Throwable) {
        javaText
    }

    /** A declaration whose parameter list holds the placeholder, with the span it owns. */
    private data class Declaration(
        val listOpen: Int,
        val listClose: Int,
        val bodyOpen: Int?,
        val bodyClose: Int?
    )

    /** One splice: the character range to replace and what replaces it. */
    private data class Edit(val start: Int, val end: Int, val replacement: String)

    private fun repair(text: String): String {
        if (!text.contains("<set-?>")) return text
        val tokens = JavaText.tokenize(text)

        val runs = placeholderRuns(tokens)
        if (runs.isEmpty()) return text
        // A placeholder followed immediately by another `>` is `"<set-?>>"`-style text or a shape
        // whose last `>` belongs to something else; replacing only the five matched tokens would
        // leave that `>` behind and invent different invalid Java. Refuse the whole unit.
        if (runs.any { run ->
                run + PLACEHOLDER.size < tokens.size &&
                    tokens[run + PLACEHOLDER.size].text == ">" &&
                    tokens[run + PLACEHOLDER.size].start == tokens[run + PLACEHOLDER.size - 1].end
            }
        ) {
            return text
        }

        val declarations = mutableListOf<Declaration>()
        val references = mutableListOf<Int>()
        for (run in runs) {
            val declaration = declaration(tokens, run)
            if (declaration == null) references += run else declarations += declaration
        }
        // One placeholder per parameter list: two declarations claiming the same list cannot be
        // mapped back onto the parameters they came from.
        if (declarations.map { it.listOpen }.toSet().size != declarations.size) return text

        val used = usedIdentifiers(tokens)
        val names = HashMap<Int, String>()
        val edits = mutableListOf<Edit>()

        for (declaration in declarations) {
            val run = runs.singleOrNull { it > declaration.listOpen && it < declaration.listClose } ?: return text
            val name = chooseName(used) ?: return text
            names[declaration.listOpen] = name
            edits += Edit(tokens[run].start, tokens[run + PLACEHOLDER.size - 1].end, name)
        }

        for (run in references) {
            val before = JavaText.previousSignificant(tokens, run) ?: return text
            if (tokens[before].text !in REFERENCE_LEADERS) return text
            val owner = owner(tokens, declarations, run) ?: return text
            val name = names[owner.listOpen] ?: return text
            edits += Edit(tokens[run].start, tokens[run + PLACEHOLDER.size - 1].end, name)
        }

        for (i in tokens.indices) {
            val token = tokens[i]
            if (token.kind != Kind.LITERAL || token.text !in MESSAGES) continue
            val before = JavaText.previousSignificant(tokens, i) ?: return text
            if (tokens[before].text != "(" && tokens[before].text != ",") return text
            val owner = owner(tokens, declarations, i) ?: return text
            val name = names[owner.listOpen] ?: return text
            edits += Edit(token.start, token.end, "\"$name\"")
        }

        if (edits.isEmpty()) return text
        // Every edit covers a disjoint range of the original text, so splicing right-to-left keeps
        // the offsets of the edits still to be applied valid.
        var result = text
        for (edit in edits.sortedByDescending { it.start }) {
            result = result.substring(0, edit.start) + edit.replacement + result.substring(edit.end)
        }
        return result
    }

    // -- finding the placeholder ------------------------------------------------------------------

    /** The token index of every `<`, `set`, `-`, `?`, `>` run in the unit. */
    private fun placeholderRuns(tokens: List<Token>): List<Int> {
        val runs = mutableListOf<Int>()
        var i = 0
        while (i + PLACEHOLDER.size <= tokens.size) {
            if (matches(tokens, i)) {
                runs += i
                i += PLACEHOLDER.size
            } else {
                i++
            }
        }
        return runs
    }

    /** True when the five tokens at [index] spell the placeholder. */
    private fun matches(tokens: List<Token>, index: Int): Boolean {
        for (k in PLACEHOLDER.indices) {
            if (tokens[index + k].text != PLACEHOLDER[k]) return false
        }
        // `set` must be the identifier the placeholder names, not part of a longer one.
        return tokens[index + 1].kind == Kind.IDENT
    }

    // -- the declaration --------------------------------------------------------------------------

    /**
     * The declaration the placeholder at [run] is the *name* of, or null when that position is not a
     * parameter name — a call argument, a type position, a nested expression, anything unproven.
     */
    private fun declaration(tokens: List<Token>, run: Int): Declaration? {
        val open = parameterListOpen(tokens, run) ?: return null
        val close = JavaText.matchingParen(tokens, open) ?: return null
        val name = JavaText.previousSignificant(tokens, open) ?: return null
        if (tokens[name].kind != Kind.IDENT || JavaText.isKeyword(tokens[name].text)) return null
        // `x.set(...)` is a call: its `(` follows a `.`. A placeholder in such a list proves nothing
        // about a declaration, so the unit is left alone rather than guessed at.
        val beforeName = JavaText.previousSignificant(tokens, name)
        if (beforeName != null && tokens[beforeName].text == ".") return null
        val tail = JavaText.nextSignificant(tokens, close + 1) ?: return null
        if (tokens[tail].text !in DECLARATION_TAILS) return null
        val body = bodyOpen(tokens, close)
        return Declaration(open, close, body, body?.let { JavaText.matchingBrace(tokens, it) })
    }

    /**
     * The `(` of the parameter list the placeholder at [run] names a parameter in, or null. The
     * placeholder must be at the **top level** of that list (so an annotation argument or a nested
     * call never matches), with the last token of a parameter's type to its left and `,` or `)` to
     * its right — the only position a parameter's name can occupy.
     */
    private fun parameterListOpen(tokens: List<Token>, run: Int): Int? {
        val before = JavaText.previousSignificant(tokens, run) ?: return null
        val after = JavaText.nextSignificant(tokens, run + PLACEHOLDER.size) ?: return null
        val typeEnd = tokens[before]
        val namedType = typeEnd.kind == Kind.IDENT && !JavaText.isKeyword(typeEnd.text)
        if (!namedType && typeEnd.text !in TYPE_ENDS) return null
        if (tokens[after].text != "," && tokens[after].text != ")") return null

        var depth = 0
        var open = -1
        var i = run - 1
        while (i >= 0) {
            when (tokens[i].text) {
                ")" -> depth++
                "(" -> {
                    if (depth == 0) {
                        open = i
                        break
                    }
                    depth--
                }
            }
            i--
        }
        if (open < 0) return null
        // Top level of the list: everything between its `(` and the placeholder is balanced.
        var nested = 0
        for (j in (open + 1) until run) {
            when (tokens[j].text) {
                "(", "[", "{" -> nested++
                ")", "]", "}" -> {
                    nested--
                    if (nested < 0) return null
                }
            }
        }
        return if (nested == 0) open else null
    }

    /** The `{` that opens the body of the declaration closed at [theClose], or null when it has none. */
    private fun bodyOpen(tokens: List<Token>, theClose: Int): Int? {
        var depth = 0
        var i = theClose + 1
        while (i < tokens.size) {
            when (tokens[i].text) {
                "(" -> depth++
                ")" -> depth--
                "{" -> if (depth == 0) return i
                ";" -> if (depth == 0) return null
            }
            i++
        }
        return null
    }

    // -- the references ---------------------------------------------------------------------------

    /**
     * The one declaration whose body contains the token at [index], or null when none does — or when
     * more than one does, which a nested declaration of the same shape produces: an attribution this
     * pass cannot prove.
     */
    private fun owner(tokens: List<Token>, declarations: List<Declaration>, index: Int): Declaration? {
        val owners = declarations.filter { declaration ->
            val open = declaration.bodyOpen
            val close = declaration.bodyClose
            open != null && close != null && index > open && index < close
        }
        return owners.singleOrNull()
    }

    // -- the name ---------------------------------------------------------------------------------

    /** Every identifier the unit uses, so a name that is already taken is never chosen. */
    private fun usedIdentifiers(tokens: List<Token>): Set<String> {
        val used = HashSet<String>()
        for (token in tokens) {
            if (token.kind == Kind.IDENT) used += token.text
        }
        return used
    }

    /** `value`, then `value1`, `value2`, ... — the first name the unit does not already use. */
    private fun chooseName(used: Set<String>): String? {
        if (PREFERRED !in used) return PREFERRED
        var i = 1
        while (i <= NAME_LIMIT) {
            val candidate = "$PREFERRED$i"
            if (candidate !in used) return candidate
            i++
        }
        return null
    }
}
