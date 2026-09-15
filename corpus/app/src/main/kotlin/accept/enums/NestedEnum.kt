package accept.enums

/** A nested enum: the constant list must be hoisted inside the nested type, not the outer one. */
class EnumHolder {
    enum class Inner(val code: Int) {
        A(1),
        B(2);
    }
}
