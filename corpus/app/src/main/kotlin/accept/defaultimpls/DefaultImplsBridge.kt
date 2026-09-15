package accept.defaultimpls

/**
 * The shape that produced the largest defect family on `target module`: a Kotlin class inheriting an
 * interface *default* through a sub-interface, over a member type the sub-interface narrowed.
 *
 * Kotlin's `List<out E>` is covariant, so `Aware` may override `Context.items: List<Entry>` with
 * `items: List<Item>` where `Item : Entry`. Java's generics are invariant, so `List<Item>` is *not* a
 * subtype of `List<Entry>` and javac does not read that as an override — the abstract declaration in
 * `Context` stays unimplemented. Kotlin bridges the difference with an explicit delegating method in
 * every class that inherits the member (`getItems()` → `Aware.super.getItems()`), and FernFlower
 * removes that member because it is a bridge, so the generated class no longer satisfies its interface:
 *
 * ```
 * BothBranches.java:8:8: BothBranches is not abstract and does not override abstract method
 *     getItems() in Context
 * ```
 *
 * The fixtures below are the axes `InterfaceDefaultSynthesis` has to hold apart: one inherited default
 * (`BothBranches`), two independent chains on one class (`MultiInherited`), a method with a parameter
 * and a non-void return (`describe`), a nested parameter type (`BoundImpl`), a class that already
 * declares the member (`DeclaringClass`, which must be left alone), a default the superclass provides
 * (`SubclassOfProvider`, likewise left alone — an override delegating to the interface there would
 * bypass the superclass), and a generic default (`GenImpl`, whose type variable makes the pass refuse
 * rather than guess).
 */

interface Entry

interface Item : Entry

interface Context {
    val items: List<Entry>

    fun describe(prefix: String): String
}

interface Aware : Context {
    override val items: List<Item> get() = emptyList()

    override fun describe(prefix: String): String = "$prefix-aware"
}

interface StartedEvent : Aware

interface OtherEvent : Context

/** One delegating member per inherited default, none of which this class declares. */
class BothBranches : StartedEvent, OtherEvent

/** Two independent chains on one class: each inherits a default javac cannot see through. */
interface Tags {
    val tags: List<Entry>
}

interface Tagged : Tags {
    override val tags: List<Item> get() = emptyList()
}

interface TagEvent : Tagged

class MultiInherited : StartedEvent, TagEvent

/** A parameter whose type is nested: the pass has to spell `Binder.Marker`, not `Binder$Marker`. */
interface Binder {
    fun bind(marker: Marker): List<Entry>

    interface Marker
}

interface BoundAware : Binder {
    override fun bind(marker: Binder.Marker): List<Entry> = emptyList()
}

interface BoundEvent : BoundAware

interface BoundOther : Binder

class BoundImpl : BoundEvent, BoundOther

/** Declares both members itself: the pass must find nothing to append. */
class DeclaringClass : StartedEvent {
    override val items: List<Item> get() = emptyList()

    override fun describe(prefix: String): String = prefix
}

/** The default is provided by the superclass: an override in the subclass would bypass it. */
open class ProvidingBase : StartedEvent {
    override val items: List<Item> get() = emptyList()
}

class SubclassOfProvider : ProvidingBase()

/** A generic default: its `Signature` carries a type variable the pass cannot render as written. */
interface Generator {
    fun <T> generate(factory: Factory<T>): T = factory.create()

    interface Factory<T> {
        fun create(): T
    }
}

interface GenChain : Generator

class GenImpl : GenChain
