package accept.variance

/**
 * Kotlin's `Map<K, out V>` (declaration-site variance) makes the emitted Java parameter
 * `Map<String, ? extends Number>` while the field comes out invariant `Map<String, Number>`, so
 * `this.properties = properties;` fails javac's wildcard-capture rules. Only compiling the output
 * reveals it — the text parses.
 */
interface Props {
    val properties: Map<String, Number>
}

data class VarianceProps(
    override val properties: Map<String, Number> = emptyMap()
) : Props {
    fun total(): Int = properties.size
}
