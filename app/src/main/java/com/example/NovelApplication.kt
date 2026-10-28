package com.example

import android.app.Application
import com.example.di.AppContainer

class NovelApplication : Application() {

    internal var isContainerInitialized: Boolean = false
        private set

    val container: AppContainer by lazy {
        isContainerInitialized = true
        AppContainer(this)
    }

    override fun onCreate() {
        super.onCreate()
        // Architectural Invariant (Rule 11, Amendment h):
        // onCreate must do zero disk or database work.
        // AppContainer is initialized lazily upon first UI access.
    }
}
