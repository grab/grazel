package com.grab.example

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.grab.databinding.DatabindingActivity
import com.grab.hybrid.kotlin.KotlinClass
import com.grab.library.HelloWorld

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.layout_main)
        HelloWorld()
        KotlinClass()
        startActivity(Intent(this, DatabindingActivity::class.java))
    }
}