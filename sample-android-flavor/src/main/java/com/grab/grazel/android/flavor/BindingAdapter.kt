/*
 * Copyright 2021 Grabtaxi Holdings Pte Ltd (GRAB), All rights reserved.
 *
 * Use of this source code is governed by an MIT-style license that can be found in the LICENSE file
 */

package com.grab.grazel.android.flavor

import android.widget.TextView
import androidx.databinding.BindingAdapter

data class ViewModel(val text: String)

@BindingAdapter("text")
fun viewModelText(view: TextView, viewModel: ViewModel) {
    view.text = viewModel.text
}