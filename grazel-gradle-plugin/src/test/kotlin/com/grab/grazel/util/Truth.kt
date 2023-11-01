package com.grab.grazel.util

import com.google.common.truth.IterableSubject
import com.google.common.truth.MapSubject
import com.google.common.truth.StringSubject
import com.google.common.truth.Truth

inline fun Collection<*>.truth(
    assertions: IterableSubject.() -> Unit = {}
): IterableSubject = Truth.assertThat(this).apply(assertions)

inline fun Map<*, *>.truth(
    assertions: MapSubject.() -> Unit = {}
): MapSubject = Truth.assertThat(this).apply(assertions)

fun String.truth(block: StringSubject.() -> Unit = {}): StringSubject =
    Truth.assertThat(this).apply(block)