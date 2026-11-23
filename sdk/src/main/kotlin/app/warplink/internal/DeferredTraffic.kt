package app.warplink.internal

import android.content.SharedPreferences
import org.json.JSONException
import org.json.JSONObject
import java.io.File

/**
 * Everything the deferred check keeps to limit its own traffic: the policy the
 * server sent, this install's id, and its attempts and failures.
 *
 * A `wl_test_` key is exempt from every rule, so a developer can test a first
 * launch as often as they like. Test and Production keep separate files.
 */
internal class DeferredTraffic(
    directory: File,
    private val environment: KeyEnvironment,
    private val prefs: SharedPreferences,
    private val firstInstallTimeMs: Long,
) {

    private val attempts = AttemptLog(directory, environment.scoped(ATTEMPTS_FILE, ".json"))
    private val ids = InstallationIdStore(directory, environment.scoped(ID_FILE, ".json"))

    val installationId: String get() = ids.id

    /** The server's last policy, or the built-in one. */
    var policy: SdkPolicy
        get() = prefs.getString(POLICY, null)?.let(::parsePolicy) ?: SdkPolicy.DEFAULT
        set(value) = prefs.edit().putString(POLICY, value.toJson().toString()).apply()

    val skippedForAge: Boolean get() = attempts.snapshot().skippedForAge

    /** Failed checks since the last 200 answer. */
    val failureCount: Int get() = attempts.snapshot().failures.count

    /** The age of this install in seconds. Zero when the platform could not say. */
    fun installAgeSeconds(nowMs: Long): Long =
        if (firstInstallTimeMs <= 0L) 0L else ((nowMs - firstInstallTimeMs) / 1_000).coerceAtLeast(0L)

    fun decide(nowMs: Long): TrafficDecision {
        if (environment == KeyEnvironment.TEST) return TrafficDecision.Proceed(sendRawSignals = true)
        return decideTraffic(policy, installAgeSeconds(nowMs), attempts.snapshot().failures, nowMs)
    }

    /** The first moment a check may send again after the failures so far, in epoch milliseconds. */
    fun nextRetryAtMs(): Long = retryAtMs(policy, attempts.snapshot().failures)

    fun beginAttempt(nowMs: Long): AttemptGrant = attempts.begin(nowMs)

    fun peekNextAttemptNumber(): Int = attempts.peekNextNumber()

    fun undoAttempt(grant: AttemptGrant) = attempts.undo(grant)

    /** A 200 answer was applied: the failures no longer count. */
    fun recordSuccess() = attempts.recordSuccess()

    fun noteSkippedForAge() = attempts.noteSkippedForAge()

    /** Forgets attempts, failures and the age note, keeping the id and the policy. */
    fun resetAttempts(): Boolean = attempts.clear()

    /** Forgets everything, the id and the policy included. */
    fun clear(): Boolean {
        prefs.edit().remove(POLICY).apply()
        return attempts.clear() and ids.clear()
    }

    private fun parsePolicy(text: String): SdkPolicy? = try {
        SdkPolicy.fromJson(JSONObject(text))
    } catch (_: JSONException) {
        null
    }

    private companion object {
        const val POLICY = "sdk_policy"
        const val ATTEMPTS_FILE = "warplink_deferred_attempts"
        const val ID_FILE = "warplink_installation_id"
    }
}
