package ru.sixthcup.evotor.net

/**
 * Один серверный сценарий «найти или выдать карту по телефону».
 *
 * Карта считается существующей только после ответа backend с cardCode.
 * Никакого локального fallback-cardCode здесь нет.
 */
class PhoneCardService(
    private val lookup: (String) -> CustomersApi.Profile = { CustomersApi.lookup(it) },
    private val register: (String, String) -> CustomersApi.Profile = { phone, version ->
        CustomersApi.register(phone, version)
    },
) {

    sealed class Result {
        data class Profile(val profile: CustomersApi.Profile) : Result()
        data class Error(val message: String) : Result()
    }

    fun findOrCreate(phone: String, consent: Boolean): Result {
        if (!consent) return Result.Error(CONSENT_MESSAGE)

        val found = try {
            lookup(phone)
        } catch (_: Throwable) {
            return Result.Error(NO_CONNECTION_MESSAGE)
        }
        if (!found.ok) return Result.Error(errorMessage(found))
        if (found.found) return profileResult(found)

        val created = try {
            register(phone, CONSENT_VERSION)
        } catch (_: Throwable) {
            return Result.Error(NO_CONNECTION_MESSAGE)
        }
        return profileResult(created)
    }

    private fun profileResult(profile: CustomersApi.Profile): Result {
        if (!profile.ok) return Result.Error(errorMessage(profile))
        if (profile.httpStatus != 200) {
            return Result.Error(errorMessage(profile))
        }
        if (profile.cardCode.isNullOrBlank()) {
            return Result.Error("Сервер не вернул номер карты. Карта не выдана.")
        }
        return Result.Profile(profile)
    }

    private fun errorMessage(profile: CustomersApi.Profile): String = when {
        profile.error == "network" ||
            profile.error == "timeout" ||
            profile.httpStatus >= 500 -> NO_CONNECTION_MESSAGE
        else -> CustomersApi.errorText(profile.httpStatus, profile.error)
    }

    companion object {
        const val CONSENT_VERSION = "v1"
        const val CONSENT_MESSAGE = "Отметьте согласие на обработку номера."
        const val NO_CONNECTION_MESSAGE = "Нет связи с сервером, карту сейчас выдать нельзя"
    }
}
