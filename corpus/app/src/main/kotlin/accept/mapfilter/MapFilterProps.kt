package accept.mapfilter

/**
 * The inlined `Map.filter` shape that broke `com.example.app.properties.Properties` on
 * `target module`: a data class whose `equals` compares two filtered maps.
 *
 * Kotlin writes **no** `LocalVariableTypeTable`, so the local that the inlined `filter` body binds
 * the receiver to (`$this$filter$iv`) reaches FernFlower with only the erased `Ljava/util/Map;`
 * signature, while the loop variable of the same body (`element$iv$iv`) was written with
 * `Ljava/util/Map$Entry;`. FernFlower renders both faithfully, and the two renderings do not compose:
 *
 * ```java
 * Map $this$filter$iv = this.getProperties();              // erased signature: raw Map
 * for(Map.Entry element$iv$iv : $this$filter$iv.entrySet()) {   // typed signature: Map.Entry
 * ```
 *
 * The unit *parses*, so the parse gate passes it, and javac then rejects the enhanced `for` with
 * `incompatible types: java.lang.Object cannot be converted to java.util.Map.Entry` — the real
 * `Properties.java` failed at `:56:64` and `:69:64` with exactly that, was never written, and left 16
 * callers failing with `no suitable constructor found for Properties(Map,int,...)`.
 *
 * The fixture mirrors the real unit member for member (interface property, defaulted constructor
 * parameter, `equals` over `filter`ed maps, `hashCode`) so the inlined body FernFlower sees is the
 * same one, including the `data class` members (`component1`, `copy`, `copy$default`).
 */
interface MapFilterContract {
    val properties: Map<MapFilterKey, MapFilterValue>
}

interface MapFilterKey

interface MapFilterValue {
    val value: Any?

    fun isEmpty(): Boolean
}

data class MapFilterProps(
        override val properties: Map<MapFilterKey, MapFilterValue> = emptyMap()
) : MapFilterContract {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is MapFilterProps) return false
        return this.properties.filter { e -> !e.value.isEmpty() } ==
                other.properties.filter { e -> !e.value.isEmpty() }
    }

    override fun hashCode(): Int {
        return properties.hashCode()
    }
}
