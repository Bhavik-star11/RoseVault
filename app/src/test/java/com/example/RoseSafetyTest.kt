package com.example

import com.example.ai.RoseSafety
import org.junit.Assert.*
import org.junit.Test

class RoseSafetyTest {
    @Test
    fun dangerDoesNotReachGeneration() {
        for (text in
            listOf(
                "I might hurt myself",
                "There is violence",
                "I am being abused",
                "Someone is threatening me",
            )) assertEquals(RoseSafety.support, RoseSafety.intercept(text))
    }

    @Test
    fun controllingRequestsAreRefused() {
        for (text in
            listOf(
                "spy on my partner",
                "track her without consent",
                "I want revenge",
                "blackmail him",
            )) assertNotNull(RoseSafety.intercept(text))
    }

    @Test
    fun OrdinaryReflectionIsAllowed() {
        assertNull(RoseSafety.intercept("Help me apologize for replying late."))
    }
}
