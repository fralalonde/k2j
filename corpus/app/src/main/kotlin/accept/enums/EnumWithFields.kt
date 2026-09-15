package accept.enums

interface HasAlias {
    val alias: String
    val parent: String
}

/**
 * Enum with constructor parameters stored in instance
 * fields, implementing an interface. FernFlower emits the fields before the constant list, which
 * is illegal Java, and references `$VALUES` without declaring it.
 */
enum class EnumWithFields(
    override val alias: String,
    val type: Class<out CharSequence>
) : HasAlias {
    ALPHA("alpha", String::class.java),
    BETA("beta", StringBuilder::class.java);

    override val parent: String
        get() = "root"
}
