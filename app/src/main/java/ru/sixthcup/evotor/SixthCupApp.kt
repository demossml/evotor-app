package ru.sixthcup.evotor

import android.app.Application
import android.os.StrictMode
import ru.sixthcup.evotor.ui.ThumbLoader

class SixthCupApp : Application() {
    override fun onCreate() {
        super.onCreate()
        ThumbLoader.init(this)
        if (BuildConfig.DEBUG) {
            // Только в debug: ловим диск/сеть на главном потоке и утечки — в логе (penaltyLog), приложение не роняем.
            StrictMode.setThreadPolicy(StrictMode.ThreadPolicy.Builder().detectAll().penaltyLog().build())
            StrictMode.setVmPolicy(StrictMode.VmPolicy.Builder().detectLeakedClosableObjects().detectLeakedSqlLiteObjects().penaltyLog().build())
        }
    }
}
