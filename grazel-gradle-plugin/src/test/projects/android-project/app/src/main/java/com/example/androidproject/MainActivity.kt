package com.example.androidproject

import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import androidx.appcompat.app.AppCompatActivity
import com.example.androidlibrary.doSomethingAndroidSpecific
import com.example.kotlinlibrary1.aboutLibrary1
import com.example.kotlinlibrary2.aboutLibrary2
import com.example.kotlinlibrary3.aboutLibrary3
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.snackbar.Snackbar

class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        setSupportActionBar(findViewById(R.id.toolbar))

        findViewById<FloatingActionButton>(R.id.fab).setOnClickListener { view ->
            Snackbar.make(view, "Replace with your own action", Snackbar.LENGTH_LONG)
                    .setAction("Action", null).show()
        }

        println(doSomethingAndroidSpecific(this))
        println(aboutLibrary1)
        println(aboutLibrary2)
        println(aboutLibrary3)
    }
}