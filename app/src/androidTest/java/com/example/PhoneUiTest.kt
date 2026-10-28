package com.example

import android.content.Intent
import android.widget.EditText
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.*
import java.io.File
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PhoneUiTest {
    @Test
    fun phoneOnboardingValidatesNumbersAndStillAllowsOfflineUse() {
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        ActivityScenario.launch<MainActivity>(
                Intent(
                    InstrumentationRegistry.getInstrumentation().targetContext,
                    MainActivity::class.java,
                )
            )
            .use { scenario ->
                var output: File? = null
                fun visible(text: String) {
                    assertTrue("Missing: $text", device.wait(Until.hasObject(By.text(text)), 10000))
                }
                fun click(text: String) {
                    visible(text)
                    device.findObject(By.text(text)).click()
                    device.waitForIdle(1000)
                }
                scenario.onActivity {
                    it.repository.open()
                    it.repository.settings(
                        it.repository.state.value.settings.copy(
                            secureScreen = false,
                            onboardingComplete = false,
                            timeoutSeconds = 300,
                        )
                    )
                    it.unlocked.value = true
                    it.applyShield()
                    it.touch()
                    output = it.getExternalFilesDir(null)
                }
                if (BuildConfig.CLOUD_ENABLED) {
                    visible("Continue with phone number")
                    click("Continue with phone number")
                    device.findObject(By.clazz(EditText::class.java)).text = "2025550123"
                    click("Send verification code")
                    visible("Include + and your country code, for example +12025550123.")
                    click("Cancel")
                    click("Continue offline")
                }
                visible("Closer. Privately.")
                scenario.onActivity {
                    assertEquals(BuildConfig.CLOUD_ENABLED, it.repository.cloudReady)
                    assertEquals("local", it.repository.uid)
                }
                assertFalse(device.hasObject(By.text("Continue with phone number")))
                device.findObject(By.desc("Create space")).click()
                visible("On this phone")
                if (BuildConfig.CLOUD_ENABLED) click("On this phone")
                else {
                    assertFalse(device.hasObject(By.text("Phone number")))
                    assertFalse(device.hasObject(By.text("Contact code")))
                }
                device.findObject(By.clazz(EditText::class.java)).text = "Offline first"
                click("Create space")
                click("Offline first")
                visible("Encrypted on this device")
                device.waitForIdle(1000)
                device.takeScreenshot(File(output, "rosevault-offline-first.png"))
                scenario.onActivity {
                    assertEquals(BuildConfig.CLOUD_ENABLED, it.repository.cloudReady)
                    it.lock()
                }
                visible("System Security Vault")
            }
    }
}
