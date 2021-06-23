/*
 * Copyright 2021 Grabtaxi Holdings PTE LTE (GRAB)
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