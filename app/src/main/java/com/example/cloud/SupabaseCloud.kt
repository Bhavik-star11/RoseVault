package com.example.cloud

import android.content.Context
import android.util.AtomicFile
import com.example.BuildConfig
import com.example.security.CryptoEngine
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

class CloudException(val status: Int, message: String) : Exception(message)

/** Small REST adapter: no service key, plaintext message, or credential logging. */
class SupabaseCloud(context: Context) {
    private val crypto = CryptoEngine()
    private val sessionFile = AtomicFile(File(context.filesDir, "supabase-session.enc"))
    private val mutex = Mutex()
    private var session: JSONObject? = null
    private val bindingFile = AtomicFile(File(context.filesDir, "supabase-account.enc"))
    private var boundUid: String? =
        runCatching { crypto.unseal(bindingFile.readFully()).toString(Charsets.UTF_8) }.getOrNull()
    val userId = MutableStateFlow("local")
    val phone = MutableStateFlow<String?>(null)
    val configured
        get() =
            BuildConfig.CLOUD_ENABLED &&
                BuildConfig.SUPABASE_URL.startsWith("https://") &&
                BuildConfig.SUPABASE_PUBLISHABLE_KEY.isNotBlank()

    init {
        if (
            configured &&
                (sessionFile.baseFile.exists() || File(sessionFile.baseFile.path + ".bak").exists())
        ) {
            runCatching {
                    adopt(
                        JSONObject(crypto.unseal(sessionFile.readFully()).toString(Charsets.UTF_8)),
                        false,
                    )
                }
                .onFailure {
                    session = null
                    userId.value = "local"
                    phone.value = null
                }
        }
    }

    private fun adopt(value: JSONObject, persist: Boolean = true) {
        check(!(bindingFile.baseFile.exists() && boundUid == null)) {
            "Account settings are unreadable. Your local vault remains available offline."
        }
        val user = value.getJSONObject("user")
        val id = user.getString("id")
        require(Regex("[a-f0-9-]{36}").matches(id))
        require(boundUid == null || boundUid == id) {
            "This vault is bound to another account. Account switching is not supported."
        }
        if (boundUid == null) {
            val binding = bindingFile.startWrite()
            try {
                binding.write(crypto.seal(id.toByteArray()))
                bindingFile.finishWrite(binding)
            } catch (e: Exception) {
                bindingFile.failWrite(binding)
                throw e
            }
            boundUid = id
        }
        require(
            value.getString("access_token").isNotBlank() &&
                value.getString("refresh_token").isNotBlank()
        )
        if (!value.has("expires_at"))
            value.put(
                "expires_at",
                System.currentTimeMillis() / 1000 + value.optLong("expires_in", 3600),
            )
        if (persist) {
            val out = sessionFile.startWrite()
            try {
                out.write(crypto.seal(value.toString().toByteArray()))
                sessionFile.finishWrite(out)
            } catch (e: Exception) {
                sessionFile.failWrite(out)
                throw e
            }
        }
        session = value
        userId.value = id
        phone.value =
            user.optString("phone").takeIf { it.isNotBlank() }?.let { "+" + it.removePrefix("+") }
    }

    suspend fun sendOtp(number: String) {
        require(configured) {
            "Phone sign-in needs a Supabase project and SMS provider. You can continue offline."
        }
        request(
            "POST",
            "/auth/v1/otp",
            JSONObject().put("phone", number).put("create_user", true).toString().toByteArray(),
            authenticated = false,
        )
    }

    suspend fun verifyOtp(number: String, code: String) =
        mutex.withLock {
            val response =
                JSONObject(
                    request(
                            "POST",
                            "/auth/v1/verify",
                            JSONObject()
                                .put("phone", number)
                                .put("token", code)
                                .put("type", "sms")
                                .toString()
                                .toByteArray(),
                            authenticated = false,
                        )
                        .toString(Charsets.UTF_8)
                )
            require(
                userId.value == "local" ||
                    userId.value == response.getJSONObject("user").getString("id")
            ) {
                "This vault belongs to another account. Account switching is not supported."
            }
            adopt(response)
        }

    private suspend fun token(): String =
        mutex.withLock {
            val current = session ?: error("Sign in with your phone number first.")
            if (current.optLong("expires_at") <= System.currentTimeMillis() / 1000 + 60) {
                try {
                    val next =
                        JSONObject(
                            raw(
                                    "POST",
                                    "/auth/v1/token?grant_type=refresh_token",
                                    JSONObject()
                                        .put("refresh_token", current.getString("refresh_token"))
                                        .toString()
                                        .toByteArray(),
                                    null,
                                    "application/json",
                                    null,
                                )
                                .toString(Charsets.UTF_8)
                        )
                    adopt(next)
                } catch (e: CloudException) {
                    if (e.status in listOf(400, 401, 403)) {
                        session = null
                        sessionFile.delete()
                        userId.value = "local"
                        phone.value = null
                    }
                    throw e
                }
            }
            session!!.getString("access_token")
        }

    suspend fun request(
        method: String,
        path: String,
        body: ByteArray? = null,
        authenticated: Boolean = true,
        contentType: String = "application/json",
        prefer: String? = null,
    ): ByteArray {
        require(configured) { "Supabase is not configured. Local vault and Nearby still work." }
        return raw(method, path, body, if (authenticated) token() else null, contentType, prefer)
    }

    private suspend fun raw(
        method: String,
        path: String,
        body: ByteArray?,
        bearer: String?,
        contentType: String,
        prefer: String?,
    ): ByteArray =
        withContext(Dispatchers.IO) {
            require(path.startsWith("/") && !path.startsWith("//"))
            val connection =
                URL(BuildConfig.SUPABASE_URL.trimEnd('/') + path).openConnection()
                    as HttpURLConnection
            connection.instanceFollowRedirects = false
            connection.requestMethod = method
            connection.connectTimeout = 15_000
            connection.readTimeout = 15_000
            connection.setRequestProperty("apikey", BuildConfig.SUPABASE_PUBLISHABLE_KEY)
            bearer?.let { connection.setRequestProperty("Authorization", "Bearer $it") }
            connection.setRequestProperty("Content-Type", contentType)
            prefer?.let { connection.setRequestProperty("Prefer", it) }
            try {
                if (body != null) {
                    connection.doOutput = true
                    connection.setFixedLengthStreamingMode(body.size)
                    connection.outputStream.use { it.write(body) }
                }
                val status = connection.responseCode
                if (status !in 200..299) {
                    // Never surface complete server responses, which can contain identifiers or
                    // tokens.
                    val details =
                        connection.errorStream
                            ?.use { it.readBytes().toString(Charsets.UTF_8) }
                            .orEmpty()
                    val message =
                        if (path.startsWith("/functions/") || path.startsWith("/rest/v1/rpc/phone"))
                            runCatching { JSONObject(details).optString("message") }
                                .getOrNull()
                                ?.takeIf { it.isNotBlank() }
                        else null
                    throw CloudException(
                        status,
                        message
                            ?: when {
                                path.startsWith("/auth/") && status == 429 ->
                                    "Too many verification requests. Try again later."
                                path.startsWith("/auth/v1/verify") ->
                                    "The verification code is invalid or expired."
                                path.startsWith("/auth/") ->
                                    "Phone verification is unavailable. Check the SMS provider setup or try again later."
                                status == 401 ->
                                    "Your session expired. Verify your phone number again."
                                status == 403 ->
                                    "This account cannot access that conversation or file."
                                status == 404 -> "The cloud service has not been deployed yet."
                                status == 409 -> "This item already exists with a pinned identity."
                                else ->
                                    "The cloud service is unavailable. Your messages remain saved locally."
                            },
                    )
                }
                connection.inputStream.use { stream ->
                    val limit = 30 * 1024 * 1024
                    val out = ByteArrayOutputStream()
                    val buffer = ByteArray(8192)
                    var count = stream.read(buffer)
                    while (count >= 0) {
                        require(out.size() + count <= limit) {
                            "Cloud response exceeds the supported size."
                        }
                        out.write(buffer, 0, count)
                        count = stream.read(buffer)
                    }
                    out.toByteArray()
                }
            } finally {
                connection.disconnect()
            }
        }

    suspend fun rows(table: String, filter: String = "", paginate: Boolean = true): List<JSONObject> {
        val result = mutableListOf<JSONObject>()
        var offset = 0
        while (true) {
            val array =
                JSONArray(
                    request("GET", "/rest/v1/$table?select=*&limit=1000&offset=$offset$filter")
                        .toString(Charsets.UTF_8)
                )
            for (index in 0 until array.length()) result += array.getJSONObject(index)
            if (!paginate || array.length() < 1000) return result
            offset += 1000
            require(offset < 100_000) {
                "This conversation exceeds the supported synchronization window."
            }
        }
    }

    suspend fun insert(table: String, value: JSONObject) {
        request(
            "POST",
            "/rest/v1/$table",
            value.toString().toByteArray(),
            prefer = "return=minimal",
        )
    }

    suspend fun patch(table: String, filter: String, value: JSONObject) {
        request(
            "PATCH",
            "/rest/v1/$table?$filter",
            value.toString().toByteArray(),
            prefer = "return=minimal",
        )
    }

    suspend fun delete(table: String, filter: String) {
        request("DELETE", "/rest/v1/$table?$filter")
    }

    suspend fun function(name: String, value: JSONObject = JSONObject()): JSONObject =
        JSONObject(
            request("POST", "/functions/v1/$name", value.toString().toByteArray())
                .toString(Charsets.UTF_8)
        )

    suspend fun phoneAction(action: String, value: JSONObject = JSONObject()): JSONObject =
        JSONObject(
            request(
                    "POST",
                    "/rest/v1/rpc/phone",
                    JSONObject()
                        .put("action", action)
                        .put("payload", value)
                        .toString()
                        .toByteArray(),
                )
                .toString(Charsets.UTF_8)
        )

    suspend fun upload(path: String, bytes: ByteArray): ByteArray? {
        try {
            request(
                "POST",
                "/storage/v1/object/vault-media/$path",
                bytes,
                contentType = "application/octet-stream",
            )
            return null
        } catch (e: CloudException) {
            if (e.status in listOf(400, 409)) {
                try {
                    return download(path)
                } catch (_: Exception) {}
            }
            throw e
        }
    }

    suspend fun download(path: String): ByteArray {
        require(Regex("media/[a-f0-9]{64}/[a-f0-9-]{36}").matches(path)) {
            "Invalid attachment path"
        }
        return request("GET", "/storage/v1/object/authenticated/vault-media/$path")
    }
}
