package org.example.k2j.core

import org.example.k2j.core.JavaText.Kind
import org.example.k2j.core.JavaText.Token

/**
 * Text-level repair for a Kotlin declaration whose *name* is a Java **reserved word**.
 *
 * Kotlin accepts as identifiers several words Java reserves — `default`, `assert`, `goto`,
 * `strictfp` — so a Kotlin property, constructor parameter or function may legally be called
 * `default`, and the class file carries that name verbatim: the JVM has no reserved words. FernFlower
 * reproduces the class file faithfully, so the generated Java does not *parse*:
 *
 * ```
 * private final Boolean default;                                     // <identifier> expected
 * public BooleanValue(@Nullable Boolean actual, @Nullable Boolean default) {
 *    this.default = default;                                        // not a statement, <identifier> expected
 * }
 * ```
 *
 * javac: `<identifier> expected`, `not a statement`, `: or -> expected`, `illegal start of
 * expression`. The unit never reaches the compile gate — [JavacValidator] rejects it and [K2j]
 * reports it as a per-class failure with phase `VALIDATE`. Measured over the `target module` module, 12
 * units carry exactly this shape — every `*Value` property class in
 * `com.example.app.properties` (`EnumValue`, `BooleanValue`, `StringValue`, `NumberValue`,
 * `LongValue`, `DecimalValue`, `DurationValue`, `DateTimeValue`, `TimeValue`, `CronValue`,
 * `DataMaskValue`, `AbstractPropertyValue`) names its second component `default` — and 13 further
 * units fail to compile only because they call one of them, so a single leaked keyword holds a whole
 * cluster of the module hostage.
 *
 * [normalize] renames the identifier — `<word>_`, or `<word>__`/`<word>___` when that is taken — and
 * splices it everywhere the unit uses it, so every other byte of the unit survives unchanged.
 *
 * Deliberately conservative. The rule, in full — **all** of these must hold or the unit is returned
 * unchanged, byte for byte:
 *
 * 1. an occurrence is an identifier, not a `default:` label in a `switch` (that occurrence is left
 *    alone rather than refused: a label is a legal Java construct and renaming it would break the
 *    unit). A `default` that a *ternary* leaves in front of a `:` is read as a label and left
 *    alone — the unit then still fails the parse gate, which is the safe direction;
 * 2. the unit must **declare** the word (a field, a method, a local or a parameter). A unit that only
 *    *uses* `<expr>.default` proves nothing about who declared it — the declaration may live in a
 *    unit this pass cannot see — so it is refused;
 * 3. no **API-visible member** may carry the word: a `public`/`protected` field or method named
 *    `default` is reachable from Java callers as `obj.default`, which Java cannot express. Renaming
 *    it would *change* the generated API instead of repairing it, so the unit is refused. (A
 *    parameter's name is not part of any API: it may always be renamed);
 * 4. no occurrence may sit in a **type position** (`class`, `new`, `extends`, ...): a type named
 *    `default` is not something a rename can repair.
 *
 * Any occurrence none of those rules claims — a `switch` label, a ternary branch, an unbalanced or
 * truncated unit — leaves the offending `default` in place, so the parse gate keeps rejecting the
 * unit and [K2j] keeps reporting it. That is strictly better than an identifier invented from an
 * unproven shape.
 *
 * Tokenization is shared with the other normalizers through [JavaText], so this pass agrees with them
 * on what an identifier, a literal or a comment is, and never re-prints the text it edits. It writes
 * no message string: a string literal such as `"BooleanValue(actual=" + actual + ", default=" + ...)`
 * is *text*, not a name, and stays exactly as the decompiler emitted it.
 */
object ReservedWordNormalizer {

    /**
     * The Java reserved words Kotlin accepts as identifiers, so a Kotlin declaration can legally
     * carry one and leak it into the generated Java. `default` is the one the `target module` census
     * produced (12 units); the others are the same leak through a different word, and the rules below
     * treat them identically.
     */
    private val RESERVED = setOf("default", "assert", "goto", "strictfp")

    /** The last token of a *type* — what the name in a declaration follows. */
    private val TYPE_ENDS = setOf(
        ">", "]", "boolean", "byte", "char", "short", "int", "long", "float", "double"
    )

    /**
     * What the name in a declaration may be followed by: `;`/`,`/`)` for a field, a parameter or a
     * local, `(` for a method, `=` for a field or a local with an initializer.
     */
    private val DECLARATION_TAILS = setOf(";", ",", ")", "(", "=")

    /**
     * Where a declaration's own tokens end, walking back: a statement boundary, or the delimiters of
     * the list the name sits in. A parameter list is entered from `(` or `,`, which is precisely why
     * a *parameter* can never be attributed a `public` modifier belonging to its method — the walk
     * stops at the parameter list's own delimiter.
     */
    private val DECLARATION_HEADS = setOf(";", "{", "}", "(", ",", ")", "=", ":", "return")

    /** Modifiers that make a member part of the Java-visible API. */
    private val VISIBLE = setOf("public", "protected")

    /** A type position: the word names a type there, and no rename can repair that. */
    private val TYPE_POSITIONS = setOf(
        "class", "interface", "enum", "new", "import", "package", "extends", "implements",
        "instanceof", "throws", "catch"
    )

    /** Names tried, in order; the first the unit does not already use wins. */
    private val SUFFIXES = listOf("_", "__", "___")

    /** Renames the leaked reserved-word identifier; returns [javaText] unchanged when no rule applies. */
    fun normalize(javaText: String): String = try {
        repair(javaText)
    } catch (t: Throwable) {
        javaText
    }

    /** One splice: the character range to replace and what replaces it. */
    private data class Edit(val start: Int, val end: Int, val replacement: String)

    private fun repair(text: String): String {
        // Cheap reject: the tokenizer only runs on a unit that carries one of the words at all.
        if (RESERVED.none { text.contains(it) }) return text
        val tokens = JavaText.tokenize(text)
        val used = usedIdentifiers(tokens)
        val edits = mutableListOf<Edit>()

        for (word in RESERVED) {
            val occurrences = tokens.indices.filter { tokens[it].kind == Kind.IDENT && tokens[it].text == word }
            if (occurrences.isEmpty()) continue
            // Rule 1: a `default:` label is legal Java and must not be renamed. Renaming its
            // occurrences is what this pass does; a label occurrence is skipped, and a word that is
            // *only* ever a label is not this pass's business at all.
            val identifiers = occurrences.filter { !isLabel(tokens, it) }
            if (identifiers.isEmpty()) continue
            // Rule 4: a type position is not a name.
            if (identifiers.any { isTypePosition(tokens, it) }) return text
            // Rule 2: the unit must declare the word.
            if (identifiers.none { isDeclaration(tokens, it) }) return text
            // Rule 3: a visible member's name is API, not a local detail.
            if (identifiers.any { isVisibleMember(tokens, it) }) return text
            val name = SUFFIXES.map { word + it }.firstOrNull { it !in used } ?: return text
            for (occurrence in identifiers) {
                edits += Edit(tokens[occurrence].start, tokens[occurrence].end, name)
            }
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

    // -- classifying one occurrence ----------------------------------------------------------------

    /** True when the token at [index] is a Java label (`default:`), not an identifier. */
    private fun isLabel(tokens: List<Token>, index: Int): Boolean {
        val next = JavaText.nextSignificant(tokens, index + 1) ?: return false
        return tokens[next].text == ":"
    }

    /** True when the token at [index] sits where a type must go. */
    private fun isTypePosition(tokens: List<Token>, index: Int): Boolean {
        val before = JavaText.previousSignificant(tokens, index) ?: return false
        return tokens[before].text in TYPE_POSITIONS
    }

    /**
     * True when the token at [index] is the *name* of a declaration: the last token of a type to its
     * left, and `;`, `,`, `)`, `(` or `=` to its right. A cast (`(T)default`) is not a declaration —
     * `)` is not a type end — and neither is a call argument, whose leader is `,` or `(`.
     */
    private fun isDeclaration(tokens: List<Token>, index: Int): Boolean {
        val before = JavaText.previousSignificant(tokens, index) ?: return false
        val after = JavaText.nextSignificant(tokens, index + 1) ?: return false
        val typeEnd = tokens[before]
        val namedType = typeEnd.kind == Kind.IDENT &&
            !JavaText.isKeyword(typeEnd.text) &&
            typeEnd.text !in RESERVED
        if (!namedType && typeEnd.text !in TYPE_ENDS) return false
        return tokens[after].text in DECLARATION_TAILS
    }

    /**
     * True when the token at [index] is the name of a `public`/`protected` member rather than a
     * parameter or a local. The walk back stops at the delimiters of the list the name sits in, so a
     * parameter of a `public` method never inherits its method's visibility.
     */
    private fun isVisibleMember(tokens: List<Token>, index: Int): Boolean {
        var k = index - 1
        while (k >= 0) {
            val token = tokens[k]
            if (token.kind == Kind.COMMENT) {
                k--
                continue
            }
            if (token.text in DECLARATION_HEADS) return false
            if (token.text in VISIBLE) return true
            k--
        }
        return false
    }

    /** Every identifier the unit uses, so a name that is already taken is never chosen. */
    private fun usedIdentifiers(tokens: List<Token>): Set<String> {
        val used = HashSet<String>()
        for (token in tokens) {
            if (token.kind == Kind.IDENT) used += token.text
        }
        return used
    }
}
