package kotlin

//
// NOTE THIS FILE IS AUTO-GENERATED by the GenerateStandardLib.kt
// See: https://github.com/JetBrains/kotlin/tree/master/libraries/stdlib
//
// Generated from input file: JUtilCollectionsJVM.kt
//


import java.util.*

//
// This file contains methods which are optimised for working on Collection / Array collections where the size
// could be used to implement a more optimal solution
//
// See [[GenerateStandardLib.kt]] for more details
//

/**
 * Returns a new List containing the results of applying the given *transform* function to each element in this collection
 *
 * @includeFunctionBody ../../test/CollectionTest.kt map
 */
public inline fun <R> IntArray.map(transform : (Int) -> R) : List<R> {
    return mapTo(java.util.ArrayList<R>(this.size), transform)
}
