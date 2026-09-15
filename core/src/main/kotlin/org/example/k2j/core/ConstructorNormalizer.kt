package org.example.k2j.core

import org.example.k2j.core.JavaText.Kind

/**
 * Text-level repair for the one bytecode shape FernFlower reproduces faithfully but javac rejects.
 *
 * Kotlin emits a constructor's parameter null-check *before* the `super()`/`this(...)` delegation
 * (legal in bytecode); Java requires a constructor delegation to be the first statement. FernFlower
 * copies the order, so the emitted constructor does not compile:
 *
 * ```
 * public CancelActivityLike(@NotNull ActivityId id) {
 *    Intrinsics.checkNotNullParameter(id, "id");
 *    super();
 *    this.id = id;
 * }
 * ```
 *
 * [normalize] hoists an explicit `super(...)` / `this(...)` invocation to the first statement of its
 * constructor body, leaving every other statement in its original order. It handles `super()`,
 * `super(args)`, `this(...)`, constructors that already lead with the delegation (no change), and
 * constructors with no explicit delegation at all (Java's implicit `super()` is already correct).
 * Because the search is token-based it also reaches constructors of nested, local and anonymous
 * classes.
 *
 * The transform is deliberately conservative: it only rewrites a statement it recognized as a
 * delegating call by exact token match, and returns the input unchanged if it cannot tokenize or
 * match safely. Such a unit is then left for [JavaValidator] to reject and [K2j] to report as a
 * per-class failure — a class is never silently dropped.
 *
 * Tokenization is shared with [EnumNormalizer] through [JavaText], so both normalizers agree on what
 * an identifier, a literal or a comment is, and neither ever re-prints the text it edits.
 */
object ConstructorNormalizer {

    /** Hoists delegating constructor calls; returns [javaText] unchanged when no safe match is found. */
    fun normalize(javaText: String): String = try {
        hoist(javaText)
    } catch (t: Throwable) {
        javaText
    }

    /** A `super(...);` / `this(...);` statement to move to the front of its enclosing block. */
    private data class Hoist(val statementStart: Int, val statementEnd: Int, val blockOpenEnd: Int)

    private fun hoist(text: String): String {
        val tokens = JavaText.tokenize(text)
        val edits = mutableListOf<Hoist>()
        var i = 0
        while (i < tokens.size) {
            val t = tokens[i]
            if (t.kind == Kind.IDENT && (t.text == "super" || t.text == "this")) {
                val next = tokens.getOrNull(i + 1)
                // `super.foo()` / `this.field` are not delegating calls: they are not followed by `(`.
                if (next != null && next.text == "(") {
                    val close = JavaText.matchingParen(tokens, i + 1)
                    if (close != null && tokens.getOrNull(close + 1)?.text == ";") {
                        val blockOpen = JavaText.enclosingBlockOpen(tokens, i)
                        if (blockOpen != null) {
                            val firstInBlock = JavaText.nextSignificant(tokens, blockOpen + 1)
                            if (firstInBlock != i) {
                                edits += Hoist(
                                    statementStart = t.start,
                                    statementEnd = tokens[close + 1].end,
                                    blockOpenEnd = tokens[blockOpen].end
                                )
                            }
                        }
                    }
                }
            }
            i++
        }
        if (edits.isEmpty()) return text

        var result = text
        // Right-to-left: an edit only touches text at or after its block's opening brace, which is
        // always to the right of any earlier constructor's statement, so offsets stay valid.
        for (edit in edits.sortedByDescending { it.statementStart }) {
            val statement = result.substring(edit.statementStart, edit.statementEnd)
            result = result.removeRange(edit.statementStart, edit.statementEnd)
            result = result.substring(0, edit.blockOpenEnd) + statement + result.substring(edit.blockOpenEnd)
        }
        return result
    }
}
