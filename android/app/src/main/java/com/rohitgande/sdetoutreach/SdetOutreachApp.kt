package com.rohitgande.sdetoutreach

import android.app.Application
import com.rohitgande.sdetoutreach.data.Settings
import com.rohitgande.sdetoutreach.worker.cancelPeriodicRun
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader

class SdetOutreachApp : Application() {
    override fun onCreate() {
        super.onCreate()
        PDFBoxResourceLoader.init(applicationContext)

        // WorkManager can resurrect a previously-cancelled periodic job across an app
        // reinstall/update — see Settings.isBackgroundEnabled. Re-assert the user's last
        // explicit choice on every process start so a resurrected job never survives
        // unnoticed.
        if (!Settings.isBackgroundEnabled(applicationContext)) {
            cancelPeriodicRun(applicationContext)
        }
    }
}
