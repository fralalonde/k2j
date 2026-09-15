package accept.defaultimpls

interface ParentDefaults {
    val label: String
        get() = "parent"
}

interface ChildDefaults : ParentDefaults {
    override val label: String
        get() = super.label
}
