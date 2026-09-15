package org.example.k2j.core

/**
 * The lexical layer the text normalizers share.
 *
 * A conservative Java tokenizer that preserves every token's exact source offsets, so a transform
 * can **splice the original text** instead of re-printing it — whitespace, comments and the
 * decompiler's own formatting survive byte-for-byte. That property is what lets [EnumNormalizer]
 * move a multi-line constant list without reformatting a single character of it.
 *
 * It is deliberately dumb: no parsing, no source model, no javac. Anything it cannot tokenize is
 * simply not a match, and the caller leaves the text alone for the parse gate to report.
 */
internal object JavaText {

    enum class Kind { IDENT, PUNCT, LITERAL, COMMENT }

    data class Token(val kind: Kind, val text: String, val start: Int, val end: Int)

    /**
     * Java keywords and literals. A member whose first identifier is one of these can never be an
     * enum constant, which is how a normalizer tells `private final String alias;` and
     * `@NotNull public String getAlias() {` apart from `INVENTORY("inventory", Foo.class);`.
     */
    private val KEYWORDS = setOf(
        "abstract", "assert", "boolean", "break", "byte", "case", "catch", "char", "class",
        "const", "continue", "default", "do", "double", "else", "enum", "extends", "final",
        "finally", "float", "for", "goto", "if", "implements", "import", "instanceof", "int",
        "interface", "long", "native", "new", "package", "private", "protected", "public",
        "return", "short", "static", "strictfp", "super", "switch", "synchronized", "this",
        "throw", "throws", "transient", "try", "void", "volatile", "while",
        "true", "false", "null", "_"
    )

    fun isKeyword(text: String): Boolean = text in KEYWORDS

    /**
     * Splits [text] into identifiers, punctuation, literals and comments. Whitespace produces no
     * token at all — offsets are the only record of it, and that is exactly what a splice needs.
     */
    fun tokenize(text: String): List<Token> {
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

    /** Index of the first token after the token at [index] that is not a comment, or null. */
    fun nextSignificant(tokens: List<Token>, index: Int, limit: Int = tokens.size): Int? {
        var i = index
        while (i < limit) {
            if (tokens[i].kind != Kind.COMMENT) return i
            i++
        }
        return null
    }

    /** Index of the first token before the token at [index] that is not a comment, or null. */
    fun previousSignificant(tokens: List<Token>, index: Int): Int? {
        var i = index - 1
        while (i >= 0) {
            if (tokens[i].kind != Kind.COMMENT) return i
            i--
        }
        return null
    }

    /** Index of the token matching the `(` at [openIndex], or null when unbalanced. */
    fun matchingParen(tokens: List<Token>, openIndex: Int): Int? =
        matching(tokens, openIndex, "(", ")")

    /** Index of the token matching the `{` at [openIndex], or null when unbalanced. */
    fun matchingBrace(tokens: List<Token>, openIndex: Int): Int? =
        matching(tokens, openIndex, "{", "}")

    /**
     * Index of the `{` that opens the block the token at [index] sits in, or null when the token is
     * not inside a block. Depth-counted, so a nested block (an inner class body, a method body) is
     * reported as *that* block, not as the outermost one. Shared by the normalizers that have to
     * relate two members to the same block — [ConstructorNormalizer] (a delegating call's enclosing
     * constructor body) and [WildcardCaptureNormalizer] (a field and a constructor must sit in the
     * same class body before either may be rewritten).
     */
    fun enclosingBlockOpen(tokens: List<Token>, index: Int): Int? {
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

    private fun matching(tokens: List<Token>, openIndex: Int, open: String, close: String): Int? {
        var depth = 0
        for (j in openIndex until tokens.size) {
            when (tokens[j].text) {
                open -> depth++
                close -> {
                    depth--
                    if (depth == 0) return j
                }
            }
        }
        return null
    }
}
