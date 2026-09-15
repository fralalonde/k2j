package accept.defaultargs

import java.util.UUID

/** The interface the id fixture implements, so the unit is a realistic type, not a bare holder. */
interface HasObjectId {
    val objectId: UUID
}

/**
 * The synthetic-local delegation shape, in the exact source form the real module has
 * (`data class ActivityId(override val objectId: UUID) : IObjectId { constructor() : this(UUID.randomUUID()) }`):
 * a secondary no-arg constructor whose **delegation argument is a call on a platform type**.
 *
 * Kotlin emits `ActivityId() { var10001 = UUID.randomUUID(); checkNotNullExpressionValue(var10001, ...);
 * this(var10001); }` — the null check forces the value through a temporary local, and the spill sits
 * *before* the delegation. FernFlower copies the spill and the check but moves `this(...)` to the
 * first statement (the only order Java accepts) and leaves the declaration below it, so the emitted
 * unit reads `public RandomDefaultId() {this(var10001);` with `UUID var10001 = ...;` underneath.
 * It **parses** — an explicit constructor invocation is grammatically valid — and javac rejects it
 * with `cannot find symbol: variable var10001`; only compiling the output can see it
 * (`--compile-check`, or `DefaultArgumentConstructorNormalizerTest`).
 */
data class RandomDefaultId(override val objectId: UUID) : HasObjectId {

    constructor() : this(UUID.randomUUID())

    /** Uses the property so the fixture exercises the real member set (getter, component1, copy). */
    fun hex(): String = objectId.toString()
}

/**
 * The *other* Kotlin shape a default argument can produce, kept as the corpus-level negative
 * control: with Kotlin 2.4.0 a default argument that is a static call is routed through the
 * synthetic `(UUID, int, DefaultConstructorMarker)` constructor — the no-arg one delegates to
 * *that* — so FernFlower emits
 *
 * ```
 * public DefaultArgumentIds(UUID var1, int var2, DefaultConstructorMarker var3) {this(var1);
 *    if ((var2 & 1) != 0) { UUID var10000 = UUID.randomUUID(); ...; var1 = var10000; }
 * }
 * ```
 *
 * Here the referenced identifier **is** a declared parameter and the default is applied *after* the
 * delegation, so the shape is a different (semantic) defect and
 * [org.example.k2j.core.DefaultArgumentConstructorNormalizer] must leave it byte-identical: no
 * declaration to inline, and a transform that guessed would silently change behaviour.
 */
class DefaultArgumentIds(val id: UUID = UUID.randomUUID()) {

    fun hex(): String = id.toString()
}
