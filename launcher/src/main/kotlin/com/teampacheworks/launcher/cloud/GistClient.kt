package com.teampacheworks.launcher.cloud

import com.teampacheworks.launcher.log.LauncherLog
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

/** Which read path this session settled on (§3 invariant 5). Sticky for the client's lifetime. */
enum class GistReadStrategy { Unknown, Authenticated, AnonymousRaw }

data class GistFile(
    val name: String,
    val size: Long,
    val truncated: Boolean,
    val content: String?,
    val rawUrl: String?
)

data class GistSnapshot(
    val id: String,
    val ownerLogin: String,
    val description: String,
    val isPublic: Boolean,
    val latestCommit: String?,
    val files: Map<String, GistFile>
)

/**
 * Knows about gists; knows nothing about saves (design spec §3).
 *
 * 🔴 Structural inertness: every authenticated entry point takes a non-null [GitHubToken], and
 * [newApiRequest] throws if one is somehow absent. "No token means no traffic" is a property of the
 * type signature here, not of caller discipline.
 *
 * @param userAgentProduct The product token used in the `User-Agent` header, e.g. `"PacheLauncher"`
 *   or a host's own app id - purely diagnostic on GitHub's side, never security-relevant.
 * @param appVersion Shown alongside [userAgentProduct] in the `User-Agent` header.
 */
class GistClient(appVersion: String, userAgentProduct: String = "PacheLauncher") {

    private val userAgent = "$userAgentProduct/${appVersion.ifEmpty { "dev" }}"

    private val http = OkHttpClient.Builder()
        .connectTimeout(60, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    var readStrategy: GistReadStrategy = GistReadStrategy.Unknown
        private set

    /** From `GitHub-Authentication-Token-Expiration`; drives the 30/7-day warning. */
    var tokenExpiration: String? = null
        private set

    var rateLimitRemaining: Int? = null
        private set

    private var cachedSnapshot: GistSnapshot? = null
    private var cachedSnapshotId: String? = null

    // -------------------------------------------------------------- public API

    fun getUserLogin(token: GitHubToken): String {
        val body = sendJson(newApiRequest(token, "$API/user").get().build(), "GET /user")
        return body?.jsonObject?.get("login")?.jsonPrimitive?.contentOrNull ?: ""
    }

    fun createGist(token: GitHubToken, description: String, files: Map<String, String?>): GistSnapshot {
        val payload = buildJsonObject {
            put("description", JsonPrimitive(description))
            // 🔴 Always false. There is no code path in this app that creates a public gist and none
            // may be added: GitHub does not allow making a public gist secret afterwards.
            put("public", JsonPrimitive(false))
            put("files", filesNode(files))
        }
        val req = newApiRequest(token, "$API/gists")
            .post(payload.toString().toRequestBody(JSON_MEDIA)).build()
        val node = sendJson(req, "POST /gists") ?: throw CloudException(
            CloudFailure.Protocol, "GitHub returned an empty response.", null, "POST /gists"
        )
        invalidateReadCache()
        val snap = parseGist(node.jsonObject)
        LauncherLog.write("cloud", "gist created id=${CloudException.maskGistId(snap.id)} files=${files.size}")
        return snap
    }

    /**
     * 🔴 One PATCH carries the slot file **and** the manifest = one commit, so there is no window in
     * which the file and its metadata disagree. A null value deletes that file.
     */
    fun patchGist(token: GitHubToken, gistId: String, files: Map<String, String?>): GistSnapshot {
        val payload = buildJsonObject { put("files", filesNode(files)) }
        val req = newApiRequest(token, "$API/gists/${enc(gistId)}")
            .patch(payload.toString().toRequestBody(JSON_MEDIA)).build()
        val node = sendJson(req, "PATCH /gists/{id}") ?: throw CloudException(
            CloudFailure.Protocol, "GitHub returned an empty response.", null, "PATCH /gists/{id}"
        )
        invalidateReadCache()
        val snap = parseGist(node.jsonObject)
        LauncherLog.write(
            "cloud",
            "gist patched id=${CloudException.maskGistId(gistId)} files=${files.size} " +
                "commit=${shortCommit(snap.latestCommit)}"
        )
        return snap
    }

    fun deleteGist(token: GitHubToken, gistId: String) {
        val req = newApiRequest(token, "$API/gists/${enc(gistId)}").delete().build()
        sendWithRetry(req, "DELETE /gists/{id}")
        invalidateReadCache()
        LauncherLog.write("cloud", "gist deleted id=${CloudException.maskGistId(gistId)}")
    }

    fun listMyGists(token: GitHubToken, maxPages: Int): List<GistSnapshot> {
        val all = ArrayList<GistSnapshot>()
        for (page in 1..maxPages) {
            val req = newApiRequest(token, "$API/gists?per_page=100&page=$page").get().build()
            val node = sendJson(req, "GET /gists") ?: break
            val arr = try { node.jsonArray } catch (t: Throwable) { break }
            if (arr.isEmpty()) break
            arr.forEach { all += parseGist(it.jsonObject) }
            // A short page is the last page - saves a request.
            if (arr.size < 100) break
        }
        LauncherLog.write("cloud", "gist list: ${all.size} gist(s) seen")
        return all
    }

    fun getSnapshot(token: GitHubToken, gistId: String): GistSnapshot {
        cachedSnapshot?.let { if (cachedSnapshotId == gistId) return it }
        val req = newApiRequest(token, "$API/gists/${enc(gistId)}").get().build()
        val node = sendJson(req, "GET /gists/{id}") ?: throw CloudException(
            CloudFailure.Protocol, "GitHub returned an empty response.", null, "GET /gists/{id}"
        )
        val snap = parseGist(node.jsonObject)
        cachedSnapshot = snap
        cachedSnapshotId = gistId
        return snap
    }

    fun invalidateReadCache() {
        cachedSnapshot = null
        cachedSnapshotId = null
    }

    /**
     * §3 invariant 5 - dual read. Try the authenticated `GET /gists/{id}` first; **only** a
     * 401/403/404 from that call flips the session to the anonymous raw URL. A 5xx or a timeout is
     * transient: it is retried, and it never changes strategy.
     */
    fun readTextFile(
        token: GitHubToken?,
        gistId: String,
        login: String,
        fileName: String,
        commitSha: String?,
        cacheBusterRevision: Int?
    ): String {
        if (readStrategy != GistReadStrategy.AnonymousRaw && token != null) {
            try {
                val snap = getSnapshot(token, gistId)
                if (readStrategy != GistReadStrategy.Authenticated) {
                    readStrategy = GistReadStrategy.Authenticated
                    LauncherLog.write(
                        "cloud",
                        "read path = AUTHENTICATED GET /gists/{id} (id=${CloudException.maskGistId(gistId)})"
                    )
                }
                val f = snap.files[fileName]
                    ?: throw CloudException(
                        CloudFailure.NotFound, "That file is not in your cloud gist any more.",
                        404, "GET /gists/{id}"
                    )
                if (!f.truncated && f.content != null) return f.content
                if (!f.rawUrl.isNullOrEmpty()) return getRaw(f.rawUrl, token)
                throw CloudException(
                    CloudFailure.Protocol, "GitHub returned a file with no content and no raw URL.",
                    null, "GET /gists/{id}"
                )
            } catch (ex: CloudException) {
                val flips = ex.failure == CloudFailure.Unauthorized ||
                    ex.failure == CloudFailure.MissingGistPermission ||
                    // Once the authenticated path is known to work, a 404 means the file is really
                    // gone and MUST propagate rather than silently switching strategy.
                    (ex.failure == CloudFailure.NotFound && ex.statusCode == 404 &&
                        readStrategy == GistReadStrategy.Unknown)
                if (!flips) throw ex
                readStrategy = GistReadStrategy.AnonymousRaw
                LauncherLog.write(
                    "cloud",
                    "read path = ANONYMOUS RAW (authenticated GET returned ${ex.statusCode ?: "?"}; " +
                        "id=${CloudException.maskGistId(gistId)})"
                )
            }
        } else if (readStrategy == GistReadStrategy.Unknown) {
            readStrategy = GistReadStrategy.AnonymousRaw
            LauncherLog.write("cloud", "read path = ANONYMOUS RAW (no token available)")
        }
        return getRaw(buildRawUrl(login, gistId, fileName, commitSha, cacheBusterRevision), null)
    }

    fun buildRawUrl(
        login: String,
        gistId: String,
        fileName: String,
        commitSha: String?,
        revision: Int?
    ): String {
        val owner = login.ifEmpty { "anonymous" }
        return if (!commitSha.isNullOrEmpty()) {
            // Pinned: immutable, so deliberately cacheable - no cache-buster.
            "$RAW/${enc(owner)}/${enc(gistId)}/raw/${enc(commitSha)}/${enc(fileName)}"
        } else {
            val base = "$RAW/${enc(owner)}/${enc(gistId)}/raw/${enc(fileName)}"
            if (revision != null) "$base?rev=$revision"
            else "$base?t=${System.currentTimeMillis() / 1000}"
        }
    }

    // ------------------------------------------------------------- transport

    private fun newApiRequest(token: GitHubToken?, url: String): Request.Builder {
        // 🔴 Not a sign-in problem - a programming error. The API surface is authenticated by
        // definition, so refuse to build the request at all rather than send an anonymous one.
        checkNotNull(token) {
            "GistClient: an authenticated GitHub API request was attempted with no token. " +
                "Check the token before calling."
        }
        return token.applyTo(
            Request.Builder()
                .url(url)
                .header("Accept", "application/vnd.github+json")
                .header("X-GitHub-Api-Version", API_VERSION)
                .header("User-Agent", userAgent)
                .header("Accept-Encoding", "gzip")
        )
    }

    /** Anonymous by design: a raw gist URL is meant to work without a token. */
    private fun getRaw(url: String, token: GitHubToken?): String {
        var b = Request.Builder().url(url)
            .header("User-Agent", userAgent)
            .header("Cache-Control", "no-cache")
        if (token != null) b = token.applyTo(b)
        // 🔴 The logged endpoint is never the URL: it carries the gist id, which is a read
        // capability for every save in the gist.
        return sendWithRetry(b.get().build(), "GET gist raw")
    }

    private fun sendJson(request: Request, endpoint: String): kotlinx.serialization.json.JsonElement? {
        val body = sendWithRetry(request, endpoint)
        if (body.isBlank()) return null
        return try {
            LENIENT.parseToJsonElement(body)
        } catch (t: Throwable) {
            // The body itself never goes into the exception: a proxy's HTML error page is exactly
            // the kind of thing that could contain an echoed header.
            throw CloudException(
                CloudFailure.Protocol, "GitHub returned a response that could not be understood.",
                null, endpoint
            )
        }
    }

    private fun sendWithRetry(request: Request, endpoint: String): String {
        var attempt = 0
        while (true) {
            attempt++
            val response = try {
                http.newCall(request).execute()
            } catch (e: java.net.SocketTimeoutException) {
                throw CloudException(
                    CloudFailure.Offline,
                    "GitHub did not respond in time. Check your connection and try again.",
                    null, endpoint, cause = e
                )
            } catch (e: IOException) {
                throw CloudException(
                    CloudFailure.Offline, "Cannot reach GitHub. Check your connection.",
                    null, endpoint, cause = e
                )
            }

            val text: String
            val code: Int
            val retryAfter: Long?
            val failure: CloudFailure
            val message: String
            response.use { r ->
                captureMetadata(r)
                code = r.code
                text = try { r.body?.string().orEmpty() } catch (t: Throwable) { "" }
                if (r.isSuccessful) return text
                val classified = classify(r, text)
                failure = classified.failure
                message = classified.message
                retryAfter = classified.retryAfterSeconds
            }

            val retryable = failure == CloudFailure.ServerError ||
                // 429 gets exactly one retry, and only when the wait is short.
                (failure == CloudFailure.RateLimited && retryAfter != null && retryAfter <= 30 && attempt == 1)

            if (retryable && attempt < MAX_ATTEMPTS) {
                val delayMs = retryAfter?.times(1000)
                    ?: ((1L shl attempt) * 400L + Random.nextLong(0, 400))
                LauncherLog.write("cloud", "$endpoint -> $code, retry $attempt/${MAX_ATTEMPTS - 1} in ${delayMs}ms")
                try { Thread.sleep(delayMs) } catch (ignored: InterruptedException) {}
                continue
            }
            LauncherLog.write("cloud", "$endpoint -> $code $failure")
            throw CloudException(failure, message, code, endpoint, retryAfter)
        }
    }

    private fun captureMetadata(r: okhttp3.Response) {
        r.header("GitHub-Authentication-Token-Expiration")?.let { tokenExpiration = it }
        r.header("x-ratelimit-remaining")?.toIntOrNull()?.let { rateLimitRemaining = it }
    }

    private data class Classified(
        val failure: CloudFailure,
        val message: String,
        val retryAfterSeconds: Long?
    )

    /** §3 invariant 8. Only GitHub's `message` field is ever surfaced, and only after scrubbing. */
    private fun classify(r: okhttp3.Response, body: String): Classified {
        val serverMessage = extractMessage(body)
        var retryAfter = r.header("Retry-After")?.toLongOrNull()
            ?.let { min(max(it, 0L), 3600L) }
        val rateLimited = r.header("x-ratelimit-remaining") == "0"
        val code = r.code

        if (code == 401) {
            return Classified(
                CloudFailure.Unauthorized,
                "GitHub rejected this token. It may be expired, revoked, or mistyped.", retryAfter
            )
        }
        if (code == 429 || (code == 403 && (rateLimited || retryAfter != null))) {
            if (retryAfter == null) {
                r.header("x-ratelimit-reset")?.toLongOrNull()?.let { epoch ->
                    val delta = epoch - System.currentTimeMillis() / 1000
                    retryAfter = min(max(if (delta > 0) delta else 1L, 0L), 3600L)
                }
            }
            return Classified(
                CloudFailure.RateLimited,
                "GitHub is rate-limiting this app. Try again in a few minutes.", retryAfter
            )
        }
        if (code == 403) {
            return if (serverMessage?.contains("Resource not accessible", ignoreCase = true) == true) {
                Classified(
                    CloudFailure.MissingGistPermission,
                    "This token cannot create or update gists. On GitHub, set " +
                        "Account permissions → Gists → Read and write.", retryAfter
                )
            } else {
                Classified(
                    CloudFailure.MissingGistPermission,
                    serverMessage ?: "GitHub refused this request.", retryAfter
                )
            }
        }
        if (code == 404) {
            return Classified(CloudFailure.NotFound, "GitHub could not find that gist.", retryAfter)
        }
        if (code == 422) {
            return Classified(
                CloudFailure.Rejected,
                "GitHub rejected the upload" + (if (serverMessage == null) "." else ": $serverMessage"),
                retryAfter
            )
        }
        if (code >= 500) {
            return Classified(CloudFailure.ServerError, "GitHub is having trouble. Try again later.", retryAfter)
        }
        return Classified(
            CloudFailure.Protocol,
            serverMessage ?: "GitHub returned an unexpected status ($code).", retryAfter
        )
    }

    private fun extractMessage(body: String): String? {
        if (body.isBlank()) return null
        return try {
            val m = LENIENT.parseToJsonElement(body).jsonObject["message"]?.jsonPrimitive?.contentOrNull
            m?.let { CloudException.scrub(it) }
        } catch (t: Throwable) {
            // An unparseable body is DISCARDED, not quoted.
            null
        }
    }

    // -------------------------------------------------------------- parsing

    private fun filesNode(files: Map<String, String?>): JsonObject = buildJsonObject {
        for ((name, content) in files) {
            if (content == null) {
                put(name, JsonNull) // null value = delete this file in a PATCH
            } else {
                put(name, buildJsonObject { put("content", JsonPrimitive(content)) })
            }
        }
    }

    private fun parseGist(o: JsonObject): GistSnapshot {
        val files = LinkedHashMap<String, GistFile>()
        (o["files"] as? JsonObject)?.forEach { (key, v) ->
            val fo = v as? JsonObject ?: return@forEach
            val name = fo["filename"]?.jsonPrimitive?.contentOrNull ?: key
            files[name] = GistFile(
                name = name,
                size = fo["size"]?.jsonPrimitive?.longOrNull ?: 0L,
                truncated = fo["truncated"]?.jsonPrimitive?.booleanOrNull ?: false,
                content = fo["content"]?.jsonPrimitive?.contentOrNull,
                rawUrl = fo["raw_url"]?.jsonPrimitive?.contentOrNull
            )
        }
        val commit = try {
            (o["history"] as? kotlinx.serialization.json.JsonArray)
                ?.firstOrNull()?.jsonObject?.get("version")?.jsonPrimitive?.contentOrNull
        } catch (t: Throwable) {
            null
        }
        return GistSnapshot(
            id = o["id"]?.jsonPrimitive?.contentOrNull ?: "",
            ownerLogin = (o["owner"] as? JsonObject)?.get("login")?.jsonPrimitive?.contentOrNull ?: "",
            description = o["description"]?.jsonPrimitive?.contentOrNull ?: "",
            isPublic = o["public"]?.jsonPrimitive?.booleanOrNull ?: false,
            latestCommit = commit,
            files = files
        )
    }

    private fun shortCommit(c: String?): String =
        if (c.isNullOrEmpty()) "-" else if (c.length <= 7) c else c.substring(0, 7)

    private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    companion object {
        const val API = "https://api.github.com"
        const val RAW = "https://gist.githubusercontent.com"
        const val API_VERSION = "2022-11-28"
        const val MAX_ATTEMPTS = 3

        private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
        private val LENIENT = Json { ignoreUnknownKeys = true; isLenient = true }
    }
}
