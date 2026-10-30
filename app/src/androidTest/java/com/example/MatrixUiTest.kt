package com.example

import android.content.Intent
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MatrixUiTest {
    @Test
    fun matrixLoginIsBehindVaultLockAndDoesNotAskForSms() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java)).use {
            scenario ->
            scenario.onActivity {
                it.repository.open()
                it.repository.settings(
                    it.repository.state.value.settings.copy(
                        onboardingComplete = true,
                        timeoutSeconds = 300,
                    )
                )
                it.unlocked.value = true
                it.touch()
            }
            assertTrue(device.wait(Until.hasObject(By.text("Matrix")), 10000))
            device.findObject(By.text("Matrix")).click()
            val serverLabel =
                if (BuildConfig.LOCAL_MATRIX_DEV) "Homeserver address (local test)"
                else "Homeserver HTTPS origin"
            assertTrue(device.wait(Until.hasObject(By.text(serverLabel)), 10000))
            assertTrue(device.hasObject(By.text("Matrix ID")))
            assertFalse(device.hasObject(By.text("Send verification code")))
            scenario.onActivity { it.lock() }
            assertTrue(device.wait(Until.hasObject(By.text("System Security Vault")), 10000))
            assertFalse(device.hasObject(By.text(serverLabel)))
        }
    }
}
