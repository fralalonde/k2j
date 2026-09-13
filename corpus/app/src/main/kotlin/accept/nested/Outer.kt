package accept.nested

class Outer(val label: String) {

    class Nested {
        fun ping(): Int = 1
    }

    inner class Inner {
        fun outerLabel(): String = label
    }

    fun makeLocal(): Int {
        class Local(val v: Int) {
            fun twice(): Int = v * 2
        }
        return Local(21).twice()
    }

    fun makeAnon(): Runnable = object : Runnable {
        override fun run() {}
    }
}
