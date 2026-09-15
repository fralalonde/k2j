package accept.dataclasses

/** Data class with default arguments and a body: exercises copy, copy$default and componentN. */
data class DataWithDefaults(
    val name: String = "x",
    val count: Int = 0,
    val tags: List<String> = emptyList()
) {
    fun describe(): String = "$name:$count"
}
