/*
 * Copyright 2021 Grabtaxi Holdings Pte Ltd (GRAB), All rights reserved.
 *
 * Use of this source code is governed by an MIT-style license that can be found in the LICENSE file
 */

package com.grab.grazel.android.flavor

import android.app.Activity
import android.os.Bundle
import android.widget.TextView
import androidx.databinding.DataBindingUtil
import com.grab.grazel.android.flavor.databinding.ActivityFlavorBinding

class FlavorActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val binding = DataBindingUtil.setContentView<ActivityFlavorBinding>(this, R.layout.activity_flavor)
        findViewById<TextView>(R.id.text).text = HelloFlavorMessage().message(this)
        findViewById<TextView>(R.id.text2).text = "With dep from ${ModuleName().name()}"
        binding.viewModel = ViewModel("Text set from Binding adapter")
    }
}