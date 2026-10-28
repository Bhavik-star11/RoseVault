package com.example

import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import com.example.data.model.VirtualDateEvent
import java.io.File
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class VaultUiTest {
    @Test
    fun gateAndNavigationRenderCorrectly() {
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        ActivityScenario.launch<MainActivity>(
                android.content.Intent(
                    InstrumentationRegistry.getInstrumentation().targetContext,
                    MainActivity::class.java,
                )
            )
            .use { scenario ->
                fun visible(text: String) {
                    assertTrue(
                        "Expected screen text: $text",
                        device.wait(Until.hasObject(By.text(text)), 10000),
                    )
                }
                visible("System Security Vault")
                var output: File? = null
                // In-process test setup only. Production entry requires system authentication.
                scenario.onActivity { activity ->
                    activity.repository.open()
                    activity.repository.settings(
                        activity.repository.state.value.settings.copy(
                            secureScreen = false,
                            onboardingComplete = true,
                            timeoutSeconds = 300,
                        )
                    )
                    activity.repository.newConversation("Our little world", true)
                    activity.repository.newConversation("Weekend plans", false)
                    activity.repository.addDate(
                        VirtualDateEvent(
                            title = "Movie night",
                            date = "2026-10-30",
                            time = "20:00",
                            note = "Something cozy. Popcorn required.",
                        )
                    )
                    activity.unlocked.value = true
                    activity.applyShield()
                    activity.touch()
                    output = activity.getExternalFilesDir(null)
                }
                visible("Closer. Privately.")
                visible("Our little world")
                device.waitForIdle()
                android.os.SystemClock.sleep(500)
                assertTrue(device.takeScreenshot(File(output, "rosevault-chats.png")))
                device.findObject(By.text("Calls")).click()
                visible("A voice, a little\ncloser.")
                device.waitForIdle()
                android.os.SystemClock.sleep(500)
                assertTrue(device.takeScreenshot(File(output, "rosevault-calls.png")))
                device.findObject(By.text("Vault")).click()
                visible("The little things.")
                device.findObject(By.text("Dates")).click()
                visible("Movie night")
                device.waitForIdle()
                android.os.SystemClock.sleep(500)
                assertTrue(device.takeScreenshot(File(output, "rosevault-dates.png")))
                device.findObject(By.text("Security")).click()
                visible("Peace of mind.")
                device.waitForIdle()
                android.os.SystemClock.sleep(500)
                assertTrue(device.takeScreenshot(File(output, "rosevault-security.png")))
                scenario.onActivity { it.lock() }
                visible("System Security Vault")
            }
    }
}
