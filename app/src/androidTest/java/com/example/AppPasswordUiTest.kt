package com.example

import android.widget.EditText
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.*
import com.example.security.AppPasswordStore
import org.junit.Assert.*
import org.junit.Test

class AppPasswordUiTest {
    @Test
    fun wrongPasswordCannotUnlockAndCorrectPasswordCan() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = AppPasswordStore(context)
        store.set("local testing password".toCharArray())
        try {
            ActivityScenario.launch<MainActivity>(
                    android.content.Intent(context, MainActivity::class.java)
                )
                .use { scenario ->
                    val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
                    fun click(text: String) {
                        assertTrue(
                            "Missing: $text",
                            device.wait(Until.hasObject(By.text(text)), 15000),
                        )
                        device.findObject(By.text(text)).click()
                        device.waitForIdle(1000)
                    }
                    scenario.onActivity {
                        it.repository.open()
                        it.repository.settings(
                            it.repository.state.value.settings.copy(
                                onboardingComplete = true,
                                secureScreen = false,
                                timeoutSeconds = 300,
                            )
                        )
                        it.lock()
                    }
                    click("Use RoseVault password")
                    device.findObject(By.clazz(EditText::class.java)).text = "incorrect password"
                    click("Unlock vault")
                    assertTrue(
                        device.wait(
                            Until.hasObject(By.text("Incorrect password. Try again.")),
                            20000,
                        )
                    )
                    scenario.onActivity { assertFalse(it.unlocked.value) }
                    device.findObject(By.clazz(EditText::class.java)).text =
                        "local testing password"
                    click("Unlock vault")
                    assertTrue(device.wait(Until.hasObject(By.text("Closer. Privately.")), 20000))
                    scenario.onActivity {
                        assertTrue(it.unlocked.value)
                        it.lock()
                    }
                    click("Use RoseVault password")
                    click("Cancel")
                    scenario.onActivity { assertFalse(it.unlocked.value) }
                }
        } finally {
            store.remove()
        }
    }
}
