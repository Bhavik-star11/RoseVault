package com.example.matrix

import java.net.HttpURLConnection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** Unauthenticated connectivity probe only. Messaging and authentication remain SDK-owned. */
object MatrixConnectionCheck {
    suspend fun check(input: String): String =
        withContext(Dispatchers.IO) {
            val origin =
                try {
                    MatrixVault.validateOrigin(input)
                } catch (_: Exception) {
                    return@withContext "Check the server address. Use the complete address with http:// or https://, and no extra path."
                }
            val connection =
                java.net.URI(origin + "/_matrix/client/versions").toURL().openConnection()
                    as HttpURLConnection
            try {
                connection.connectTimeout = 8000
                connection.readTimeout = 8000
                connection.instanceFollowRedirects = false
                if (connection.responseCode != 200)
                    return@withContext "The server replied with HTTP ${connection.responseCode}. Check that this is the Matrix server address."
                val buffer = ByteArray(65537)
                val count =
                    connection.inputStream.use { input ->
                        var total = 0
                        while (total < buffer.size) {
                            val read = input.read(buffer, total, buffer.size - total)
                            if (read < 0) break
                            total += read
                        }
                        total
                    }
                val bytes = buffer.copyOf(count)
                if (
                    bytes.size > 65536 ||
                        JSONObject(bytes.toString(Charsets.UTF_8))
                            .optJSONArray("versions")
                            ?.length()
                            .let { it == null || it == 0 }
                )
                    return@withContext "The address replied, but it did not return a Matrix server response."
                "Connection works from this phone. Now enter your full Matrix ID and its matching account password."
            } catch (_: java.net.SocketTimeoutException) {
                "Connection timed out. Check the computer address, same Wi-Fi, Docker, and the computer firewall."
            } catch (_: javax.net.ssl.SSLException) {
                "HTTPS certificate verification failed. Check the server certificate; do not disable verification."
            } catch (_: Exception) {
                "This phone could not reach the server. Check the computer address, same Wi-Fi, Docker, and the computer firewall."
            } finally {
                connection.disconnect()
            }
        }
}
