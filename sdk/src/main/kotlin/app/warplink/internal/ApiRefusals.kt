package app.warplink.internal

import app.warplink.WarpLinkError
import org.json.JSONException
import org.json.JSONObject
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.net.HttpURLConnection

/** The API's error code for a link whose password was not supplied. */
private const val PASSWORD_REQUIRED = "PASSWORD_REQUIRED"

/**
 * Tells the two 403s apart. Resolve answers 403 for a rejected key AND for
 * a password protected link, and only the body's error code separates
 * them. A refusal the SDK cannot read keeps the older meaning rather than
 * accusing every 403 of being password protected.
 */
internal fun refusalFor(conn: HttpURLConnection): WarpLinkError {
    return if (errorCodeOf(conn) == PASSWORD_REQUIRED) {
        WarpLinkError.PasswordRequired
    } else {
        WarpLinkError.InvalidApiKey
    }
}

/**
 * The API's error code, from the documented `{"error":{"code":...}}` body.
 *
 * A refusal has no body on `inputStream`; reading it throws. Returns null
 * when the body is absent or unreadable, which the caller maps to the
 * generic refusal. Deliberately not rethrown: a malformed error body would
 * otherwise surface as a decoding failure and hide the refusal itself.
 */
private fun errorCodeOf(conn: HttpURLConnection): String? {
    return try {
        val body = conn.errorStream?.let { stream ->
            BufferedReader(InputStreamReader(stream, Charsets.UTF_8))
                .use { it.readText() }
        } ?: return null
        JSONObject(body).optJSONObject("error")?.optStringOrNull("code")
    } catch (_: JSONException) {
        null
    } catch (_: IOException) {
        null
    }
}
