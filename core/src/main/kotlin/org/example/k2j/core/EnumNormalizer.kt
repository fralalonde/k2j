package org.example.k2j.core

import org.example.k2j.core.JavaText.Kind
import org.example.k2j.core.JavaText.Token

/**
 * Text-level repair for the shape FernFlower emits for **every** Kotlin enum.
 *
 * Kotlin compiles an enum with constructor parameters into an ordinary class body in which the
 * instance fields are declared *before* the enum constants. FernFlower reproduces that member order
 * faithfully, and `javac` rejects it — a Java enum body must open with its constant list:
 *
 * ```
 * public enum CountActivityType implements WorkflowActivityType {
 *    @NotNull
 *    private final String alias;                        // field emitted first ...
 *    INVENTORY("inventory", CountInventoryProperties.class);   // ... constants after it
 * ```
 *
 * The second, coupled defect is the synthetic `$VALUES` array: the class file declares
 * `private static final CountActivityType[] $VALUES`, but FernFlower emits the *accessor*
 * `private static final CountActivityType[] $values()` and never the field, while still referencing
 * the field in `$ENTRIES = EnumEntriesKt.enumEntries($VALUES)`. Java parses the dangling reference
 * (so the parse gate cannot see it) and then fails to compile — which is why [normalize] rewrites
 * the reference to the accessor that really exists rather than inventing a `$VALUES` field.
 *
 * [normalize] therefore does two things to a generated unit, in this order:
 *
 * 1. **Lift the constant list** of every enum body in the unit (nested enums included) to the top of
 *    that body, immediately after its opening brace. The constants travel as one block — their
 *    text, order, trailing `;` and multi-line formatting are copied verbatim, so a list of several
 *    constants separated by commas stays a list. Everything else in the body keeps its relative
 *    order. A body whose constants are already first is not touched at all.
 * 2. **Repair the dangling `$VALUES` reference**: when the unit references `$VALUES` without
 *    declaring a `$VALUES` field, and the synthetic `$values()` accessor is present, each reference
 *    becomes `$values()`. `getEntries()` and `$values()` themselves are never removed, and no
 *    `$VALUES` field is ever invented.
 *
 * The transform is deliberately conservative and all-or-nothing per unit: it only splices a
 * constant list it recognized by exact token shape at a member boundary, and it returns the input
 * **unchanged** if any enum body in the unit does not match a shape it can prove (unbalanced
 * braces, a constant list that does not end in `;`, a declaration it cannot tokenize). Such a unit
 * is then left for [JavaValidator] to reject and [K2j] to report as a per-class failure — a unit is
 * never partially rewritten and a class is never silently dropped.
 */
object EnumNormalizer {

    /** Synthetic enum field FernFlower references but does not emit. */
    private const val VALUES_FIELD = "\$VALUES"

    /** Synthetic enum accessor FernFlower emits in its place. */
    private const val VALUES_ACCESSOR = "\$values()"

    /** Repairs enum member order and the `$VALUES` reference; the input when it cannot prove safety. */
    fun normalize(javaText: String): String = try {
        repair(javaText) ?: javaText
    } catch (t: Throwable) {
        javaText
    }

    /**
     * A constant list to lift. Offsets point into the *original* text:
     * `[braceEnd, blockStart)` is what sits between the enum's `{` and the first constant,
     * `[blockStart, blockEnd)` is the constant list itself, and [consume] is the length of the line
     * terminator that follows `blockEnd` (removed with the block so the vacated line disappears).
     */
    private data class Move(
        val braceEnd: Int,
        val blockStart: Int,
        val blockEnd: Int,
        val consume: Int,
        val newline: String
    )

    /** One parsed enum constant: the index after its terminator, and which terminator it was. */
    private data class Constant(val end: Int, val separator: String)

    private fun repair(text: String): String? {
        val tokens = JavaText.tokenize(text)
        val bodies = enumBodyOpens(tokens) ?: return null
        val moves = mutableListOf<Move>()
        for (open in bodies) {
            val close = JavaText.matchingBrace(tokens, open) ?: return null
            val (start, end) = constantGroup(tokens, open, close) ?: return null
            if (isAtTop(tokens, open, start)) continue
            moves += moveFor(text, tokens, open, start, end) ?: return null
        }
        val lifted = applyMoves(text, moves) ?: return null
        return repairValues(lifted)
    }

    // -- (a) the constant list ----------------------------------------------------------------

    /** Token index of every `enum` body opening brace in the unit, or null if one is unrecognizable. */
    private fun enumBodyOpens(tokens: List<Token>): List<Int>? {
        val opens = mutableListOf<Int>()
        var i = 0
        while (i < tokens.size) {
            val t = tokens[i]
            if (t.kind == Kind.IDENT && t.text == "enum") {
                var j = i + 1
                while (j < tokens.size && tokens[j].text != "{") {
                    // `enum Foo implements Bar<Baz> {` — a `;` or the end of an enclosing body before
                    // the brace means this `enum` token is not a declaration we understand.
                    if (tokens[j].text == ";" || tokens[j].text == "}") return null
                    j++
                }
                if (j >= tokens.size) return null
                opens += j
                i = j
            }
            i++
        }
        return opens
    }

    /**
     * The token range of the constant list of the body `[open, close]`, or null when no constant
     * list can be proven. Returns `(firstToken, endExclusive)` — the exclusive end is one past the
     * terminating `;`.
     */
    private fun constantGroup(tokens: List<Token>, open: Int, close: Int): Pair<Int, Int>? {
        var i = open + 1
        while (i < close) {
            val start = JavaText.nextSignificant(tokens, i, close) ?: return null
            val first = parseConstant(tokens, start, close)
            if (first == null) {
                val next = memberEnd(tokens, start, close)
                if (next <= i) return null
                i = next
                continue
            }
            if (first.separator == ";") {
                return if (atMemberBoundary(tokens, open, start)) start to first.end else null
            }
            // A `,` can only be followed by another constant; anything else is a shape we do not
            // understand, and guessing here could move a non-constant member to the top.
            var end = first.end
            while (true) {
                val next = parseConstant(tokens, end, close) ?: return null
                end = next.end
                if (next.separator == ";") {
                    return if (atMemberBoundary(tokens, open, start)) start to end else null
                }
            }
        }
        return null
    }

    /**
     * The constant list must begin at a member boundary — right after the body brace or after a
     * previous member's `;` / `}`. Without this check a scan that lost track of member structure
     * could splice mid-expression.
     */
    private fun atMemberBoundary(tokens: List<Token>, open: Int, start: Int): Boolean {
        val previous = JavaText.previousSignificant(tokens, start) ?: return true
        if (previous <= open) return true
        return tokens[previous].text in setOf(";", "}", "{")
    }

    /** One enum constant: annotations, a name, optional arguments, an optional class body, terminator. */
    private fun parseConstant(tokens: List<Token>, start: Int, bodyClose: Int): Constant? {
        var j = start
        // Annotations on the constant (rare, but legal and preserved).
        while (j < bodyClose && tokens[j].text == "@") {
            j++
            if (j >= bodyClose || tokens[j].kind != Kind.IDENT) return null
            j++
            while (j + 1 < bodyClose && tokens[j].text == "." && tokens[j + 1].kind == Kind.IDENT) j += 2
            if (j < bodyClose && tokens[j].text == "(") {
                j = (JavaText.matchingParen(tokens, j) ?: return null) + 1
            }
        }
        if (j >= bodyClose) return null
        val name = tokens[j]
        if (name.kind != Kind.IDENT || JavaText.isKeyword(name.text)) return null
        j++
        if (j < bodyClose && tokens[j].text == "(") {
            j = (JavaText.matchingParen(tokens, j) ?: return null) + 1
        }
        if (j < bodyClose && tokens[j].text == "{") {
            j = (JavaText.matchingBrace(tokens, j) ?: return null) + 1
        }
        if (j >= bodyClose) return null
        val separator = tokens[j].text
        if (separator != "," && separator != ";") return null
        return Constant(j + 1, separator)
    }

    /**
     * Where the member beginning at [start] ends, as an exclusive token index. A member ends at the
     * first `;` at depth 0, or at a `}` that closes the member's own body — unless that `}` is an
     * array initializer (`= new Foo[]{...};`), which continues with `,` or `;`.
     */
    private fun memberEnd(tokens: List<Token>, start: Int, bodyClose: Int): Int {
        var j = start
        var depth = 0
        while (j < bodyClose) {
            when (tokens[j].text) {
                "(", "[", "{" -> depth++
                ")", "]", "}" -> depth--
            }
            if (depth <= 0) {
                if (tokens[j].text == ";") return j + 1
                if (tokens[j].text == "}") {
                    val next = JavaText.nextSignificant(tokens, j + 1, bodyClose)
                    val continues = next != null && tokens[next].text in setOf(",", ";", "]", ")")
                    if (!continues) return j + 1
                }
            }
            j++
        }
        return bodyClose
    }

    /** True when nothing but comments separates the body's `{` from its first constant. */
    private fun isAtTop(tokens: List<Token>, open: Int, start: Int): Boolean {
        for (k in (open + 1) until start) {
            if (tokens[k].kind != Kind.COMMENT) return false
        }
        return true
    }

    private fun moveFor(text: String, tokens: List<Token>, open: Int, start: Int, end: Int): Move? {
        val firstToken = tokens[start]
        val lastToken = tokens[end - 1]
        if (lastToken.text != ";") return null
        val braceEnd = tokens[open].end
        val lineStart = text.lastIndexOf('\n', firstToken.start - 1) + 1
        // The constant list must own its lines: anything else on the first line (a field sharing it)
        // and this shape is not one we can splice safely.
        if (lineStart < braceEnd) return null
        if (text.substring(lineStart, firstToken.start).isNotBlank()) return null
        val blockEnd = lastToken.end
        val consume = when {
            text.startsWith("\r\n", blockEnd) -> 2
            text.startsWith("\n", blockEnd) -> 1
            else -> 0
        }
        val newline = if (text.contains("\r\n")) "\r\n" else "\n"
        return Move(braceEnd, lineStart, blockEnd, consume, newline)
    }

    /**
     * Splices every move into the text, right to left so earlier offsets stay valid. Ranges must be
     * disjoint and must not contain one another — a nested enum declared *before* the outer body's
     * constants would break the offset bookkeeping, and that unit is returned unchanged instead.
     */
    private fun applyMoves(text: String, moves: List<Move>): String? {
        if (moves.isEmpty()) return text
        val ordered = moves.sortedBy { it.braceEnd }
        for (i in 0 until ordered.size - 1) {
            if (ordered[i].blockEnd > ordered[i + 1].braceEnd) return null
        }
        var result = text
        for (move in ordered.asReversed()) {
            val block = result.substring(move.blockStart, move.blockEnd)
            val middle = result.substring(move.braceEnd, move.blockStart)
            val tail = result.substring(move.blockEnd + move.consume)
            result = result.substring(0, move.braceEnd) + move.newline + block + middle + tail
        }
        return result
    }

    // -- (b) the dangling `$VALUES` reference --------------------------------------------------

    /**
     * Rewrites references to the synthetic `$VALUES` field when the unit does not declare it and the
     * emitted accessor exists. A unit that declares the field, or has no accessor, is left alone.
     */
    private fun repairValues(text: String): String {
        val tokens = JavaText.tokenize(text)
        if (enumBodyOpens(tokens).isNullOrEmpty()) return text
        val references = tokens.indices.filter {
            tokens[it].kind == Kind.IDENT && tokens[it].text == VALUES_FIELD
        }
        if (references.isEmpty()) return text
        // A declaration (`Foo[] $VALUES`, `Foo $VALUES`) means the field was emitted: leave it be.
        val declared = references.any { i ->
            val previous = JavaText.previousSignificant(tokens, i)
            previous != null && (
                tokens[previous].text == "]" ||
                    tokens[previous].text == ">" ||
                    (tokens[previous].kind == Kind.IDENT && !JavaText.isKeyword(tokens[previous].text))
                )
        }
        if (declared) return text
        val accessorEmitted = tokens.indices.any {
            tokens[it].kind == Kind.IDENT && tokens[it].text == "\$values" &&
                tokens.getOrNull(it + 1)?.text == "("
        }
        if (!accessorEmitted) return text
        var result = text
        for (i in references.sortedDescending()) {
            val previous = JavaText.previousSignificant(tokens, i)
            // `Foo.$VALUES` is a qualified access to some *other* enum's field: not ours to rewrite.
            if (previous != null && tokens[previous].text == ".") continue
            result = result.substring(0, tokens[i].start) + VALUES_ACCESSOR + result.substring(tokens[i].end)
        }
        return result
    }
}
