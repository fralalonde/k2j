package org.example.k2j.core

import org.example.k2j.core.JavaText.Kind
import org.example.k2j.core.JavaText.Token

/**
 * Text-level repair for the shape Kotlin's **declaration-site variance** produces.
 *
 * Kotlin's `Map<K, out V>` declares `V` covariant, so a data class whose property is
 * `Map<String, Number>` compiles to a field typed `Map<String, Number>` while the **parameter** of
 * the synthesized constructor (and of `copy(...)`) carries the wildcard Kotlin wrote into the
 * generic signature:
 *
 * ```
 * public final class VarianceProps implements Props {
 *    @NotNull
 *    private final Map<String, Number> properties;                       // invariant
 *    public VarianceProps(@NotNull Map<String, ? extends Number> properties) {
 *       super();
 *       this.properties = properties;                                    // javac rejects this
 *    }
 *    public final VarianceProps copy(@NotNull Map<String, ? extends Number> properties) {
 *       return new VarianceProps(properties);                            // ... and this
 *    }
 * }
 * ```
 *
 * javac: `incompatible types: Map<String,CAP#1> cannot be converted to Map<String,Number> where
 * CAP#1 extends Number from capture of ? extends Number`. The text *parses*, so [JavaValidator]
 * cannot see it; only compiling the output can (which is what `--compile-check` exists for).
 *
 * [normalize] strips the wildcards from exactly the parameters that the shape makes provably safe
 * to strip, and touches nothing else. The rule, in full — **all** of these must hold or the unit is
 * returned unchanged:
 *
 * 1. the parameter's declared type contains a wildcard (`? extends X` / `? super X`);
 * 2. a **field of the same simple name** is declared in the **same class body** as the declaration
 *    carrying the parameter, at member level, with no initializer;
 * 3. the field's declared type is **invariant** — it contains no wildcard at all;
 * 4. the two types are **token-identical once the parameter's wildcards are replaced by their
 *    bounds**. That single comparison proves the erasure is the same (the erasure tokens are part
 *    of it), the arity is the same, every type argument is the same, and the field is neither
 *    narrower nor wider than the stripped parameter. A bare `?` has no bound to substitute and is
 *    refused, a raw-typed field never matches a parameterized parameter, and `? super X` is only
 *    accepted when substituting `X` reproduces the field's type exactly;
 * 5. for a **constructor** parameter, the constructor body assigns it to that field
 *    (`this.<name> = <name>;`) — the assignment the wildcard breaks;
 * 6. for the parameter of the **`copy(...)` method**, its body passes it to that same constructor
 *    (`new <Class>(<name>)`), where `<Class>` declares a constructor parameter this pass already
 *    stripped under the same name.
 *
 * The rewrite is a splice of the field's declared type text over the parameter's type range, so the
 * parameter ends up **byte-identical to the field** and the assignment is legal by construction.
 * Nothing is reformatted: the surrounding annotations, parameter names, bodies and comments are
 * carried over verbatim because only that one range of the original text is replaced.
 *
 * Deliberately conservative. It never widens or narrows an API beyond the one parameter whose type
 * it can prove equals the field it is assigned to, it does not touch the field, the getter, the
 * synthetic `copy$default`, or any member it did not match, and it returns the input **unchanged**
 * (byte for byte) when nothing matches. A unit it cannot prove safe is left for [JavaValidator] /
 * the compile check to reject and [K2j] to report as a per-class failure — a class is never
 * silently half-rewritten and never silently dropped.
 *
 * Tokenization is shared with [ConstructorNormalizer] and [EnumNormalizer] through [JavaText], so
 * all three agree on what an identifier, a literal or a comment is, and none of them ever re-prints
 * the text it edits.
 */
object WildcardCaptureNormalizer {

    /** The method whose parameter Kotlin's `data class` copy carries the same wildcard on. */
    private const val COPY = "copy"

    /** What a constructor's name may follow: modifiers, or nothing (start of a class body). */
    private val CONSTRUCTOR_PREFIXES = setOf("public", "private", "protected", "{", "}", ";")

    /** What a field declaration's type may follow. */
    private val FIELD_PREFIXES = setOf(
        "public", "private", "protected", "static", "final", "transient", "volatile"
    )

    /** Strips provably-matching wildcards; returns [javaText] unchanged when no rule applies. */
    fun normalize(javaText: String): String = try {
        strip(javaText)
    } catch (t: Throwable) {
        javaText
    }

    /** A declared type: the original text of its token range plus its wildcard-stripped token form. */
    private data class Type(
        val start: Int,
        val end: Int,
        val text: String,
        val canonical: List<String>,
        val wildcard: Boolean
    )

    private data class Field(val name: String, val type: Type, val classBody: Int)

    private data class Parameter(val name: String, val type: Type)

    /** A constructor or method declaration with its parsed parameter list. */
    private data class Declaration(
        val name: String,
        val constructor: Boolean,
        val classBody: Int,
        val params: List<Parameter>,
        val bodyOpen: Int,
        val bodyClose: Int
    )

    /** One parameter type to replace with the field's type text. */
    private data class Edit(val start: Int, val end: Int, val replacement: String)

    /** A constructor parameter this pass stripped: its class, its name, and its class body. */
    private data class Fix(val owner: String, val parameter: String, val classBody: Int)

    private fun strip(text: String): String {
        val tokens = JavaText.tokenize(text)
        if (tokens.none { it.kind == Kind.PUNCT && it.text == "?" }) return text

        val classNames = classNames(tokens)
        if (classNames.isEmpty()) return text
        val declarations = declarations(tokens, text, classNames)
        if (declarations.isEmpty()) return text

        val edits = mutableListOf<Edit>()
        val fixes = mutableListOf<Fix>()

        for (declaration in declarations) {
            if (!declaration.constructor) continue
            for (parameter in declaration.params) {
                val field = fieldFor(tokens, text, parameter, declaration) ?: continue
                if (field.classBody != declaration.classBody) continue
                if (!assignsField(tokens, declaration, parameter.name)) continue
                edits += Edit(parameter.type.start, parameter.type.end, field.type.text)
                fixes += Fix(declaration.name, parameter.name, declaration.classBody)
            }
        }

        for (declaration in declarations) {
            if (declaration.constructor || declaration.name != COPY) continue
            for (parameter in declaration.params) {
                val field = fieldFor(tokens, text, parameter, declaration) ?: continue
                if (field.classBody != declaration.classBody) continue
                val passed = fixes.any { fix ->
                    fix.classBody == declaration.classBody &&
                        fix.parameter == parameter.name &&
                        passesToConstructor(tokens, declaration, fix.owner, parameter.name)
                }
                if (!passed) continue
                edits += Edit(parameter.type.start, parameter.type.end, field.type.text)
            }
        }

        if (edits.isEmpty()) return text
        // Each edit replaces one parameter's type range; ranges belong to different parameter
        // lists, so they can never overlap. Splicing right-to-left keeps the earlier offsets valid.
        var result = text
        for (edit in edits.sortedByDescending { it.start }) {
            result = result.substring(0, edit.start) + edit.replacement + result.substring(edit.end)
        }
        return result
    }

    // -- matching ------------------------------------------------------------------------------

    /**
     * The field declaration [parameter] may be reconciled with, or null when any part of the rule
     * (wildcard present, same name, same class body, invariant field, wildcard-stripped token
     * equality) cannot be proven.
     */
    private fun fieldFor(
        tokens: List<Token>,
        text: String,
        parameter: Parameter,
        declaration: Declaration
    ): Field? {
        if (!parameter.type.wildcard) return null
        val field = findField(tokens, text, parameter.name, declaration.classBody) ?: return null
        if (field.type.wildcard) return null
        // The one comparison the whole transform rests on: the field's type must BE the parameter's
        // type with the wildcards substituted away. Anything else (different erasure, different
        // arity, different argument, raw field) is not a shape we can prove anything about.
        if (field.type.canonical != parameter.type.canonical) return null
        return field
    }

    /**
     * The member-level declaration of a field named [name] **in the class body [classBody]**, or
     * null. The declaration must end at a `;` (the no-initializer shape Kotlin's properties are
     * emitted with), its type must parse from the name backwards, and it must sit in a class body —
     * not in a method, constructor or initializer block. The class body is required, not merely
     * checked afterwards: two nested classes may declare fields of the same name, and the field that
     * licenses a rewrite is the one in the same body as the declaration being rewritten.
     */
    private fun findField(tokens: List<Token>, text: String, name: String, classBody: Int): Field? {
        for (i in tokens.indices) {
            if (tokens[i].kind != Kind.IDENT || tokens[i].text != name) continue
            val terminator = JavaText.nextSignificant(tokens, i + 1) ?: continue
            if (tokens[terminator].text != ";") continue
            val before = JavaText.previousSignificant(tokens, i) ?: continue
            // `this.properties` / `x.properties` / `f(properties` are references, not declarations.
            if (tokens[before].text == "." || tokens[before].text == "(" || tokens[before].text == ",") continue
            val start = typeStart(tokens, before) ?: continue
            val prefix = JavaText.previousSignificant(tokens, start)
            if (prefix != null && !isFieldPrefix(tokens, prefix)) continue
            val type = type(tokens, text, start, i) ?: continue
            if (type.wildcard) continue
            val owner = JavaText.enclosingBlockOpen(tokens, start) ?: continue
            if (owner != classBody) continue
            // A `{` preceded by `)` opens a method/constructor body, not a class body.
            val braceOwner = JavaText.previousSignificant(tokens, owner)
            if (braceOwner != null && tokens[braceOwner].text == ")") continue
            return Field(name, type, owner)
        }
        return null
    }

    /** True when the token before a field's type can legitimately precede one. */
    private fun isFieldPrefix(tokens: List<Token>, index: Int): Boolean {
        if (tokens[index].text in FIELD_PREFIXES) return true
        if (tokens[index].text == "{" || tokens[index].text == "}" || tokens[index].text == ";") return true
        return annotationTail(tokens, index)
    }

    /** True when token [index] is the last token of an annotation (`@NotNull`, `@a.b.C(1)`). */
    private fun annotationTail(tokens: List<Token>, index: Int): Boolean {
        var j = index
        if (tokens[j].text == ")") {
            var depth = 0
            var k = j
            while (k >= 0) {
                when (tokens[k].text) {
                    ")" -> depth++
                    "(" -> {
                        depth--
                        if (depth == 0) break
                    }
                }
                k--
            }
            if (k < 0) return false
            j = k - 1
        }
        while (j >= 2 && tokens[j - 1].text == "." && tokens[j - 2].kind == Kind.IDENT) j -= 2
        return j >= 1 && tokens[j - 1].text == "@"
    }

    /** Token index where the type whose last token is [last] begins, or null when it does not parse. */
    private fun typeStart(tokens: List<Token>, last: Int): Int? {
        var i = last
        while (i >= 1 && tokens[i].text == "]" && tokens[i - 1].text == "[") i -= 2
        val t = tokens.getOrNull(i) ?: return null
        if (t.text == ">") {
            val open = matchingAngle(tokens, i) ?: return null
            val name = open - 1
            if (name < 0 || tokens[name].kind != Kind.IDENT || JavaText.isKeyword(tokens[name].text)) return null
            i = name
        } else if (t.kind != Kind.IDENT || JavaText.isKeyword(t.text)) {
            return null
        }
        // A qualified name (`java.util.Map<...>`) is one type, not a type followed by an access.
        while (i >= 2 && tokens[i - 1].text == "." && tokens[i - 2].kind == Kind.IDENT) i -= 2
        return i
    }

    /** Index of the `<` matching the `>` at [close], depth-counted. */
    private fun matchingAngle(tokens: List<Token>, close: Int): Int? {
        var depth = 0
        for (k in close downTo 0) {
            when (tokens[k].text) {
                ">" -> depth++
                "<" -> {
                    depth--
                    if (depth == 0) return k
                }
            }
        }
        return null
    }

    // -- types ---------------------------------------------------------------------------------

    /** The [Type] declared by the token range `[start, end)`, or null when the range is not one. */
    private fun type(tokens: List<Token>, text: String, start: Int, end: Int): Type? {
        var last = end - 1
        while (last >= start && tokens[last].kind == Kind.COMMENT) last--
        if (last < start) return null
        // Varargs carry a `...` that a type has no room for; never a shape this transform handles.
        for (k in start..(last - 2)) {
            if (tokens[k].text == "." && tokens[k + 1].text == "." && tokens[k + 2].text == ".") return null
        }
        val canonical = canonicalType(tokens, start, end) ?: return null
        if (canonical.isEmpty()) return null
        val wildcard = (start until end).any { tokens[it].text == "?" }
        return Type(
            start = tokens[start].start,
            end = tokens[last].end,
            text = text.substring(tokens[start].start, tokens[last].end),
            canonical = canonical,
            wildcard = wildcard
        )
    }

    /**
     * The token texts of the type in `[from, to)` with every wildcard replaced by its bound
     * (`? extends X` / `? super X` -> the tokens of `X`), or null when the range is not a type this
     * transform understands — including a bare `?`, which has no bound to substitute.
     */
    private fun canonicalType(tokens: List<Token>, from: Int, to: Int): List<String>? {
        val out = mutableListOf<String>()
        var i = from
        while (i < to) {
            val t = tokens[i]
            if (t.kind == Kind.COMMENT) {
                i++
                continue
            }
            if (t.text == "?") {
                val keyword = JavaText.nextSignificant(tokens, i + 1, to) ?: return null
                if (tokens[keyword].text != "extends" && tokens[keyword].text != "super") return null
                val bound = JavaText.nextSignificant(tokens, keyword + 1, to) ?: return null
                val boundEnd = typeEnd(tokens, bound, to)
                if (boundEnd <= bound) return null
                out += canonicalType(tokens, bound, boundEnd) ?: return null
                i = boundEnd
                continue
            }
            out += t.text
            i++
        }
        return out
    }

    /** Exclusive end of the type at [from]: the next `,` or `>` at nesting depth zero. */
    private fun typeEnd(tokens: List<Token>, from: Int, to: Int): Int {
        var angle = 0
        var i = from
        while (i < to) {
            when (tokens[i].text) {
                "<" -> angle++
                ">" -> if (angle == 0) return i else angle--
                "," -> if (angle == 0) return i
            }
            i++
        }
        return to
    }

    // -- declarations --------------------------------------------------------------------------

    /** The simple names of every class/interface/enum/record declared in the unit. */
    private fun classNames(tokens: List<Token>): Set<String> {
        val names = mutableSetOf<String>()
        for (i in tokens.indices) {
            if (tokens[i].kind != Kind.IDENT) continue
            if (tokens[i].text !in setOf("class", "interface", "enum", "record")) continue
            val previous = JavaText.previousSignificant(tokens, i)
            // `Foo.class` — a class literal, not a declaration.
            if (previous != null && tokens[previous].text == ".") continue
            val name = JavaText.nextSignificant(tokens, i + 1) ?: continue
            if (tokens[name].kind == Kind.IDENT && !JavaText.isKeyword(tokens[name].text)) names += tokens[name].text
        }
        return names
    }

    /** Every constructor and method declaration of the unit whose parameter list parses. */
    private fun declarations(tokens: List<Token>, text: String, classNames: Set<String>): List<Declaration> {
        val out = mutableListOf<Declaration>()
        for (i in tokens.indices) {
            val t = tokens[i]
            if (t.kind != Kind.IDENT || JavaText.isKeyword(t.text)) continue
            val open = JavaText.nextSignificant(tokens, i + 1) ?: continue
            if (tokens[open].text != "(") continue
            val close = JavaText.matchingParen(tokens, open) ?: continue
            val bodyOpen = JavaText.nextSignificant(tokens, close + 1) ?: continue
            if (tokens[bodyOpen].text != "{") continue
            val bodyClose = JavaText.matchingBrace(tokens, bodyOpen) ?: continue
            val previous = JavaText.previousSignificant(tokens, i)
            // `x.copy(...)` is a call: its `(` is not followed by a body, but a bare `foo() {` in an
            // expression cannot exist, so the `.` check is what separates a call from a declaration.
            if (previous != null && tokens[previous].text == ".") continue
            val constructor = t.text in classNames
            // A constructor has no return type: only a modifier (or the start of a body) precedes it.
            if (constructor && (previous == null || tokens[previous].text !in CONSTRUCTOR_PREFIXES)) continue
            val classBody = JavaText.enclosingBlockOpen(tokens, i) ?: continue
            val params = parameters(tokens, text, open, close) ?: continue
            out += Declaration(t.text, constructor, classBody, params, bodyOpen, bodyClose)
        }
        return out
    }

    /** The parameters of the list `[open, close]`, or null when one of them does not parse. */
    private fun parameters(tokens: List<Token>, text: String, open: Int, close: Int): List<Parameter>? {
        val out = mutableListOf<Parameter>()
        var i = JavaText.nextSignificant(tokens, open + 1, close) ?: return out
        while (i < close) {
            val end = topLevelEnd(tokens, i, close) ?: return null
            out += parameter(tokens, text, i, end) ?: return null
            if (tokens[end].text == ")") return out
            i = JavaText.nextSignificant(tokens, end + 1, close) ?: return out
        }
        return out
    }

    /**
     * One parameter from the token range `[from, end)`: its declared name (the last identifier) and
     * its type (everything between the leading annotations/`final` and the name). Returns null for a
     * shape this transform does not understand.
     */
    private fun parameter(tokens: List<Token>, text: String, from: Int, end: Int): Parameter? {
        var start = from
        while (start < end) {
            val t = tokens[start]
            if (t.text == "@") {
                start++
                if (start >= end || tokens[start].kind != Kind.IDENT) return null
                start++
                while (start + 1 < end && tokens[start].text == "." && tokens[start + 1].kind == Kind.IDENT) start += 2
                if (start < end && tokens[start].text == "(") {
                    start = (JavaText.matchingParen(tokens, start) ?: return null) + 1
                }
                continue
            }
            if (t.text == "final") {
                start++
                continue
            }
            break
        }
        val name = JavaText.previousSignificant(tokens, end) ?: return null
        if (name < start) return null
        // A trailing `[]` (C-style array declarator) would be dropped by a type splice, not moved.
        if (tokens[name].text == "]" || tokens[end - 1].text == "]") return null
        val token = tokens[name]
        if (token.kind != Kind.IDENT || JavaText.isKeyword(token.text)) return null
        if (name <= start) return null
        val type = type(tokens, text, start, name) ?: return null
        return Parameter(token.text, type)
    }

    /**
     * Exclusive index of the parameter starting at [from]: the next `,`, or the `)` that closes the
     * list, at nesting depth zero for `()`, `[]`, `{}` and `<...>`.
     */
    private fun topLevelEnd(tokens: List<Token>, from: Int, close: Int): Int? {
        var depth = 0
        var i = from
        while (i < close) {
            when (tokens[i].text) {
                "(", "[", "{", "<" -> depth++
                ")", "]", "}", ">" -> if (depth == 0) return i else depth--
                "," -> if (depth == 0) return i
            }
            i++
        }
        return if (i == close) close else null
    }

    // -- the assignment and the copy ------------------------------------------------------------------

    /** True when [declaration]'s body assigns the parameter [name] to the field of the same name. */
    private fun assignsField(tokens: List<Token>, declaration: Declaration, name: String): Boolean {
        var i = declaration.bodyOpen + 1
        while (i < declaration.bodyClose) {
            if (tokens[i].kind == Kind.IDENT && tokens[i].text == "this") {
                val dot = JavaText.nextSignificant(tokens, i + 1, declaration.bodyClose)
                val field = if (dot != null && tokens[dot].text == ".") {
                    JavaText.nextSignificant(tokens, dot + 1, declaration.bodyClose)
                } else {
                    null
                }
                if (field != null && tokens[field].kind == Kind.IDENT && tokens[field].text == name) {
                    val equals = JavaText.nextSignificant(tokens, field + 1, declaration.bodyClose)
                    val value = if (equals != null && tokens[equals].text == "=") {
                        JavaText.nextSignificant(tokens, equals + 1, declaration.bodyClose)
                    } else {
                        null
                    }
                    val semicolon = if (value != null && tokens[value].text == name) {
                        JavaText.nextSignificant(tokens, value + 1, declaration.bodyClose)
                    } else {
                        null
                    }
                    if (semicolon != null && tokens[semicolon].text == ";") return true
                }
            }
            i++
        }
        return false
    }

    /** True when [declaration]'s body calls `new <className>(...)` with [name] as an argument. */
    private fun passesToConstructor(
        tokens: List<Token>,
        declaration: Declaration,
        className: String,
        name: String
    ): Boolean {
        var i = declaration.bodyOpen + 1
        while (i < declaration.bodyClose) {
            if (tokens[i].kind == Kind.IDENT && tokens[i].text == "new") {
                val target = JavaText.nextSignificant(tokens, i + 1, declaration.bodyClose)
                if (target != null && tokens[target].kind == Kind.IDENT && tokens[target].text == className) {
                    val open = JavaText.nextSignificant(tokens, target + 1, declaration.bodyClose)
                    if (open != null && tokens[open].text == "(") {
                        val close = JavaText.matchingParen(tokens, open)
                        if (close != null && close < declaration.bodyClose &&
                            isBareArgument(tokens, open, close, name)
                        ) {
                            return true
                        }
                    }
                }
            }
            i++
        }
        return false
    }

    /** True when one of the top-level arguments between [open] and [close] is exactly `name`. */
    private fun isBareArgument(tokens: List<Token>, open: Int, close: Int, name: String): Boolean {
        var i = JavaText.nextSignificant(tokens, open + 1, close) ?: return false
        while (i < close) {
            val end = topLevelEnd(tokens, i, close) ?: return false
            val first = JavaText.nextSignificant(tokens, i, end) ?: return false
            var last = end - 1
            while (last > first && tokens[last].kind == Kind.COMMENT) last--
            if (first == last && tokens[first].kind == Kind.IDENT && tokens[first].text == name) return true
            if (end >= close) return false
            i = JavaText.nextSignificant(tokens, end + 1, close) ?: return false
        }
        return false
    }
}
