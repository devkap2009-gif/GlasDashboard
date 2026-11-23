package de.glas.dashboard

import android.app.Application
import android.content.Context

/** Merkt sich Abstürze, damit die App sie beim nächsten Start anzeigen kann. */
class App : Application() {
    override fun onCreate() {
        super.onCreate()
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            runCatching {
                getSharedPreferences("crash", Context.MODE_PRIVATE).edit()
                    .putString("last", "Version " + runCatching { packageManager.getPackageInfo(packageName, 0).versionName }.getOrDefault("?") +
                        " · " + java.text.DateFormat.getDateTimeInstance().format(java.util.Date()) + "\n" +
                        android.util.Log.getStackTraceString(e).take(6000))
                    .commit()
            }
            previous?.uncaughtException(t, e)
        }
    }
}
