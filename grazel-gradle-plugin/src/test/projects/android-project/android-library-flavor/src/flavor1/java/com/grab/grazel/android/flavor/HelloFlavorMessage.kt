package com.grab.grazel.android.flavor

import android.content.Context

class HelloFlavorMessage {
    fun message(context: Context) = context.getString(R.string.hello_flavor_1)
}