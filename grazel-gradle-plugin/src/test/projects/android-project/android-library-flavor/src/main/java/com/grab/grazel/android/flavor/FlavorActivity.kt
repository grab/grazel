package com.grab.grazel.android.flavor

import android.app.Activity
import android.os.Bundle
import android.widget.TextView

class FlavorActivity: Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_flavor)
        findViewById<TextView>(R.id.text).text = HelloFlavorMessage().message(this)
        findViewById<TextView>(R.id.text2).text = "With dep from ${ModuleName().name()}"
    }
}