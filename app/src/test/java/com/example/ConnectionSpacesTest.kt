package com.example

import com.example.data.model.*
import org.junit.Assert.*
import org.junit.Test

class ConnectionSpacesTest {
    private fun action(
        id: String,
        who: String,
        kind: String,
        text: String = "Dating",
        ref: String = "",
        at: Long = 1,
    ) =
        EncryptedMessage(
            id = id,
            conversationId = "c",
            senderId = who,
            kind = "space.$kind",
            text = text,
            reference = ref,
            createdAt = at,
        )

    @Test
    fun unilateralChoiceCannotActivate() {
        assertEquals(
            "Neutral",
            ConnectionSpaces.view(listOf(action("1", "a", "offer")), "a", "b").label,
        )
    }

    @Test
    fun selfAcceptanceAndOutsidersCannotActivate() {
        val offer = action("1", "a", "offer")
        for (who in listOf("a", "outsider")) assertEquals(
            "Neutral",
            ConnectionSpaces.view(
                    listOf(offer, action("2", who, "accept", ref = "1", at = 2)),
                    "a",
                    "b",
                )
                .label,
        )
    }

    @Test
    fun mutualAcceptanceActivates() {
        val events =
            listOf(action("1", "a", "offer"), action("2", "b", "accept", ref = "1", at = 2))
        assertEquals("Dating", ConnectionSpaces.view(events, "a", "b").label)
        assertEquals(
            ConnectionSpaces.view(events, "a", "b"),
            ConnectionSpaces.view(events.reversed(), "a", "b"),
        )
    }

    @Test
    fun withdrawingBlocksLateAcceptance() {
        val events =
            listOf(
                action("1", "a", "offer"),
                action("2", "a", "close", ref = "1", at = 2),
                action("3", "b", "accept", ref = "1", at = 3),
            )
        assertEquals("Neutral", ConnectionSpaces.view(events, "a", "b").label)
    }

    @Test
    fun declineBlocksReplayAndChangingLabelsNeedsNewConsent() {
        val events =
            listOf(
                action("1", "a", "offer"),
                action("2", "b", "decline", ref = "1", at = 2),
                action("3", "b", "accept", ref = "1", at = 3),
            )
        assertEquals("Neutral", ConnectionSpaces.view(events, "a", "b").label)
        assertEquals(
            "Neutral",
            ConnectionSpaces.view(
                    listOf(
                        action("1", "a", "offer"),
                        action("2", "b", "accept", text = "Lovers", ref = "1", at = 2),
                    ),
                    "a",
                    "b",
                )
                .label,
        )
    }

    @Test
    fun eitherPersonCanWithdrawActiveConsent() {
        val events =
            listOf(
                action("1", "a", "offer"),
                action("2", "b", "accept", ref = "1", at = 2),
                action("3", "b", "close", ref = "1", at = 3),
            )
        assertEquals("Neutral", ConnectionSpaces.view(events, "a", "b").label)
    }
}
