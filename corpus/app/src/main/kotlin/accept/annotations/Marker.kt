package accept.annotations

/** Kotlin annotation class: must come out as a Java @interface. */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
annotation class Marker(val value: String)
