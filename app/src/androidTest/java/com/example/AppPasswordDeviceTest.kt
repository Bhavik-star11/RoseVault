package com.example

import androidx.test.platform.app.InstrumentationRegistry
import com.example.security.AppPasswordStore
import java.io.File
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

class AppPasswordDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val store = AppPasswordStore(context)

    @After
    fun cleanup() {
        store.remove()
    }

    @Test
    fun passwordPersistsEncryptedAndRejectsWrongCredentials() {
        store.set("local testing password".toCharArray())
        assertTrue(AppPasswordStore(context).configured)
        assertFalse(
            File(context.filesDir, "app-password.enc").readText().contains("local testing password")
        )
        assertFalse(store.verify("wrong test password".toCharArray()))
        assertTrue(AppPasswordStore(context).verify("local testing password".toCharArray()))
    }

    @Test
    fun failedAttemptDelaySurvivesReopeningAndExpires() {
        var time = 1_000_000L
        val timed = AppPasswordStore(context) { time }
        timed.set("local testing password".toCharArray())
        repeat(5) { assertFalse(timed.verify("incorrect password".toCharArray())) }
        assertThrows(IllegalArgumentException::class.java) {
            AppPasswordStore(context) { time }.verify("local testing password".toCharArray())
        }
        time += 30_001
        assertTrue(timed.verify("local testing password".toCharArray()))
    }

    @Test
    fun corruptPasswordRecordFailsClosed() {
        store.set("local testing password".toCharArray())
        File(context.filesDir, "app-password.enc").writeText("corrupt")
        assertTrue(store.configured)
        assertThrows(Exception::class.java) { store.verify("local testing password".toCharArray()) }
    }

    @Test
    fun changingAndRemovingPasswordDoNotResetVault() {
        store.set("first local password".toCharArray())
        store.set("second local password".toCharArray())
        assertFalse(store.verify("first local password".toCharArray()))
        assertTrue(store.verify("second local password".toCharArray()))
        store.remove()
        assertFalse(store.configured)
    }
}
