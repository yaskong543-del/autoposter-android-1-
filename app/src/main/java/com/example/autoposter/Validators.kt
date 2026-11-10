package com.example.autoposter

object Validators {
    private val emailRx = Regex("^[A-Za-z0-9._%+-]+@[A-Za-z0-9-]+(\\.[A-Za-z0-9-]+)*\\.[A-Za-z]{2,}$")
    const val MIN_PASSWORD = 8

    fun isEmail(s: String) = emailRx.matches(s.trim())
    fun isStrongEnough(p: String) = p.length >= MIN_PASSWORD && p.any { it.isLetter() } && p.any { it.isDigit() }
    fun isOtp(s: String) = s.trim().length in 6..8 && s.trim().all { it.isDigit() }
}
