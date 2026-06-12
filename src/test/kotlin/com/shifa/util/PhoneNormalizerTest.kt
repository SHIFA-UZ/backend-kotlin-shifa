package com.shifa.util

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PhoneNormalizerTest {

    @Test
    fun `isUzbekMobile accepts normalized +998 numbers`() {
        assertTrue(PhoneNormalizer.isUzbekMobile("+998901234567"))
        assertTrue(PhoneNormalizer.isUzbekMobile("998901234567"))
        assertTrue(PhoneNormalizer.isUzbekMobile("+998 90 123 45 67"))
    }

    @Test
    fun `isUzbekMobile rejects non-Uzbek numbers`() {
        assertFalse(PhoneNormalizer.isUzbekMobile("+14155552671"))
        assertFalse(PhoneNormalizer.isUzbekMobile("+99890123"))
        assertFalse(PhoneNormalizer.isUzbekMobile(null))
    }
}
