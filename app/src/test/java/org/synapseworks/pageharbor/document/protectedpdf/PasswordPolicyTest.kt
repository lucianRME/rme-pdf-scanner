package org.synapseworks.pageharbor.document.protectedpdf

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PasswordPolicyTest {
    @Test fun shortOrControlPasswordsAreRejected() {
        assertFalse(isSupportedPdfPassword("short".toCharArray()))
        assertFalse(isSupportedPdfPassword("secret\npassword".toCharArray()))
        assertFalse(isSupportedPdfPassword(charArrayOf()))
    }

    @Test fun utf8LimitDoesNotSilentlyTruncatePasswords() {
        assertTrue(isSupportedPdfPassword("a".repeat(127).toCharArray()))
        assertFalse(isSupportedPdfPassword("a".repeat(128).toCharArray()))
        assertFalse(isSupportedPdfPassword("ș".repeat(64).toCharArray()))
        assertTrue(isSupportedPdfPassword("ș".repeat(63).toCharArray()))
    }

    @Test fun spacesAndUnicodeRemainPartOfThePassword() {
        assertTrue(isSupportedPdfPassword("  word pass  ".toCharArray()))
        assertTrue(isSupportedPdfPassword("日本語のパスワード".toCharArray()))
    }

    @Test fun countsUnicodeCodePointsAndUtf8BytesWithoutTruncation() {
        assertFalse(isSupportedPdfPassword("😀".repeat(4).toCharArray()))
        assertFalse(isSupportedPdfPassword("😀".repeat(8).toCharArray()))
        assertFalse(isSupportedPdfPassword("password-₺".toCharArray()))
        assertTrue(isSupportedPdfPassword("\uD840\uDD00".repeat(8).toCharArray()))
        assertTrue(isSupportedPdfPassword("界".repeat(42).toCharArray()))
        assertFalse(isSupportedPdfPassword("界".repeat(43).toCharArray()))
    }

    @Test fun normalizationChangingAndHiddenCharactersAreRejected() {
        listOf("Cafe\u0301-password", "word\u00A0password", "fullwidth-Ａ", "hidden\u200Btext",
            "hidden\u034Ftext", "private\uE000text", "password\uD800", "password\uFFFD").forEach {
            assertFalse(isSupportedPdfPassword(it.toCharArray()))
        }
    }

    @Test fun rightToLeftTextMustRespectThePdfPasswordProfile() {
        assertTrue(isSupportedPdfPassword("אבגדהוזחט".toCharArray()))
        assertFalse(isSupportedPdfPassword("אבגדהabc".toCharArray()))
        assertFalse(isSupportedPdfPassword("אבגדהוזח1".toCharArray()))
    }

    @Test fun ordinaryPrecomposedLatinAndCjkRemainSupported() {
        assertTrue(isSupportedPdfPassword("Parolă-șț-123".toCharArray()))
        assertTrue(isSupportedPdfPassword("日本語のパスワード".toCharArray()))
    }
}
