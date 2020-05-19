/*
 * Copyright 2021 Grabtaxi Holdings Pte Ltd (GRAB), All rights reserved.
 *
 * Use of this source code is governed by an MIT-style license that can be found in the LICENSE file
 */

package com.grab.grazel.android.sample

import android.content.Intent
import android.os.Bundle
import android.os.Parcelable
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.grab.grazel.android.flavor.FlavorActivity
import com.grab.grazel.sample.HelloWorld
import dagger.Component
import kotlinx.parcelize.Parcelize
import javax.inject.Inject


class SimpleDependency @Inject constructor()

@Component
interface MainActivityComponent {

    fun simpleDependency(): SimpleDependency

    @Component.Factory
    interface Factory {
        fun create(): MainActivityComponent
    }
}

@Parcelize
data class ParcelableClass(val name: String) : Parcelable

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        HelloWorld()
        verifyBuildConfigFields()
        DaggerMainActivityComponent
            .factory()
            .create()
            .simpleDependency()
        findViewById<View>(R.id.text).setOnClickListener {
            val intent = Intent(this, FlavorActivity::class.java)
            startActivity(intent)
        }
        findViewById<TextView>(R.id.text).setText(R.string.generated_value)

        // Assert custom resource set import
        R.string.custom_resource_set
    }

    private fun verifyBuildConfigFields() {
        BuildConfig.SOME_STRING
        BuildConfig.SOME_BOOLEAN
        BuildConfig.SOME_LONG
        BuildConfig.SOME_INT
        BuildConfig.DEBUG
        BuildConfig.VERSION_CODE
        BuildConfig.VERSION_NAME
    }
}