package com.example.autoposter

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ValidatorsTest {
    @Test fun emails() {
        assertTrue(Validators.isEmail("a.b+c@example.co.in")); assertTrue(Validators.isEmail(" a@b.io "))
        assertFalse(Validators.isEmail("a@b")); assertFalse(Validators.isEmail("no-at.example.com")); assertFalse(Validators.isEmail(""))
    }
    @Test fun passwords() {
        assertTrue(Validators.isStrongEnough("abcdefg1")); assertFalse(Validators.isStrongEnough("abcdefgh"))
        assertFalse(Validators.isStrongEnough("12345678")); assertFalse(Validators.isStrongEnough("ab1"))
    }
    @Test fun otp() {
        assertTrue(Validators.isOtp("123456")); assertTrue(Validators.isOtp("12345678")); assertFalse(Validators.isOtp("12345")); assertFalse(Validators.isOtp("12a456"))
    }
}
