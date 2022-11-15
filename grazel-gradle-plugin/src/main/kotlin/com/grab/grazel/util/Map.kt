package com.grab.grazel.util

/***
 * Merge of collection of `Maps` into a single map by using `merger` function to merge values
 * for keys that are same.
 */
fun <K, V> Iterable<Map<K, V>>.merge(merger: (prev: V, next: V) -> V): Map<K, V> {
    return fold(mutableMapOf()) { acc, map ->
        map.forEach { (key, value) ->
            if (acc.containsKey(key)) {
                acc[key] = merger(acc.getValue(key), value)
            } else {
                acc[key] = value
            }
        }
        acc
    }
}