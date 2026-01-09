/*
 * Copyright 2022 Grabtaxi Holdings PTE LTD (GRAB)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.grab.grazel.android.sample

import android.content.Intent
import android.os.Bundle
import android.os.Parcelable
import android.util.Log
import android.widget.Button
import android.widget.TextView
import androidx.activity.ComponentActivity
import com.grab.grazel.android.flavor.FlavorActivity
import com.grab.grazel.sample.HelloWorld
import com.squareup.moshi.Moshi
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

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        Log.d("tag","MainActivity OnCreate")
        HelloWorld()
        verifyBuildConfigFields()
        verifyKspCodeGeneration()

        DaggerMainActivityComponent
            .factory()
            .create()
            .simpleDependency()

        findViewById<TextView>(R.id.text).setText(R.string.generated_value)
        findViewById<Button>(R.id.button).setOnClickListener {
            startActivity(Intent(this, FlavorActivity::class.java))
        }
        findViewById<Button>(R.id.composeButton).setOnClickListener {
            startActivity(Intent(this, ComposeActivity::class.java))
        }

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
        BuildConfig.VARIANT_NAME
    }

    /**
     * Verify KSP code generation by using the generated Moshi adapter.
     * This will fail to compile if KSP doesn't generate UserJsonAdapter.
     */
    private fun verifyKspCodeGeneration() {
        val moshi = Moshi.Builder().build()
        val adapter = moshi.adapter(User::class.java)
        val json = adapter.toJson(User("Test", 25))
    }
}
