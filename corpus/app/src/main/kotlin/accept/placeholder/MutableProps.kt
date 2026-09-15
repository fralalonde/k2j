package accept.placeholder

/**
 * The property-setter shape that broke on `target module` (`SearchValue$Multiple`, `IPaginatedSearchCriterias`
 * and 221 more failed units): Kotlin's compiler writes **no name** for a `var` property's setter
 * parameter into the class file. The setter's `LocalVariableTable` carries the literal `<set-?>` and
 * the string constant the compiler hands to `Intrinsics.checkNotNullParameter` carries `<set-?>` too,
 * so FernFlower reproduces the placeholder in the generated Java:
 *
 * ```java
 * public final void setValues(@NotNull List<String> <set-?>) {
 *    Intrinsics.checkNotNullParameter(<set-?>, "<set-?>");
 *    this.values = <set-?>;
 * }
 * ```
 *
 * javac rejects that with `<identifier> expected` / `> expected`, so the unit never parses and
 * `--compile-check` is not even reached.
 *
 * The fixtures cover the four occurrences the real dumps contain: a bodyless interface declaration
 * (the shape of `IPaginatedSearchCriterias.setPageable`), a non-null parameter whose
 * `Intrinsics.checkNotNullParameter` message is the placeholder, a nullable parameter (no `Intrinsics`
 * call at all) and a primitive parameter (no `Intrinsics` call either), all in one unit — plus a
 * second class body, so one unit carries several placeholder methods.
 */
interface MutableLabel {
    var label: String
}

class MutableProps : MutableLabel {
    override var label: String = ""

    var values: List<String> = emptyList()

    var name: String? = null

    var count: Int = 0
}

class MutableHolder {
    var flag: Boolean = false

    class Nested {
        var values: List<String> = emptyList()
    }
}
