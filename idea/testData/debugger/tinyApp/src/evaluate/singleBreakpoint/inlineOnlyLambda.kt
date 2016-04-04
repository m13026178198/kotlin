package inlineOnlyLambda

fun main(args: Array<String>) {
    // EXPRESSION: it + 1
    // RESULT: 12: I
    //Breakpoint! (lambdaOrdinal = 1)
    foo { it -> 1 }
}

@Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE")
@kotlin.internal.InlineOnly
inline fun foo(s: (Int) -> Unit) {
    val x = 1
    s(11)
    val y = 1
}
