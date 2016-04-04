// FILE: 1.kt
//WITH_RUNTIME

package test

@Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE")
@kotlin.internal.InlineOnly
inline fun foo(s: (Int) -> Unit) {
    val x = 1
    s(11)
    val y = 1
}

inline fun foo2(s: (Int) -> Unit) {
    foo(s)
}

// FILE: 2.kt

import test.*

fun box(): String {
    var res = "fail"
    foo2 {it-> res = "OK" }
    return res
}

// FILE: 1.smap

SMAP
1.kt
Kotlin
*S Kotlin
*F
+ 1 1.kt
test/_1Kt
*L
1#1,18:1
*E

// FILE: 2.smap

SMAP
2.kt
Kotlin
*S Kotlin
*F
+ 1 2.kt
_2Kt
+ 2 1.kt
test/_1Kt
*L
1#1,11:1
15#2,2:12
*E