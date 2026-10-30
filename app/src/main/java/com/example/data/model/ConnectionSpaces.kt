package com.example.data.model

/** Private intentions never enter this reducer: only explicitly shared, authenticated messages. */
object ConnectionSpaces {
    val labels = listOf("Friends", "Dating", "Lovers")

    data class View(
        val label: String = "Neutral",
        val agreement: String = "",
        val pending: List<EncryptedMessage> = emptyList(),
    )

    fun view(messages: List<EncryptedMessage>, self: String, peer: String): View {
        if (self == peer || peer.isBlank()) return View()
        val offers = linkedMapOf<String, EncryptedMessage>()
        val closed = mutableSetOf<String>()
        var label = "Neutral"
        var agreement = ""
        for (m in
            messages.sortedWith(compareBy<EncryptedMessage> { it.createdAt }.thenBy { it.id })) {
            if (m.senderId != self && m.senderId != peer) continue
            when (m.kind) {
                "space.offer" -> if (m.text in labels) offers[m.id] = m
                "space.accept" -> {
                    val offer = offers[m.reference] ?: continue
                    if (
                        offer.senderId != m.senderId &&
                            m.text == offer.text &&
                            offer.id !in closed &&
                            offer.reference == agreement
                    ) {
                        label = offer.text
                        agreement = offer.id
                    }
                }
                "space.decline" -> {
                    val offer = offers[m.reference] ?: continue
                    if (offer.senderId != m.senderId) closed += offer.id
                }
                "space.close" -> {
                    // Either person may withdraw. A late acceptance cannot resurrect that offer.
                    closed += m.reference
                    if (agreement == m.reference) {
                        label = "Neutral"
                        agreement = ""
                    }
                }
            }
        }
        return View(
            label,
            agreement,
            offers.values.filter {
                it.id !in closed && it.id != agreement && it.reference == agreement
            },
        )
    }
}
