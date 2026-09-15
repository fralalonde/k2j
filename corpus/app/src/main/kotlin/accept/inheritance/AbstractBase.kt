package accept.inheritance

/** Abstract class with a protected constructor property and an open method. */
abstract class AbstractBase(protected val root: String) {
    abstract fun render(): String

    open fun describe(): String = "base:$root"
}

class Concrete(root: String) : AbstractBase(root) {
    override fun render(): String = describe()
}
