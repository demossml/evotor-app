package ru.sixthcup.evotor.net

import android.util.Base64
import java.security.SecureRandom

object DeviceKeys {
    fun generatePublicKeyPlaceholder(): String {
        val bytes = ByteArray(32)
        SecureRandom().nextBytes(bytes)
        return Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    }
}
