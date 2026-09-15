package accept.rawtypes

/**
 * The element types of shape (a). [Item] is a subtype of [Entry], which is what makes the narrowed
 * override in [NarrowContext] legal in Kotlin and illegal in Java: Kotlin's `List<out E>` is
 * covariant, Java's `List<E>` is invariant.
 */
interface Entry

interface Item : Entry

/** The wide declaration, exactly like `ItemWorkContext.items` on `target-module`. */
interface WideContext {
    val items: List<Entry>
}

/**
 * Shape (a) — the method clash. The narrowed override is legal in Kotlin and unrepresentable in
 * Java: `List<Item> getItems()` is not return-type-substitutable for `List<Entry> getItems()`, and
 * javac says so in its own words:
 *
 * ```
 * NarrowContext.java:12:24: getItems() in accept.rawtypes.NarrowContext clashes with
 *     getItems() in accept.rawtypes.WideContext
 *   return type java.util.List<accept.rawtypes.Item> is not compatible with
 *     java.util.List<accept.rawtypes.Entry>
 * ```
 *
 * The declaration is *in this unit*, so the return type is the one thing that may go raw.
 */
interface NarrowContext : WideContext {
    override val items: List<Item>
}
