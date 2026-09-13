package accept.mixed

class Mixed(val id: Int) {

    class Nested {
        fun tag(): String = "nested"
    }

    inner class Inner {
        fun idText(): String = id.toString()
    }
}

typealias MixedAlias = String
