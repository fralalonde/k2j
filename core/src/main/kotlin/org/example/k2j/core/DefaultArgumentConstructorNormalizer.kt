package org.example.k2j.core

import org.example.k2j.core.JavaText.Kind
import org.example.k2j.core.JavaText.Token

/**
 * Text-level repair for FernFlower's *synthetic-local delegation* shape — a constructor whose
 * delegation argument is an expression the decompiler had to spill into a temporary local.
 *
 * Kotlin emits a constructor that delegates with a **call on a platform type** as
 *
 * ```
 * public ActivityId() {
 *    UUID var10001 = UUID.randomUUID();                        // the spill
 *    Intrinsics.checkNotNullExpressionValue(var10001, "randomUUID(...)");
 *    this(var10001);                                          // the delegation
 * }
 * ```
 *
 * The compiler (and the JVM) put the spill *before* the delegation. FernFlower copies the spill and
 * the check but moves the `this(...)`/`super(...)` delegation to the front, which is the only order
 * Java accepts, and leaves the declaration below it. The unit **parses** — an explicit constructor
 * invocation is grammatically valid — and javac then rejects it:
 *
 * ```
 * ActivityId.java:40:30: cannot find symbol
 *   symbol:   variable var10001
 *   location: class ...ActivityId
 * ```
 *
 * [normalize] inlines the initializer into the delegation (`this(<expr>)` / `super(<expr>)`) and
 * deletes the declaration plus the null-check statements that existed only to serve it, which is
 * exactly the evaluation order the bytecode had. Every other byte of the unit is carried over
 * verbatim: the edits are splices of the original text over the matched token ranges, so
 * whitespace, comments, annotations and the decompiler's formatting survive untouched.
 *
 * The transform is deliberately conservative. A constructor is rewritten only when **all** of this
 * is proven, and left byte-identical otherwise:
 *
 * 1. its body holds exactly one delegating `this(...)` / `super(...)` statement, in either of the
 *    two orders the defect appears in — the compiler's (declaration and check *before* the
 *    delegation, which javac rejects with "call to this must be first statement in constructor") or
 *    the hoisted one (declaration and check *after* it, which javac rejects with "cannot find
 *    symbol");
 * 2. the delegation's argument list references exactly one identifier that (a) is not one of the
 *    constructor's declared parameters, (b) stands alone between `(`/`,` and `,`/`)` — so it is a
 *    reference, not a cast target, a member access or a call receiver — and (c) occurs exactly
 *    once in the argument list, so inlining cannot duplicate an expression;
 * 3. the same body declares that identifier exactly once, as a simple, unannotated,
 *    single-declarator statement `<Type> <ident> = <expr>;` sitting directly in the constructor
 *    body (not inside an `if` or any nested block), whose type parses;
 * 4. the initializer references no local that the body declares later;
 * 5. with the declaration and its null-checks removed, the identifier is referenced nowhere else in
 *    the body, so deleting the local cannot change anything else;
 * 6. every statement the deletion swallows is either that declaration or a call to
 *    `Intrinsics.checkNotNull*` whose only other argument is a literal, i.e. a statement whose
 *    entire effect is null-checking that one local; and
 * 7. every statement that precedes the delegation is one of the deleted ones — so the inlined
 *    expression is still evaluated exactly where it was, and the delegation is the body's first
 *    statement afterwards, which is what Java demands.
 *
 * A constructor that fails any of these is skipped (the unit simply does not match), so the unit is
 * left for [JavaValidator] / the compile gate to reject and [K2j] to report as a per-class failure
 * — a class is never silently half-rewritten and never silently dropped. Shapes this transform
 * knows about but deliberately refuses (the `DefaultConstructorMarker` default-argument constructor,
 * whose default is applied *after* the delegation, and the `(int mask & 1) != 0` form it holds) are
 * documented in `DefaultArgumentConstructorNormalizerTest`.
 *
 * Tokenization is shared with [ConstructorNormalizer], [EnumNormalizer] and
 * [WildcardCaptureNormalizer] through [JavaText], so all four agree on what an identifier, a
 * literal or a comment is, and none of them ever re-prints the text it edits.
 */
object DefaultArgumentConstructorNormalizer {

    /** What may precede a constructor's name: a modifier, or the start/end of a class body. */
    private val CONSTRUCTOR_PREFIXES = setOf("public", "private", "protected", "{", "}", ";")

    /** The intrinsics methods whose whole body is "throw if this argument is null". */
    private val NULL_CHECKS = setOf("checkNotNullExpressionValue", "checkNotNull")

    /** Strips nothing outside a proven shape; returns [javaText] unchanged when nothing matches. */
    fun normalize(javaText: String): String = try {
        repair(javaText)
    } catch (t: Throwable) {
        javaText
    }

    /** One splice over the original text: `[start, end)` becomes [replacement]. */
    private data class Edit(val start: Int, val end: Int, val replacement: String)

    /** A constructor of the unit: where its parameter list and its body are. */
    private data class Constructor(
        val paramOpen: Int,
        val paramClose: Int,
        val bodyOpen: Int,
        val bodyClose: Int
    )

    /** A `<Type> <ident> = <expr>;` statement that declares a local directly in a constructor body. */
    private data class Declaration(
        val nameIndex: Int,
        val statementStart: Int,
        val semicolon: Int,
        val expressionStart: Int,
        val expressionEnd: Int
    )

    /** A `checkNotNull*(ident, "<literal>")` statement and its original message literal. */
    private data class Check(val start: Int, val semicolon: Int, val message: String)

    /** A proven spilled local referenced once by the constructor delegation. */
    private data class Spill(
        val ident: String,
        val reference: Int,
        val declaration: Declaration
    )

    /** A delegating statement and every spilled local its argument list references. */
    private data class Delegation(
        val statement: Int,
        val semicolon: Int,
        val spills: List<Spill>
    )

    // -- the pass --------------------------------------------------------------------------------

    private fun repair(text: String): String {
        val tokens = JavaText.tokenize(text)
        val classNames = classNames(tokens)
        if (classNames.isEmpty()) return text

        val edits = mutableListOf<Edit>()
        for (constructor in constructors(tokens, classNames)) {
            val statements = statementStarts(tokens, constructor)
            val delegation = delegation(tokens, constructor, statements) ?: continue
            edits += editsFor(tokens, text, constructor, statements, delegation) ?: continue
        }
        if (edits.isEmpty()) return text

        // Every edit belongs to a different constructor body and no two of them overlap, so splicing
        // right-to-left keeps the offsets valid.
        var result = text
        for (edit in edits.sortedByDescending { it.start }) {
            result = result.substring(0, edit.start) + edit.replacement + result.substring(edit.end)
        }
        return result
    }

    /**
     * The one delegating statement of [constructor]'s body, together with the local it references
     * before the declaration this transform inlines, or null when the body does not carry exactly
     * one provable shape.
     *
     * The delegation may be Java's own first statement or sit after the statements that declare and
     * check the local: FernFlower emits the compiler's order (`varN = <expr>; check; this(varN);`),
     * which javac rejects with "call to this must be first statement in constructor", and
     * [ConstructorNormalizer] hoists the delegation to produce the shape the `k2j-failures` dump
     * shows (`this(varN); varN = <expr>; check;`) — which javac rejects with "cannot find symbol".
     * Both orders are the same defect and both are repaired here, so the chain order cannot matter.
     */
    private fun delegation(tokens: List<Token>, constructor: Constructor, statements: List<Int>): Delegation? {
        val statements1 = statements.filter { isDelegatingStatement(tokens, constructor, it) }
        if (statements1.size != 1) return null
        val statement = statements1.single()
        val open = JavaText.nextSignificant(tokens, statement + 1, constructor.bodyClose) ?: return null
        val close = JavaText.matchingParen(tokens, open) ?: return null
        if (close >= constructor.bodyClose) return null
        val semicolon = JavaText.nextSignificant(tokens, close + 1, constructor.bodyClose) ?: return null
        if (tokens[semicolon].text != ";") return null

        val parameters = parameterNames(tokens, constructor)
        val proven = mutableListOf<Spill>()
        val seen = mutableSetOf<String>()
        for (i in (open + 1) until close) {
            val token = tokens[i]
            if (token.kind != Kind.IDENT || JavaText.isKeyword(token.text)) continue
            if (token.text in parameters || token.text in seen) continue
            if (!isBareReference(tokens, i, open, close)) continue
            // Inlining an expression twice would duplicate its side effects; refuse two occurrences.
            if (occurrences(tokens, open, close, token.text) != 1) continue
            val declaration = declarationFor(tokens, constructor, token.text) ?: continue
            proven += Spill(token.text, i, declaration)
            seen += token.text
        }
        if (proven.isEmpty()) return null
        return Delegation(statement, semicolon, proven)
    }

    /** True when the token at [start] really opens a `this(...);` / `super(...);` statement. */
    private fun isDelegatingStatement(tokens: List<Token>, constructor: Constructor, start: Int): Boolean {
        val token = tokens[start]
        if (token.kind != Kind.IDENT || (token.text != "this" && token.text != "super")) return false
        val open = JavaText.nextSignificant(tokens, start + 1, constructor.bodyClose) ?: return false
        if (tokens[open].text != "(") return false
        val close = JavaText.matchingParen(tokens, open) ?: return false
        if (close >= constructor.bodyClose) return false
        val semicolon = JavaText.nextSignificant(tokens, close + 1, constructor.bodyClose) ?: return false
        return tokens[semicolon].text == ";"
    }

    /**
     * True when the token at [index] is a whole argument in itself — between `(`/`,` and `,`/`)` —
     * so inlining an expression in its place cannot change how the argument list parses or how the
     * surrounding expression evaluates.
     */
    private fun isBareReference(tokens: List<Token>, index: Int, open: Int, close: Int): Boolean {
        var previous = JavaText.previousSignificant(tokens, index) ?: return false
        if (previous >= open && tokens[previous].text == ")") {
            val castOpen = matchingOpenParen(tokens, previous) ?: return false
            if (castOpen <= open || !looksLikeCastType(tokens, castOpen + 1, previous)) return false
            previous = JavaText.previousSignificant(tokens, castOpen) ?: return false
        }
        if (previous < open || (tokens[previous].text != "(" && tokens[previous].text != ",")) return false
        val next = JavaText.nextSignificant(tokens, index + 1, close + 1) ?: return false
        return tokens[next].text == "," || tokens[next].text == ")"
    }

    private fun matchingOpenParen(tokens: List<Token>, close: Int): Int? {
        var depth = 0
        for (i in close downTo 0) {
            when (tokens[i].text) {
                ")" -> depth++
                "(" -> {
                    depth--
                    if (depth == 0) return i
                }
            }
        }
        return null
    }

    private fun looksLikeCastType(tokens: List<Token>, from: Int, to: Int): Boolean {
        if (from >= to) return false
        val allowedPunctuation = setOf(".", "<", ">", "?", "[", "]", ",", "&")
        return (from until to).all { i ->
            tokens[i].kind == Kind.IDENT || tokens[i].text in allowedPunctuation
        }
    }

    /** How many times the identifier [text] occurs in the argument list `[open, close)`. */
    private fun occurrences(tokens: List<Token>, open: Int, close: Int, text: String): Int {
        var count = 0
        for (i in (open + 1) until close) {
            if (tokens[i].kind == Kind.IDENT && tokens[i].text == text) count++
        }
        return count
    }

    /** Every identifier the constructor's parameter list mentions. */
    private fun parameterNames(tokens: List<Token>, constructor: Constructor): Set<String> {
        val names = mutableSetOf<String>()
        for (i in (constructor.paramOpen + 1) until constructor.paramClose) {
            if (tokens[i].kind == Kind.IDENT) names += tokens[i].text
        }
        return names
    }

    /**
     * The one `<Type> <ident> = <expr>;` statement in [constructor]'s body that declares [ident] at
     * body level, or null when the body does not declare it exactly once. The declaration may sit
     * before or after the delegation: FernFlower's own order puts it first, the hoisted order puts it
     * last, and both are repaired.
     */
    private fun declarationFor(tokens: List<Token>, constructor: Constructor, ident: String): Declaration? {
        val found = mutableListOf<Declaration>()
        for (i in (constructor.bodyOpen + 1) until constructor.bodyClose) {
            if (tokens[i].kind != Kind.IDENT || tokens[i].text != ident) continue
            declarationAt(tokens, constructor, i)?.let { found += it }
        }
        return if (found.size == 1) found.single() else null
    }

    /**
     * The declaration statement whose name is the token at [nameIndex], or null when that range is
     * not a simple, unannotated, single-declarator `<Type> <ident> = <expr>;` sitting directly in
     * [constructor]'s body. Every failing condition is a refusal, never a guess.
     */
    private fun declarationAt(tokens: List<Token>, constructor: Constructor, nameIndex: Int): Declaration? {
        val equal = JavaText.nextSignificant(tokens, nameIndex + 1, constructor.bodyClose) ?: return null
        if (tokens[equal].text != "=") return null
        val start = typeStart(tokens, nameIndex) ?: return null
        // The statement must begin where the type begins: a preceding `final`, annotation or any
        // other token means the declaration is not the plain shape this transform proves.
        val before = JavaText.previousSignificant(tokens, start) ?: return null
        if (tokens[before].text != "{" && tokens[before].text != ";" && tokens[before].text != "}") return null
        // ...and it must sit in the constructor body itself, not in an `if` or any nested block.
        if (JavaText.enclosingBlockOpen(tokens, start) != constructor.bodyOpen) return null
        val semicolon = statementEnd(tokens, equal, constructor.bodyClose) ?: return null
        val expressionStart = JavaText.nextSignificant(tokens, equal + 1, semicolon) ?: return null
        val expressionEnd = JavaText.previousSignificant(tokens, semicolon) ?: return null
        if (expressionEnd < expressionStart) return null
        // A second declarator (or a second top-level expression) would make `<expr>` ambiguous.
        if (hasTopLevelComma(tokens, expressionStart, semicolon)) return null
        // The inlined expression must not escape the delegation: `this`/`super` are not usable in a
        // delegating call's arguments, so a body carrying them is not this shape.
        for (k in expressionStart..expressionEnd) {
            if (tokens[k].kind == Kind.IDENT && (tokens[k].text == "this" || tokens[k].text == "super")) return null
        }
        return Declaration(nameIndex, start, semicolon, expressionStart, expressionEnd)
    }

    /**
     * The token index where every top-level statement of [constructor]'s body starts, in order. A
     * token starts a statement when it sits at bracket depth zero inside the body and follows the
     * body's `{`, a `;`, or a `}` that closed a nested block — and is not itself a bracket that
     * merely closes an expression (`}` of an array initializer, `)`, `]`) or a `;` that ends one.
     * Continuations of a compound statement (`else`, the `while` of a `do`, `catch`, `finally`) are
     * reported as starts when they follow a `}`; that only makes the ordering proof stricter, which
     * is the safe direction.
     */
    private fun statementStarts(tokens: List<Token>, constructor: Constructor): List<Int> {
        val starts = mutableListOf<Int>()
        var depth = 0
        var previous: Int? = null
        var i = constructor.bodyOpen + 1
        while (i < constructor.bodyClose) {
            val token = tokens[i]
            if (token.kind == Kind.COMMENT) {
                i++
                continue
            }
            if (depth == 0 && atStatementStart(tokens, previous) && !merelyCloses(token.text)) starts += i
            when (token.text) {
                "(", "[", "{" -> depth++
                ")", "]", "}" -> if (depth > 0) depth--
            }
            previous = i
            i++
        }
        return starts
    }

    /** True when the token can only be the tail of the statement before it, never a new statement. */
    private fun merelyCloses(text: String): Boolean =
        text == ";" || text == "}" || text == ")" || text == "]" || text == "," || text == "."

    /** True when the token last seen before a statement can only be the end of the previous one. */
    private fun atStatementStart(tokens: List<Token>, previous: Int?): Boolean {
        if (previous == null) return true
        return tokens[previous].text == ";" || tokens[previous].text == "}"
    }

    /** Index of the `;` that ends the statement beginning at [from], or null when unbalanced. */
    private fun statementEnd(tokens: List<Token>, from: Int, limit: Int): Int? {
        var depth = 0
        var i = from
        while (i < limit) {
            when (tokens[i].text) {
                "(", "[", "{" -> depth++
                ")", "]", "}" -> {
                    if (depth == 0) return null
                    depth--
                }
                ";" -> if (depth == 0) return i
            }
            i++
        }
        return null
    }

    /**
     * True when a `,` at bracket depth zero appears in the token range — the signature of a second
     * declarator or a second top-level expression. Angle brackets are deliberately not counted, so
     * a comma inside `<...>` is reported too: over-refusing is safe, missing a declarator is not.
     */
    private fun hasTopLevelComma(tokens: List<Token>, from: Int, to: Int): Boolean {
        var depth = 0
        for (i in from until to) {
            when (tokens[i].text) {
                "(", "[", "{" -> depth++
                ")", "]", "}" -> if (depth > 0) depth--
                "," -> if (depth == 0) return true
            }
        }
        return false
    }

    // -- the rewrite ------------------------------------------------------------------------------

    /**
     * The splices that repair [delegation], or null when the shape is not proven: the local is
     * referenced elsewhere in the body, or the initializer depends on a local the body declares
     * later, or a statement with an effect of its own precedes the delegation (which would change
     * when the inlined expression runs), or the local's declaration/check statements are not the
     * plain shape [declarationAt] / [nullCheck] prove.
     */
    private fun editsFor(
        tokens: List<Token>,
        text: String,
        constructor: Constructor,
        statements: List<Int>,
        delegation: Delegation
    ): List<Edit>? {
        val checksBySpill = linkedMapOf<Spill, List<Check>>()
        for (spill in delegation.spills) {
            val checks = statements.mapNotNull { nullCheck(tokens, constructor, it, spill.ident) }
            if (referencedElsewhere(tokens, constructor, spill, checks)) return null
            if (dependsOnLaterLocal(tokens, constructor, spill.declaration)) return null
            checksBySpill[spill] = checks
        }

        // Every statement before the delegation must disappear as part of a proven spill. This keeps
        // evaluation at the explicit constructor invocation and preserves Java's first-statement rule.
        val removableStarts = buildSet {
            delegation.spills.forEach { add(it.declaration.statementStart) }
            checksBySpill.values.flatten().forEach { add(it.start) }
        }
        for (statement in statements) {
            if (statement == delegation.statement) break
            if (statement !in removableStarts) return null
        }

        val edits = mutableListOf<Edit>()
        for (spill in delegation.spills) {
            val declaration = spill.declaration
            var expression = text.substring(
                tokens[declaration.expressionStart].start,
                tokens[declaration.expressionEnd].end
            )
            val checks = checksBySpill.getValue(spill)
            // Kotlin emitted the null check before the constructor call. Preserve that behavior in an
            // expression-capable form; fully qualify Objects so the splice needs no import edit.
            if (checks.size > 1) return null
            checks.singleOrNull()?.let { check ->
                expression = "java.util.Objects.requireNonNull($expression, ${check.message})"
            }
            val reference = tokens[spill.reference]
            edits += Edit(reference.start, reference.end, expression)
            edits += Edit(tokens[declaration.statementStart].start, tokens[declaration.semicolon].end, "")
            checks.forEach { check ->
                edits += Edit(tokens[check.start].start, tokens[check.semicolon].end, "")
            }
        }
        return edits
    }

    /**
     * The statement at token [start] as a `Intrinsics.checkNotNull*` call on [ident], or null. The
     * call must be a direct statement of the constructor body, the local must be its first argument
     * and every remaining argument must be a literal — so the statement's entire effect is checking
     * that one local, and deleting it can delete nothing else.
     */
    private fun nullCheck(tokens: List<Token>, constructor: Constructor, start: Int, ident: String): Check? {
        if (tokens[start].kind != Kind.IDENT) return null
        var name = start
        var qualifier: Int? = null
        while (true) {
            val dot = JavaText.nextSignificant(tokens, name + 1, constructor.bodyClose) ?: return null
            if (tokens[dot].text != ".") break
            qualifier = name
            val next = JavaText.nextSignificant(tokens, dot + 1, constructor.bodyClose) ?: return null
            if (tokens[next].kind != Kind.IDENT) return null
            name = next
        }
        if (tokens[name].text !in NULL_CHECKS) return null
        if (qualifier == null || tokens[qualifier].text != "Intrinsics") return null
        val open = JavaText.nextSignificant(tokens, name + 1, constructor.bodyClose) ?: return null
        if (tokens[open].text != "(") return null
        val close = JavaText.matchingParen(tokens, open) ?: return null
        if (close >= constructor.bodyClose) return null
        val semicolon = JavaText.nextSignificant(tokens, close + 1, constructor.bodyClose) ?: return null
        if (tokens[semicolon].text != ";") return null
        if (JavaText.enclosingBlockOpen(tokens, start) != constructor.bodyOpen) return null
        val argument = JavaText.nextSignificant(tokens, open + 1, close) ?: return null
        if (tokens[argument].kind != Kind.IDENT || tokens[argument].text != ident) return null
        val comma = JavaText.nextSignificant(tokens, argument + 1, close) ?: return null
        if (tokens[comma].text != ",") return null
        val message = JavaText.nextSignificant(tokens, comma + 1, close) ?: return null
        if (tokens[message].kind != Kind.LITERAL) return null
        if (JavaText.nextSignificant(tokens, message + 1, close) != null) return null
        return Check(start, semicolon, tokens[message].text)
    }

    /** True when [ident] is referenced in the body anywhere but the reference and the deletions. */
    private fun referencedElsewhere(
        tokens: List<Token>,
        constructor: Constructor,
        spill: Spill,
        checks: List<Check>
    ): Boolean {
        for (i in (constructor.bodyOpen + 1) until constructor.bodyClose) {
            if (tokens[i].kind != Kind.IDENT || tokens[i].text != spill.ident) continue
            if (i == spill.reference || i == spill.declaration.nameIndex) continue
            if (checks.any { i > it.start && i < it.semicolon }) continue
            return true
        }
        return false
    }

    /**
     * True when the initializer mentions a name the body declares as a local *after* the
     * declaration — the inlined expression is evaluated where such a name is not in scope, so the
     * shape is not provably equivalent. The set is collected loosely (any assignment-looking
     * identifier counts): over-collecting can only make the transform refuse.
     */
    private fun dependsOnLaterLocal(tokens: List<Token>, constructor: Constructor, declaration: Declaration): Boolean {
        val later = mutableSetOf<String>()
        for (i in (declaration.semicolon + 1) until constructor.bodyClose) {
            if (tokens[i].kind != Kind.IDENT) continue
            val assigned = JavaText.nextSignificant(tokens, i + 1, constructor.bodyClose) ?: continue
            if (tokens[assigned].text != "=") continue
            val declared = JavaText.previousSignificant(tokens, i) ?: continue
            if (!declaresType(tokens, declared)) continue
            later += tokens[i].text
        }
        if (later.isEmpty()) return false
        for (k in declaration.expressionStart..declaration.expressionEnd) {
            if (tokens[k].kind == Kind.IDENT && tokens[k].text in later) return true
        }
        return false
    }

    /** True when the token at [index] could be the last token of a declared type. */
    private fun declaresType(tokens: List<Token>, index: Int): Boolean {
        val token = tokens[index]
        if (token.kind != Kind.IDENT) return token.text == "]" || token.text == ">"
        return !JavaText.isKeyword(token.text)
    }

    // -- declarations of the unit -------------------------------------------------------------------

    /** Every constructor of the unit whose body braces match: the parameter list and the body. */
    private fun constructors(tokens: List<Token>, classNames: Set<String>): List<Constructor> {
        val out = mutableListOf<Constructor>()
        for (i in tokens.indices) {
            val token = tokens[i]
            if (token.kind != Kind.IDENT || token.text !in classNames) continue
            val previous = JavaText.previousSignificant(tokens, i)
            // A constructor has no return type: only a modifier, or the start/end of a class body.
            if (previous != null && tokens[previous].text !in CONSTRUCTOR_PREFIXES) continue
            val open = JavaText.nextSignificant(tokens, i + 1) ?: continue
            if (tokens[open].text != "(") continue
            val close = JavaText.matchingParen(tokens, open) ?: continue
            val body = JavaText.nextSignificant(tokens, close + 1) ?: continue
            if (tokens[body].text != "{") continue
            val end = JavaText.matchingBrace(tokens, body) ?: continue
            out += Constructor(open, close, body, end)
        }
        return out
    }

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

    /** Token index where the type of the declaration named at [nameIndex] begins, or null. */
    private fun typeStart(tokens: List<Token>, nameIndex: Int): Int? {
        var i = JavaText.previousSignificant(tokens, nameIndex) ?: return null
        // A trailing array declarator (`UUID[] var1`) belongs to the type.
        while (tokens[i].text == "]") {
            val bracket = JavaText.previousSignificant(tokens, i) ?: return null
            if (tokens[bracket].text != "[") return null
            i = JavaText.previousSignificant(tokens, bracket) ?: return null
        }
        val last = tokens[i]
        if (last.text == ">") {
            val open = matchingAngle(tokens, i) ?: return null
            val name = JavaText.previousSignificant(tokens, open) ?: return null
            if (tokens[name].kind != Kind.IDENT || JavaText.isKeyword(tokens[name].text)) return null
            i = name
        } else if (last.kind != Kind.IDENT || JavaText.isKeyword(last.text)) {
            return null
        }
        // A qualified name (`java.util.UUID`) is one type, not a type followed by an access.
        while (true) {
            val dot = JavaText.previousSignificant(tokens, i) ?: return i
            if (tokens[dot].text != ".") return i
            val name = JavaText.previousSignificant(tokens, dot) ?: return null
            if (tokens[name].kind != Kind.IDENT || JavaText.isKeyword(tokens[name].text)) return null
            i = name
        }
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
}
