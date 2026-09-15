package org.example.k2j.core

import org.example.k2j.core.JavaText.Kind
import org.example.k2j.core.JavaText.Token

/**
 * Text-level repair for the inlined `Map.filter` / `Map.mapNotNull` / `Map.associateByTo` loop whose
 * receiver FernFlower could not type.
 *
 * Kotlin writes **no** `LocalVariableTypeTable` — `javap -v` on any class this pipeline decompiles
 * shows a `LocalVariableTable` and nothing else — so a local that Kotlin's backend typed
 * `Map<IPropertyTypeId, PropertyValue>` reaches FernFlower as the erased signature `Ljava/util/Map;`
 * and it renders the declaration raw. The *loop variable* of the same inlined body is a different
 * story: Kotlin did write `Ljava/util/Map$Entry;` for it, and FernFlower renders that faithfully
 * too. The two faithful renderings do not compose:
 *
 * ```java
 * Map $this$filter$iv = this.getProperties();          // from the erased LVT signature: raw
 * ...
 * for(Map.Entry element$iv$iv : $this$filter$iv.entrySet()) {   // from the typed LVT signature
 * ```
 *
 * javac reads the element type of a raw `Set` as `java.lang.Object` (JLS 14.14.2), so the enhanced
 * `for` is rejected — the real `target module` unit
 * `com.example.app.properties.Properties` failed its compile gate with exactly
 * `Properties.java:56:64: incompatible types: java.lang.Object cannot be converted to
 * java.util.Map.Entry` (and again at `:69`), which left `Properties.java` unwritten and 16 callers
 * of its constructors failing with
 * `no constructor found for Properties(Map,int,DefaultConstructorMarker)`.
 *
 * The bytecode underneath is not in doubt: at `Iterator.next()` the class file performs
 * `checkcast java/util/Map$Entry` on every element (offsets 67/72 and 194/199 of
 * `Properties.equals`), which is precisely the assertion `Map.Entry` makes. What is missing is only
 * a *static* type for the iterable. [normalize] supplies it, by giving the iterated expression the
 * parameterized type the compiler already checked at runtime: `(java.util.Set<Map.Entry>)`. The cast
 * is erasure-neutral — its erasure is `java.util.Set`, the operand's own static type, so javac emits
 * no runtime cast at all, and the `checkcast Map$Entry` inside the loop survives exactly as the
 * compiler originally emitted it. Nothing but the loop header's iterable expression is touched, and
 * `java.util.Set` is spelled in full because no import is added.
 *
 * The generic types themselves are **not** recovered from the class file — they are not there. So
 * this pass does not try: it refuses the unit (byte for byte) unless the receiver's static type is
 * *proven* raw `java.util.Map` from the text, which is the one fact the repair's soundness rests on.
 * The rule, in full — **all** of these must hold or [normalize] returns its input unchanged:
 *
 * 1. the unit imports or otherwise names `java.util.Map` (`java`, `.`, `util`, `.`, `Map` as tokens),
 *    so the bare `Map` in a declaration is provably `java.util.Map` and not a class of that name;
 * 2. a candidate is a `for` header whose colon is at its top level, whose element variable's type
 *    erases to exactly `Map.Entry` (`Map.Entry` or `Map.Entry<K, V>`, with either spelling of the
 *    qualifier) and whose iterated expression is exactly `name.entrySet()` — five tokens;
 * 3. **every** declaration-shaped occurrence of that `name` in the unit is the raw-map form
 *    `Map name = ...`: a type token directly before the name and `=` directly after it.
 *    A `Map<K, V> name = ...`, a `Iterable name = ...`, a parameter, or a bare `Map name;` makes the
 *    receiver's static type unproven — and a cast whose operand is not a `Set` would be a
 *    `ClassCastException` the original text did not have — so the whole unit is refused;
 * 4. at least one such declaration exists, so a receiver that is a parameter or an unresolved name
 *    is refused too;
 * 5. **any** candidate that fails 3 or 4 refuses the whole unit: a partially repaired unit still
 *    fails the gate, and a repair that cannot be proven at every site it applies to is a guess.
 *
 * Anything else — a cast already present, `this.<field>.entrySet()`, an element type that is not
 * `Map.Entry`, a unit with no candidate — leaves the text alone, for [JavacValidator] or the compile
 * gate to report. A repaired unit is idempotent: the cast it adds means the expression no longer
 * matches rule 2, so a second pass is a no-op. Tokenization, splicing and comment handling are shared
 * with the other normalizers through [JavaText], and every edit replaces the exact character range of
 * the expression with itself, so no other byte of the unit moves.
 */
object RawEntrySetNormalizer {

    /** The parameterized erasure of the iterable: `Set`, spelled out so no import is needed. */
    private const val SET = "java.util.Set"

    /** The type tokens a *declaration* may be written with, when the type is a primitive or array. */
    private val PRIMITIVE_TYPES =
        setOf("boolean", "byte", "char", "short", "int", "long", "float", "double")

    /** The entry type FernFlower's loop variable is typed with, qualified or not. */
    private val ENTRY = listOf("Map", ".", "Entry")

    /** One splice: the character range to replace and what replaces it. */
    private data class Edit(val start: Int, val end: Int, val replacement: String)

    /** A `for (Map.Entry e : name.entrySet())` header, with the ranges the repair needs. */
    private data class Candidate(
        val elementTypeStart: Int,
        val elementTypeEnd: Int,
        val receiver: String,
        val expressionStart: Int,
        val expressionEnd: Int
    )

    /** Types the iterable of a raw-map `for` header; returns [javaText] unchanged when unproven. */
    fun normalize(javaText: String): String = try {
        repair(javaText)
    } catch (t: Throwable) {
        javaText
    }

    private fun repair(text: String): String {
        if (!text.contains("entrySet")) return text
        val tokens = JavaText.tokenize(text)

        val candidates = candidates(tokens)
        if (candidates.isEmpty()) return text
        // Rule 1: without this, `Map` could name a class of the unit's own, whose `entrySet()` is not
        // a `java.util.Set` at all — and the cast would be a `ClassCastException` the text never had.
        if (!namesJavaUtilMap(tokens)) return text
        // Rules 3-5: every candidate, or none.
        for (candidate in candidates) {
            if (!receiverIsRawMap(tokens, candidate.receiver)) return text
        }

        val edits = candidates.map { candidate ->
            val elementType = text.substring(
                tokens[candidate.elementTypeStart].start,
                tokens[candidate.elementTypeEnd].end
            )
            Edit(
                candidate.expressionStart,
                candidate.expressionEnd,
                "($SET<$elementType>) " + text.substring(candidate.expressionStart, candidate.expressionEnd)
            )
        }
        // Every edit covers a disjoint range of the original text, so splicing right-to-left keeps
        // the offsets of the edits still to be applied valid.
        var result = text
        for (edit in edits.sortedByDescending { it.start }) {
            result = result.substring(0, edit.start) + edit.replacement + result.substring(edit.end)
        }
        return result
    }

    // -- finding the candidate loops -----------------------------------------------------------------

    /** Every `for` header in the unit that iterates `name.entrySet()` with a `Map.Entry` variable. */
    private fun candidates(tokens: List<Token>): List<Candidate> {
        val found = mutableListOf<Candidate>()
        for (i in tokens.indices) {
            if (tokens[i].kind != Kind.IDENT || tokens[i].text != "for") continue
            val open = JavaText.nextSignificant(tokens, i + 1) ?: continue
            if (tokens[open].text != "(") continue
            val close = JavaText.matchingParen(tokens, open) ?: continue
            found += candidate(tokens, open, close) ?: continue
        }
        return found
    }

    /** The candidate shape of one `for` header, or null when the header is not that shape. */
    private fun candidate(tokens: List<Token>, open: Int, close: Int): Candidate? {
        val colon = topLevelColon(tokens, open, close) ?: return null
        val name = JavaText.previousSignificant(tokens, colon) ?: return null
        if (tokens[name].kind != Kind.IDENT || JavaText.isKeyword(tokens[name].text)) return null
        val typeStart = JavaText.nextSignificant(tokens, open + 1) ?: return null
        val typeEnd = JavaText.previousSignificant(tokens, name) ?: return null
        if (typeEnd < typeStart) return null
        if (!isMapEntry(tokens, typeStart, typeEnd)) return null

        // The iterated expression must be exactly `name.entrySet()`: no receiver chain, no cast, no
        // call arguments — anything else is a shape this pass cannot reason about.
        val expression = significantBetween(tokens, colon + 1, close)
        if (expression.size != 5) return null
        val receiver = tokens[expression[0]]
        if (receiver.kind != Kind.IDENT || JavaText.isKeyword(receiver.text)) return null
        if (tokens[expression[1]].text != ".") return null
        if (tokens[expression[2]].kind != Kind.IDENT || tokens[expression[2]].text != "entrySet") return null
        if (tokens[expression[3]].text != "(" || tokens[expression[4]].text != ")") return null

        return Candidate(typeStart, typeEnd, receiver.text, receiver.start, tokens[expression[4]].end)
    }

    /** The colon that separates a `for` header's variable from its expression, or null. */
    private fun topLevelColon(tokens: List<Token>, open: Int, close: Int): Int? {
        var depth = 0
        for (i in (open + 1) until close) {
            when (tokens[i].text) {
                "(", "[", "{" -> depth++
                ")", "]", "}" -> depth--
                ":" -> if (depth == 0) return i
            }
        }
        return null
    }

    /** The significant tokens in `(start, end)`, exclusive on [end]. */
    private fun significantBetween(tokens: List<Token>, start: Int, end: Int): List<Int> {
        val indices = mutableListOf<Int>()
        var i = start
        while (i < end) {
            if (tokens[i].kind != Kind.COMMENT) indices += i
            i++
        }
        return indices
    }

    /**
     * True when the tokens [start]..[end] spell `Map.Entry`, `Map.Entry<K, V>` or the fully qualified
     * `java.util.Map.Entry` form of either — the erasures the loop variable can have.
     */
    private fun isMapEntry(tokens: List<Token>, start: Int, end: Int): Boolean {
        var i = start
        if (i + 5 <= end &&
            tokens[i].text == "java" && tokens[i + 1].text == "." &&
            tokens[i + 2].text == "util" && tokens[i + 3].text == "."
        ) {
            i += 4
        }
        if (i + 2 > end) return false
        for (k in ENTRY.indices) {
            if (tokens[i + k].text != ENTRY[k]) return false
        }
        i += ENTRY.size
        if (i > end) return true
        // Optional type arguments, and nothing after them.
        if (tokens[i].text != "<" || tokens[end].text != ">") return false
        var depth = 0
        for (j in i..end) {
            when (tokens[j].text) {
                "<" -> depth++
                ">" -> {
                    depth--
                    if (depth == 0) return j == end
                }
            }
        }
        return false
    }

    // -- proving the receiver's static type ----------------------------------------------------------

    /**
     * True when every declaration-shaped occurrence of [name] in the unit is the raw-map form
     * `Map name = ...`, and at least one exists. That is the whole proof the cast rests on: whichever
     * declaration a given site binds to, `entrySet()` returns the raw `java.util.Set` the cast
     * parameterizes.
     */
    private fun receiverIsRawMap(tokens: List<Token>, name: String): Boolean {
        var declarations = 0
        for (i in tokens.indices) {
            if (tokens[i].kind != Kind.IDENT || tokens[i].text != name) continue
            val before = JavaText.previousSignificant(tokens, i) ?: continue
            if (!isTypeToken(tokens[before])) continue
            // A type token directly before the name makes this occurrence a declaration. Only the
            // unparameterized `Map name = ...` form proves the receiver's type; `Map<K, V> name = ...`
            // (or any other type) leaves the whole unit refused.
            val after = JavaText.nextSignificant(tokens, i + 1) ?: return false
            if (tokens[before].text != "Map" || tokens[after].text != "=") return false
            declarations++
        }
        return declarations > 0
    }

    /** The last token of a type: a class name, a `>` of type arguments, a `]` of an array, a primitive. */
    private fun isTypeToken(token: Token): Boolean =
        (token.kind == Kind.IDENT && !JavaText.isKeyword(token.text)) ||
            token.text in PRIMITIVE_TYPES || token.text == ">" || token.text == "]"

    /** True when the unit names `java.util.Map` (an import, or a qualified use). */
    private fun namesJavaUtilMap(tokens: List<Token>): Boolean {
        for (i in 0 until tokens.size - 4) {
            if (tokens[i].text == "java" && tokens[i + 1].text == "." &&
                tokens[i + 2].text == "util" && tokens[i + 3].text == "." &&
                tokens[i + 4].text == "Map"
            ) {
                return true
            }
        }
        return false
    }
}
