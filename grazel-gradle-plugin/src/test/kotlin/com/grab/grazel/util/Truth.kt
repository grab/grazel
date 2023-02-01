package com.grab.grazel.util

import com.google.common.truth.IterableSubject
import com.google.common.truth.Truth

fun Collection<*>.truth(): IterableSubject = Truth.assertThat(this)