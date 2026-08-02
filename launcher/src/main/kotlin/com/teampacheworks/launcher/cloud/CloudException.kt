package com.teampacheworks.launcher.cloud

/** The §3 failure table. */
enum class CloudFailure {
    /** DNS / socket / TLS / no network. */
    Offline,

    /** 401 - dead token. */
    Unauthorized,

    /** 403 "Resource not accessible" - the token lacks Gists read/write. */
    MissingGistPermission,

    /** 429, or 403 with a rate-limit signal. [CloudException.retryAfterSeconds] may be set. */
    RateLimited,

    /** 404. */
    NotFound,

    /** 422 - GitHub rejected the request body. */
    Rejected,

    /** 5xx. */
    ServerError,

    /** A well-formed HTTP response whose body is not what the API documents. */
    Protocol,

    /** The payload did not decode, or its SHA-256 does not match the manifest. */
    Integrity,

    /** Local disk / filesystem. */
    Local
}

/**
 * A cloud failure, carrying deliberately little (design spec §3).
 *
 * What it holds: the failure class, the status code, and the **endpoint template**
 * (`PATCH /gists/{id}` - the id already elided). What it never holds: a response body. GitHub's
 * `message` field is the single exception and it is regex-scrubbed on the way in, because a gist
 * URL is a read capability for every save in the gist.
 */
class CloudException(
    val failure: CloudFailure,
    message: String?,
    val statusCode: Int? = null,
    val endpoint: String? = null,
    val retryAfterSeconds: Long? = null,
    cause: Throwable? = null
) : Exception(scrub(message), cause) {

    fun toLogLine(): String =
        "$failure status=${statusCode ?: "-"} endpoint=${endpoint ?: "-"} :: $message"

    companion object {
        private const val REDACTED = "***REDACTED***"

        /**
         * Same families as [com.teampacheworks.launcher.log.LauncherLog.scrub]. Duplicated on
         * purpose rather than shared: LauncherLog is the last-resort net for strings this layer
         * never sees, this one is the first-resort net for strings this layer constructs. Neither
         * should depend on the other still existing.
         */
        private val CREDENTIALS =
            Regex("github_pat_[A-Za-z0-9_]{16,}|gh[pousr]_[A-Za-z0-9]{16,}")

        fun scrub(text: String?): String {
            if (text.isNullOrEmpty()) return "Cloud operation failed."
            return CREDENTIALS.replace(text, REDACTED)
        }

        /** Gist ids are bearer capabilities; only a 7-char prefix may ever reach a log line. */
        fun maskGistId(id: String?): String {
            if (id.isNullOrEmpty()) return "-"
            return if (id.length <= 7) id else id.substring(0, 7) + "…"
        }
    }
}
