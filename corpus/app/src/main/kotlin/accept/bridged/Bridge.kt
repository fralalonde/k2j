package accept.bridged

interface BridgeBox<T> {
    fun get(): T
}

class BridgeImpl : BridgeBox<String> {
    override fun get(): String = "bridge"
}
