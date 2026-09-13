package com.onomatic.k2j.core

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
 */
object ConstructorNormalizer {

    /** Hoists delegating constructor calls; returns [javaText] unchanged when no safe match is found. */
    fun normalize(javaText: String): String = try {
        hoist(javaText)
    } catch (t: Throwable) {
        javaText
    }

    private enum class Kind { IDENT, PUNCT, LITERAL, COMMENT }

    private data class Token(val kind: Kind, val text: String, val start: Int, val end: Int)

    /** A `super(...);` / `this(...);` statement to move to the front of its enclosing block. */
    private data class Hoist(val statementStart: Int, val statementEnd: Int, val blockOpenEnd: Int)

    private fun hoist(text: String): String {
        val tokens = tokenize(text)
        val edits = mutableListOf<Hoist>()
        var i = 0
        while (i < tokens.size) {
            val t = tokens[i]
            if (t.kind == Kind.IDENT && (t.text == "super" || t.text == "this")) {
                val next = tokens.getOrNull(i + 1)
                // `super.foo()` / `this.field` are not delegating calls: they are not followed by `(`.
                if (next != null && next.text == "(") {
                    val close = matchingParen(tokens, i + 1)
                    if (close != null && tokens.getOrNull(close + 1)?.text == ";") {
                        val blockOpen = enclosingBlockOpen(tokens, i)
                        if (blockOpen != null) {
                            val firstInBlock = firstSignificantAfter(tokens, blockOpen)
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

    private fun matchingParen(tokens: List<Token>, openIndex: Int): Int? {
        var depth = 0
        for (j in openIndex until tokens.size) {
            when (tokens[j].text) {
                "(" -> depth++
                ")" -> {
                    depth--
                    if (depth == 0) return j
                }
            }
        }
        return null
    }

    /** Index of the `{` that opens the block the token at [index] sits in. */
    private fun enclosingBlockOpen(tokens: List<Token>, index: Int): Int? {
        var depth = 0
        for (k in index - 1 downTo 0) {
            when (tokens[k].text) {
                "}" -> depth++
                "{" -> {
                    if (depth == 0) return k
                    depth--
                }
            }
        }
        return null
    }

    /** First non-comment token after the block-opening token at [blockOpen]. */
    private fun firstSignificantAfter(tokens: List<Token>, blockOpen: Int): Int? {
        var f = blockOpen + 1
        while (f < tokens.size && tokens[f].kind == Kind.COMMENT) f++
        return if (f < tokens.size) f else null
    }

    private fun tokenize(text: String): List<Token> {
        val tokens = mutableListOf<Token>()
        var i = 0
        val n = text.length
        while (i < n) {
            val c = text[i]
            when {
                c.isWhitespace() -> i++
                c == '/' && i + 1 < n && text[i + 1] == '/' -> {
                    val start = i
                    while (i < n && text[i] != '\n') i++
                    tokens += Token(Kind.COMMENT, text.substring(start, i), start, i)
                }
                c == '/' && i + 1 < n && text[i + 1] == '*' -> {
                    val start = i
                    i += 2
                    while (i < n && !(text[i] == '*' && i + 1 < n && text[i + 1] == '/')) i++
                    i = if (i < n) i + 2 else n
                    tokens += Token(Kind.COMMENT, text.substring(start, i), start, i)
                }
                c == '"' && text.startsWith("\"\"\"", i) -> {
                    val start = i
                    i += 3
                    while (i < n && !text.startsWith("\"\"\"", i)) i++
                    i = if (i < n) i + 3 else n
                    tokens += Token(Kind.LITERAL, text.substring(start, i), start, i)
                }
                c == '"' -> {
                    val start = i
                    i++
                    while (i < n && text[i] != '"') {
                        if (text[i] == '\\' && i + 1 < n) i += 2 else i++
                    }
                    i = if (i < n) i + 1 else n
                    tokens += Token(Kind.LITERAL, text.substring(start, i), start, i)
                }
                c == '\'' -> {
                    val start = i
                    i++
                    while (i < n && text[i] != '\'') {
                        if (text[i] == '\\' && i + 1 < n) i += 2 else i++
                    }
                    i = if (i < n) i + 1 else n
                    tokens += Token(Kind.LITERAL, text.substring(start, i), start, i)
                }
                Character.isJavaIdentifierStart(c) -> {
                    val start = i
                    while (i < n && Character.isJavaIdentifierPart(text[i])) i++
                    tokens += Token(Kind.IDENT, text.substring(start, i), start, i)
                }
                Character.isDigit(c) -> {
                    val start = i
                    while (i < n && (Character.isLetterOrDigit(text[i]) || text[i] == '.' || text[i] == '_')) i++
                    tokens += Token(Kind.PUNCT, text.substring(start, i), start, i)
                }
                else -> {
                    tokens += Token(Kind.PUNCT, c.toString(), i, i + 1)
                    i++
                }
            }
        }
        return tokens
    }
}
