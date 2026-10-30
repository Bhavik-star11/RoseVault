package com.example.ai

import android.content.Context
import android.util.AtomicFile
import com.example.security.CryptoEngine
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.json.JSONArray
import org.json.JSONObject

/** Only user-submitted context is sent to the explicitly configured HTTPS Ollama server. */
class RoseAi(private val context: Context, private val crypto: CryptoEngine) {
    data class Config(
        val origin: String = "",
        val token: String = "",
        val model: String = "qwen3:4b",
    )

    private val file
        get() = AtomicFile(File(context.filesDir, "rose-ai-server.enc"))

    fun config(): Config =
        if (!file.baseFile.exists()) Config()
        else {
            val j = JSONObject(crypto.unseal(file.readFully()).toString(Charsets.UTF_8))
            Config(j.getString("origin"), j.getString("token"), j.getString("model"))
        }

    fun save(config: Config) {
        val uri = URI(config.origin)
        require(
            uri.scheme == "https" &&
                !uri.host.isNullOrBlank() &&
                uri.rawUserInfo == null &&
                uri.rawQuery == null &&
                uri.rawFragment == null &&
                uri.path in listOf("", "/")
        ) {
            "Use your server's HTTPS origin without a path, query or password."
        }
        require(
            config.token.length in 24..256 &&
                config.token.all { it.isLetterOrDigit() || it in "_-" }
        ) {
            "Use a server access token of 24–256 letters, digits, underscores or hyphens."
        }
        require(config.model.matches(Regex("[A-Za-z0-9_.:/-]{1,100}"))) {
            "Enter an installed Ollama model name."
        }
        val bytes =
            crypto.seal(
                JSONObject()
                    .put("origin", config.origin.trimEnd('/'))
                    .put("token", config.token)
                    .put("model", config.model)
                    .toString()
                    .toByteArray()
            )
        val out = file.startWrite()
        try {
            out.write(bytes)
            file.finishWrite(out)
        } catch (e: Exception) {
            file.failWrite(out)
            throw e
        }
    }

    fun forget() {
        file.delete()
    }

    fun generate(task: String, input: String): Flow<String> =
        flow {
                require(input.length <= 1600 && input.isNotBlank()) {
                    "Use a short draft or situation, up to 1600 characters."
                }
                RoseSafety.intercept(input)?.let {
                    emit(it)
                    return@flow
                }
                val c = config()
                check(c.origin.isNotBlank()) { "Configure your self-hosted server first." }
                val connection =
                    URI(c.origin + "/api/chat").toURL().openConnection() as HttpURLConnection
                try {
                    connection.requestMethod = "POST"
                    connection.instanceFollowRedirects = false
                    connection.connectTimeout = 15000
                    connection.readTimeout = 90000
                    connection.doOutput = true
                    connection.setRequestProperty("Content-Type", "application/json")
                    connection.setRequestProperty("Authorization", "Bearer ${c.token}")
                    val body =
                        JSONObject()
                            .put("model", c.model)
                            .put("stream", false)
                            .put("think", false)
                            .put("keep_alive", "0")
                            .put(
                                "options",
                                JSONObject()
                                    .put("num_predict", 220)
                                    .put("num_ctx", 2048)
                                    .put("temperature", 0.5),
                            )
                            .put(
                                "messages",
                                JSONArray()
                                    .put(
                                        JSONObject()
                                            .put("role", "system")
                                            .put("content", RoseSafety.instruction)
                                    )
                                    .put(
                                        JSONObject()
                                            .put("role", "user")
                                            .put(
                                                "content",
                                                "Task: $task\nUser-selected context:\n$input",
                                            )
                                    ),
                            )
                    connection.outputStream.use { it.write(body.toString().toByteArray()) }
                    check(connection.responseCode == 200) {
                        "Server unavailable or access denied. Check your server settings."
                    }
                    val response =
                        connection.inputStream.use { input ->
                            val output = java.io.ByteArrayOutputStream()
                            val buffer = ByteArray(4096)
                            while (true) {
                                currentCoroutineContext().ensureActive()
                                val count = input.read(buffer)
                                if (count < 0) break
                                require(output.size() + count <= 65536) {
                                    "Server response was too large."
                                }
                                output.write(buffer, 0, count)
                            }
                            output.toByteArray()
                        }
                    require(response.size <= 65536) { "Server response was too large." }
                    currentCoroutineContext().ensureActive()
                    val answer =
                        JSONObject(response.toString(Charsets.UTF_8))
                            .getJSONObject("message")
                            .getString("content")
                            .take(8000)
                    emit(RoseSafety.intercept(answer) ?: answer)
                } finally {
                    connection.disconnect()
                }
            }
            .flowOn(Dispatchers.IO)
}
