package com.shifa

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SignatureInsensitiveLineMatcherTest {
    private val m = SignatureInsensitiveLineMatcher()
    private val repo = "com.shifa.a.infrastructure.R"

    @Test
    fun `a changed constructor parameter list or line number still matches`() {
        val a = "Constructor <com.shifa.x.api.C.<init>($repo, com.shifa.b.F)> has parameter of type <$repo> in (C.kt:12)"
        val b = "Constructor <com.shifa.x.api.C.<init>($repo)> has parameter of type <$repo> in (C.kt:31)"
        assertTrue(m.matches(a, b))
    }

    @Test
    fun `a changed method dependency does not match`() {
        val a = "Method <com.shifa.x.api.C.load(java.lang.String)> calls method <$repo.find(java.lang.String)> in (C.kt:40)"
        val b = "Method <com.shifa.x.api.C.load(java.lang.Long)> calls method <$repo.find(java.lang.Long)> in (C.kt:40)"
        val c = "Method <com.shifa.x.api.C.load(java.lang.String)> calls method <$repo.find(java.lang.String)> in (C.kt:45)"
        assertFalse(m.matches(a, b))
        assertTrue(m.matches(a, c), "line numbers are ignored")
        assertFalse(m.matches(a, a.replace("R.find", "R.findAll")))
    }
}
