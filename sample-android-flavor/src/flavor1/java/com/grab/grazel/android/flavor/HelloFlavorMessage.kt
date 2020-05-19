/*
 * Copyright 2021 Grabtaxi Holdings Pte Ltd (GRAB), All rights reserved.
 *
 * Use of this source code is governed by an MIT-style license that can be found in the LICENSE file
 */

package com.grab.grazel.android.flavor

import android.content.Context

class HelloFlavorMessage {
    fun message(context: Context) = context.getString(R.string.hello_flavor_1)
}