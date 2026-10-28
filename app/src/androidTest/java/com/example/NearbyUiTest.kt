package com.example

import android.content.Intent
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.*
import java.io.File
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NearbyUiTest {
    @Test
    fun findNearbyExplainsDisabledRadiosAndCanReturnToChats() {
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        val intent =
            Intent(
                InstrumentationRegistry.getInstrumentation().targetContext,
                MainActivity::class.java,
            )
        ActivityScenario.launch<MainActivity>(intent).use { scenario ->
            var output: File? = null
            scenario.onActivity {
                it.repository.open()
                it.repository.settings(
                    it.repository.state.value.settings.copy(
                        secureScreen = false,
                        onboardingComplete = true,
                        timeoutSeconds = 300,
                    )
                )
                it.unlocked.value = true
                it.applyShield()
                it.touch()
                output = it.getExternalFilesDir(null)
            }
            assertTrue(device.wait(Until.hasObject(By.desc("Nearby chats")), 10000))
            device.findObject(By.desc("Nearby chats")).click()
            assertTrue(device.wait(Until.hasObject(By.text("Find nearby phones")), 10000))
            device.findObject(By.text("Find nearby phones")).click()
            assertTrue(
                "Expected an honest radio/service prerequisite",
                device.wait(Until.hasObject(By.textContains("Turn on Bluetooth")), 3000) ||
                    device.wait(
                        Until.hasObject(By.textContains("Update Google Play services")),
                        3000,
                    ),
            )
            device.waitForIdle(1000)
            assertTrue(device.takeScreenshot(File(output, "rosevault-nearby.png")))
            device.findObject(By.text("Back to chats")).click()
            assertTrue(device.wait(Until.hasObject(By.text("Closer. Privately.")), 5000))
            scenario.onActivity { it.lock() }
        }
    }
}
