package com.music.spotui.data.api

import org.junit.Assert.assertEquals
import org.junit.Test

class QmTripleDesTest {
    @Test
    fun `QQ response decrypts to a zlib header`() {
        // First 32 encrypted bytes of the real NEON / A2O LITE response (song id 733159779).
        val hex = "2F9DB32AC6D4C820750A1C52ACBCFD622E10C5C680410FA377D403640D36756B"
        val bytes = ByteArray(hex.length / 2) { i ->
            ((hex[i * 2].digitToInt(16) shl 4) + hex[i * 2 + 1].digitToInt(16)).toByte()
        }
        val schedules = QmTripleDes.tripleDesKeySetup("!@#)(*$%123ZXC!@!@#)(NHL".toByteArray(), QmTripleDes.DECRYPT)
        val plain = QmTripleDes.tripleDesCrypt(bytes, schedules)
        assertEquals(0x78.toByte(), plain[0])
        assertEquals(0x9C.toByte(), plain[1])
    }
}
