package dev.rodolphe.accesscontrol.api

import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals

class CursorCodecTest {

    @Test fun `encode then decode round-trips`() {
        val cursor = encodeCursor(1784400000000L, "abc-123")
        assertEquals(1784400000000L to "abc-123", decodeCursor(cursor))
    }

    @Test fun `decode reads a known base64 token`() {
        val token = Base64.getUrlEncoder().withoutPadding().encodeToString("1000:xyz".toByteArray())
        assertEquals(1000L to "xyz", decodeCursor(token))
    }

    @Test fun `uuid id (dashes, no colon) is preserved`() {
        val (ts, id) = decodeCursor(encodeCursor(42L, "9f8e-7d6c-5b4a"))
        assertEquals(42L, ts)
        assertEquals("9f8e-7d6c-5b4a", id)
    }
}
