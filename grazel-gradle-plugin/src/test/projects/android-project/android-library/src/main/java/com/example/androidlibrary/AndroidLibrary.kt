package com.example.androidlibrary

import android.content.Context

fun doSomethingAndroidSpecific(context: Context): String {
    return "This is AndroidLibrary. Package name: ${context.packageName}"
}