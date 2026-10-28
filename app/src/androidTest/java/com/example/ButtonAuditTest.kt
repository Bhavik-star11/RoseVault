package com.example

import android.widget.EditText
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.*
import com.example.data.model.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Runs with Wi-Fi and cellular disabled by the test launcher. */
@RunWith(AndroidJUnit4::class)
class ButtonAuditTest {
    @Test
    fun offlineChatAndLocalButtonsHaveRealEffects() {
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        ActivityScenario.launch<MainActivity>(
                android.content.Intent(
                    InstrumentationRegistry.getInstrumentation().targetContext,
                    MainActivity::class.java,
                )
            )
            .use { scenario ->
                fun visible(text: String) {
                    assertTrue("Missing: $text", device.wait(Until.hasObject(By.text(text)), 10000))
                }
                fun click(text: String) {
                    visible(text)
                    device.findObject(By.text(text)).click()
                    device.waitForIdle(1000)
                }
                fun desc(text: String) {
                    assertTrue(device.wait(Until.hasObject(By.desc(text)), 10000))
                    device.findObject(By.desc(text)).click()
                    device.waitForIdle(1000)
                }
                fun setField(index: Int, value: String) {
                    repeat(4) { attempt ->
                        device.wait(Until.hasObject(By.clazz(EditText::class.java)), 5000)
                        try {
                            device.findObjects(By.clazz(EditText::class.java))[index].text = value
                            device.waitForIdle(1000)
                            return
                        } catch (e: StaleObjectException) {
                            if (attempt == 3) throw e
                            android.os.SystemClock.sleep(250)
                        }
                    }
                }
                fun unlock() {
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
                    }
                }
                visible("System Security Vault")
                unlock()
                visible("Closer. Privately.")
                desc("Create space")
                visible("Start a conversation")
                click("Cancel")
                desc("Create space")
                click("On this phone")
                setField(0, "Offline button test")
                click("Create space")
                click("Offline button test")
                visible("Encrypted on this device")
                setField(0, "Saved without internet")
                desc("Send")
                visible("Saved without internet")
                scenario.onActivity {
                    assertFalse(it.repository.online.value)
                    assertTrue(
                        it.repository.state.value.messages.any { m ->
                            m.text == "Saved without internet"
                        }
                    )
                    it.lock()
                }
                visible("System Security Vault")
                unlock()
                click("Offline button test")
                visible("Saved without internet")
                desc("Chat privacy")
                visible("Conversation privacy")
                val switches = device.findObjects(By.clazz("android.widget.Switch"))
                if (switches.isNotEmpty()) switches.last().click()
                click("Retry pending messages")
                visible("Saved without internet")
                desc("Back")
                visible("Closer. Privately.")
                desc("Nearby chats")
                visible("Find nearby phones")
                click("Back to chats")
                click("Dates")
                click("Plan a date")
                visible("Make a little plan")
                click("Cancel")
                click("Plan a date")
                setField(0, "Offline date")
                setField(1, "not-a-date")
                click("Save plan")
                visible("Enter a valid date and time")
                setField(1, "2026-12-01")
                click("Save plan")
                visible("Offline date")
                device.findObject(By.desc("Remove plan")).click()
                scenario.onActivity {
                    assertTrue(
                        it.repository.state.value.dates.none { e -> e.title == "Offline date" }
                    )
                }
                click("Security")
                if (BuildConfig.CLOUD_ENABLED) {
                    click("Sign in with phone number")
                    visible("Sign in with your number")
                    click("Cancel")
                } else visible("Offline mode · no account needed")
                // Verify a local call action reports its actual prerequisite instead of trying a
                // fake
                // call.
                scenario.onActivity {
                    val c = VaultConversation(title = "test", partnerUid = "peer")
                    it.placeCall(c, false)
                }
                visible("Calls need internet. Nearby chats can send messages without internet.")
                scenario.onActivity { it.lock() }
                visible("System Security Vault")
            }
    }
}
