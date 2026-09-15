package accept.defaultimpls

interface CancellationDefaults {
    val cancellationContext: String
        get() = "default"
}

data class CancellationEvent(
    val context: String?
) : CancellationDefaults {
    override val cancellationContext: String
        get() = context ?: super.cancellationContext
}
