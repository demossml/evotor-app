package ru.sixthcup.evotor

import android.app.Application
import android.os.StrictMode
import ru.sixthcup.evotor.data.Cart
import ru.sixthcup.evotor.ui.ThumbLoader

class SixthCupApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // Корзина должна быть доступна DiscountIntegrationService даже если процесс
        // пересоздан на экране оплаты (MainActivity ещё не onCreate).
        Cart.attach(this)
        ThumbLoader.init(this)
        if (BuildConfig.DEBUG) {
            StrictMode.setThreadPolicy(StrictMode.ThreadPolicy.Builder().detectAll().penaltyLog().build())
            StrictMode.setVmPolicy(StrictMode.VmPolicy.Builder().detectLeakedClosableObjects().detectLeakedSqlLiteObjects().penaltyLog().build())
        }
    }
}
