package com.example.security

import com.example.ui.auth.normalizePhoneNumber
import com.example.ui.auth.validSmsCode
import org.junit.Assert.*
import org.junit.Test

class PhoneInputTest {
    @Test
    fun formatsNormalizeToOneInternationalNumber() {
        assertEquals("+12025550123", normalizePhoneNumber("+1 (202) 555-0123"))
    }

    @Test
    fun ambiguousAndUnexpectedPhoneInputIsRejected() {
        for (value in
            listOf(
                "2025550123",
                "+012345678",
                "++12025550123",
                "+12025550123x1",
                "+١٢٠٢٥٥٥٠١٢٣",
                "+1234567890123456",
            )) assertThrows(IllegalArgumentException::class.java) { normalizePhoneNumber(value) }
    }

    @Test
    fun smsCodesRequireExactlySixAsciiDigits() {
        assertTrue(validSmsCode("012345"))
        for (value in listOf("12345", "1234567", "12a456", "１２３４５６", " 123456")) assertFalse(
            validSmsCode(value)
        )
    }
}
