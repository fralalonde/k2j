package accept.objects

/** Singleton object with mutable state. */
object Registry {
    private val names: MutableList<String> = mutableListOf()

    fun add(name: String): Int {
        names.add(name)
        return names.size
    }

    fun size(): Int = names.size
}

/** Companion object carrying a const and a factory. */
class WithCompanion private constructor(val tag: String) {
    companion object {
        const val ID: String = "id"

        fun create(): WithCompanion = WithCompanion(ID)
    }
}
