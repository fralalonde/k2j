package accept.rawtypes

/**
 * A generic interface whose member's type *stays generic* after substitution (`List<T>`), which is
 * what makes the two supertypes of [ConflictingProvider] incompatible in Java while Kotlin accepts
 * them.
 */
interface Provider<T> {
    fun provide(): List<T>
}

/** A second, otherwise identical interface — the two type arguments conflict in [ConflictingProvider]. */
interface OtherProvider<T> {
    fun provide(): List<T>
}

/**
 * Shape (b) — incompatible supertypes. `Provider<Entry>` and `OtherProvider<Item>` are named **with
 * their type arguments** in this unit's own `implements` clause, and their members disagree:
 * Kotlin's `List<out E>` is covariant, so `List<Item>` is a valid override of `List<Entry>` and the
 * Kotlin class compiles; Java's `List<E>` is invariant, so javac says
 *
 * ```
 * ConflictingProvider.java:3:53: types OtherProvider<accept.rawtypes.Item> and
 *     Provider<accept.rawtypes.Entry> are incompatible;
 *   both define provide(), but with unrelated return types
 * ```
 *
 * Making the two *named* supertypes raw — and nothing else — makes both members erase to
 * `List provide()`, which *is* override-equivalent, so javac accepts the unit.
 */
abstract class ConflictingProvider : Provider<Entry>, OtherProvider<Item>
