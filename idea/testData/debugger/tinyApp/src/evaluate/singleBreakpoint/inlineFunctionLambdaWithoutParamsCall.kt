package inlineFunctionLambdaWithoutParamsCall

fun main(args: Array<String>) {
    lookAtMe {
        val c = "c"
    }
}

inline fun lookAtMe(f: () -> Unit) {
    val a = "a"
    // EXPRESSION: a
    // RESULT: "a": Ljava/lang/String;
    //Breakpoint!
    f()
    val b = "b"
}