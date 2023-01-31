package com.grab.grazel.util

fun <T, C : MutableCollection<in T>> Iterable<T>.addTo(
    destination: C,
): C {
    for (item in this) destination.add(item)
    return destination
}