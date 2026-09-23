package com.cocakova.pygmalion

import android.app.Application

class PygmalionApp : Application() {
    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    companion object {
        lateinit var instance: PygmalionApp
            private set
    }
}
