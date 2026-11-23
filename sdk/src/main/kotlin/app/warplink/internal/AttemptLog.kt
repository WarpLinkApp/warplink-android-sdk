package app.warplink.internal

import app.warplink.internal.referral.InstallJsonFile
import org.json.JSONObject
import java.io.File

/** What the log holds for this install. [skippedForAge] is a note only and never closes the gate. */
internal data class AttemptRecord(
    val attempts: Int = 0,
    val failures: FailureRecord = FailureRecord(),
    val skippedForAge: Boolean = false,
)

/** The attempt [number] a check run took, and the record before and after it, so an abandoned run can give it back. */
internal class AttemptGrant(val number: Int, val before: AttemptRecord, val after: AttemptRecord)

/**
 * The deferred check's requests on this install, kept in a file under
 * `noBackupFilesDir` so it dies with the install and a reinstall starts clean.
 *
 * A request is recorded as a failure when it is sent and cleared when a 200
 * answer is applied, so a process killed mid-request still counts. When the file
 * cannot be written the record lives in memory for the life of the process.
 */
internal class AttemptLog(directory: File, name: String) {

    private val file = InstallJsonFile(directory, name)
    private val lock = Any()
    private var record: AttemptRecord? = null

    fun snapshot(): AttemptRecord = synchronized(lock) { loaded() }

    /** Counts a request sent at [nowMs] as the next attempt and as a failure until a 200 clears it. */
    fun begin(nowMs: Long): AttemptGrant = synchronized(lock) {
        val before = loaded()
        val failures = before.failures
        val after = before.copy(
            attempts = before.attempts + 1,
            failures = FailureRecord(
                count = failures.count + 1,
                firstAtMs = if (failures.count == 0) nowMs else failures.firstAtMs,
                lastAtMs = nowMs,
            ),
        )
        save(after)
        AttemptGrant(after.attempts, before, after)
    }

    /** The number the next attempt would take, without counting it. */
    fun peekNextNumber(): Int = synchronized(lock) { loaded().attempts + 1 }

    /** Gives [grant] back, unless something else has changed the record since. */
    fun undo(grant: AttemptGrant) = synchronized(lock) {
        if (loaded() == grant.after) save(grant.before)
    }

    fun recordSuccess() = synchronized(lock) {
        val current = loaded()
        if (current.failures.count != 0) save(current.copy(failures = FailureRecord()))
    }

    fun noteSkippedForAge() = synchronized(lock) {
        val current = loaded()
        if (!current.skippedForAge) save(current.copy(skippedForAge = true))
    }

    /** True when the log is gone from disk too. */
    fun clear(): Boolean = synchronized(lock) {
        record = AttemptRecord()
        file.delete()
    }

    private fun loaded(): AttemptRecord = record ?: read().also { record = it }

    private fun save(next: AttemptRecord) {
        record = next
        file.write(next.toJson())
    }

    private fun read(): AttemptRecord {
        val json = file.read() ?: return AttemptRecord()
        return AttemptRecord(
            attempts = json.optInt(ATTEMPTS),
            failures = FailureRecord(
                count = json.optInt(FAILURES),
                firstAtMs = json.optLong(FIRST_FAILURE_AT),
                lastAtMs = json.optLong(LAST_FAILURE_AT),
            ),
            skippedForAge = json.optBoolean(SKIPPED_FOR_AGE),
        )
    }

    private fun AttemptRecord.toJson(): JSONObject = JSONObject()
        .put(ATTEMPTS, attempts)
        .put(FAILURES, failures.count)
        .put(FIRST_FAILURE_AT, failures.firstAtMs)
        .put(LAST_FAILURE_AT, failures.lastAtMs)
        .put(SKIPPED_FOR_AGE, skippedForAge)

    private companion object {
        const val ATTEMPTS = "attempts"
        const val FAILURES = "failures"
        const val FIRST_FAILURE_AT = "first_failure_at_ms"
        const val LAST_FAILURE_AT = "last_failure_at_ms"
        const val SKIPPED_FOR_AGE = "skipped_for_age"
    }
}
