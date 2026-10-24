package com.wolftower.app

import android.app.Application

class WolfApp : Application() {
    override fun onCreate() {
        super.onCreate()
        CrashInfo.install(this)
    }
}
