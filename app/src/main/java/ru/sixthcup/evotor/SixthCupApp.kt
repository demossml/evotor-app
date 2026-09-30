package ru.sixthcup.evotor

import android.app.Application

class SixthCupApp : Application() {
    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    companion object {
        lateinit var instance: SixthCupApp
            private set
    }
}
