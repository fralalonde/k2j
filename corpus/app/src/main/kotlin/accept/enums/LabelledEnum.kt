package accept.enums

/** Interface with a default method body, implemented by an enum that adds a field. */
interface Labelled {
    fun label(): String = "default"
}

enum class LabelledEnum(val id: Int) : Labelled {
    X(1),
    Y(2)
}
