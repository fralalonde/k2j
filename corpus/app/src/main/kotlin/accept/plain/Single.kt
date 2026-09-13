package accept.plain

class Single(val value: Int) {
    fun answer(): Int = value + 1
}

interface SingleContract {
    fun label(): String
}
