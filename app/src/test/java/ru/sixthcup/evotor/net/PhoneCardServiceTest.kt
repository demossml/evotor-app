package ru.sixthcup.evotor.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class PhoneCardServiceTest {

    @Test
    fun `not found with consent registers immediately`() {
        var registerCalls = 0
        var consentVersion = ""
        val service = PhoneCardService(
            lookup = { CustomersApi.Profile(found = false, httpStatus = 200) },
            register = { _, version ->
                registerCalls++
                consentVersion = version
                CustomersApi.Profile(found = true, cardCode = "CARD-42", httpStatus = 200)
            },
        )

        val result = service.findOrCreate("79991234567", consent = true)

        assertTrue(result is PhoneCardService.Result.Profile)
        assertEquals("CARD-42", (result as PhoneCardService.Result.Profile).profile.cardCode)
        assertEquals(1, registerCalls)
        assertEquals("v1", consentVersion)
    }

    @Test
    fun `not found without consent never calls register`() {
        var registerCalls = 0
        val service = PhoneCardService(
            lookup = { CustomersApi.Profile(found = false, httpStatus = 200) },
            register = { _, _ ->
                registerCalls++
                CustomersApi.Profile(found = true, cardCode = "CARD-42", httpStatus = 200)
            },
        )

        val result = service.findOrCreate("79991234567", consent = false)

        assertEquals(PhoneCardService.CONSENT_MESSAGE, (result as PhoneCardService.Result.Error).message)
        assertEquals(0, registerCalls)
    }

    @Test
    fun `network lookup error does not register`() {
        var registerCalls = 0
        val service = PhoneCardService(
            lookup = { CustomersApi.Profile(found = false, httpStatus = 0, error = "network") },
            register = { _, _ ->
                registerCalls++
                CustomersApi.Profile(found = true, cardCode = "CARD-42", httpStatus = 200)
            },
        )

        val result = service.findOrCreate("79991234567", consent = true)

        assertEquals(PhoneCardService.NO_CONNECTION_MESSAGE, (result as PhoneCardService.Result.Error).message)
        assertEquals(0, registerCalls)
    }

    @Test
    fun `lookup exception does not register`() {
        var registerCalls = 0
        val service = PhoneCardService(
            lookup = { throw IOException("offline") },
            register = { _, _ ->
                registerCalls++
                CustomersApi.Profile(found = true, cardCode = "CARD-42", httpStatus = 200)
            },
        )

        val result = service.findOrCreate("79991234567", consent = true)

        assertEquals(PhoneCardService.NO_CONNECTION_MESSAGE, (result as PhoneCardService.Result.Error).message)
        assertEquals(0, registerCalls)
    }

    @Test
    fun `register network error does not produce a profile`() {
        val service = PhoneCardService(
            lookup = { CustomersApi.Profile(found = false, httpStatus = 200) },
            register = { _, _ ->
                CustomersApi.Profile(found = false, httpStatus = 0, error = "network")
            },
        )

        val result = service.findOrCreate("79991234567", consent = true)

        assertEquals(PhoneCardService.NO_CONNECTION_MESSAGE, (result as PhoneCardService.Result.Error).message)
    }

    @Test
    fun `register response without cardCode is not a card`() {
        val service = PhoneCardService(
            lookup = { CustomersApi.Profile(found = false, httpStatus = 200) },
            register = { _, _ -> CustomersApi.Profile(found = true, httpStatus = 200) },
        )

        val result = service.findOrCreate("79991234567", consent = true)

        assertTrue(result is PhoneCardService.Result.Error)
    }

    @Test
    fun `server cardCode is returned unchanged`() {
        val service = PhoneCardService(
            lookup = { CustomersApi.Profile(found = false, httpStatus = 200) },
            register = { _, _ ->
                CustomersApi.Profile(found = true, cardCode = "SERVER-CODE-777", httpStatus = 200)
            },
        )

        val result = service.findOrCreate("79991234567", consent = true) as PhoneCardService.Result.Profile

        assertEquals("SERVER-CODE-777", result.profile.cardCode)
    }
}
