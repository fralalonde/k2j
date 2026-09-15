package accept.defaultargs

interface SpillSource {
    val first: String
    val second: String
    val untyped: Any
}

data class MultipleSpillTarget(
    val first: String,
    val second: String,
    val typed: String,
    val count: Int
) {
    constructor(source: SpillSource, count: Int) : this(
        source.first,
        source.second,
        source.untyped as String,
        count
    )
}
