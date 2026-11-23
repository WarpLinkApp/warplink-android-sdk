package app.warplink.internal

import android.net.Uri
import app.warplink.WarpLinkDeepLink

/**
 * The dedupe state of [AutoLinkHandler]: which dispatch owns a URI inside the
 * dedupe window, and the callers parked on it. It has its own lock, so every
 * method is safe from any thread.
 */
internal class LinkClaims(private val now: () -> Long) {

    private val lock = Any()
    private var lastUri: String? = null
    private var lastAt: Long = 0L
    private var lastResult: Result<WarpLinkDeepLink>? = null

    /**
     * Bumped by every claim, and captured by the dispatch that took it.
     *
     * The URI alone cannot identify a claim. A re-tap of the SAME link past the
     * dedupe window takes a FRESH claim under the same URI, so the older tap
     * read `key == lastUri` as its own, dropped the newer tap's claim on its
     * way out, and let the newer tap's duplicate through. That duplicate
     * resolved again under a new tap id, which billed a second click for one
     * tap. A counter tells the two claims apart; a string cannot.
     */
    private var claimToken = 0L
    private val waiting =
        mutableMapOf<String, MutableList<(Result<WarpLinkDeepLink>) -> Unit>>()

    /**
     * Take ownership of dispatching [uri], and hand back the token for the
     * claim just taken. Null when the same URI was already claimed inside the
     * dedupe window (auto plus a manual `handleDeepLink` for one link), meaning
     * it must not be resolved a second time.
     */
    fun claim(uri: Uri): Long? = synchronized(lock) { claimLocked(uri) }

    /**
     * Like [claim], and when the URI is a duplicate, parks [callback] to be
     * answered by the claiming dispatch. Returns the token, or null for a
     * duplicate, in which case [settled] holds the result already known, if any.
     */
    fun claimOrPark(
        uri: Uri,
        callback: (Result<WarpLinkDeepLink>) -> Unit,
    ): Claim = synchronized(lock) {
        val token = claimLocked(uri)
        if (token != null) return@synchronized Claim(token, null)
        val settled = lastResult
        if (settled == null) waitersFor(uri).add(callback)
        Claim(null, settled)
    }

    /** Whether a newer claim has been taken since [token]. */
    fun isSuperseded(token: Long): Boolean = synchronized(lock) { token != claimToken }

    /** Publish a dispatch's result to any caller parked on the same URI. */
    fun settle(uri: Uri, token: Long, result: Result<WarpLinkDeepLink>) {
        val key = uri.toString()
        val parked = synchronized(lock) {
            // A newer claim may have been taken meanwhile, for this URI or for
            // another; only the current claim's own result may touch the claim.
            if (token == claimToken) {
                if (result.isSuccess) {
                    lastResult = result
                } else {
                    // A failed resolve (offline, server error) is not a
                    // duplicate to suppress: drop the claim so a host retrying
                    // the same link really re-resolves instead of being handed
                    // the cached failure back for the rest of the window.
                    lastUri = null
                    lastResult = null
                }
            }
            // Drained whatever the token says. A caller parked on this URI is
            // owed an answer by whichever dispatch settles first, and a claim
            // taken for a DIFFERENT URI would otherwise leave it parked for the
            // life of the process.
            waiting.remove(key).orEmpty()
        }
        parked.forEach { it(result) }
    }

    /** The outcome of [claimOrPark]: a [token] when the caller owns the dispatch, else the [settled] result if known. */
    data class Claim(val token: Long?, val settled: Result<WarpLinkDeepLink>?)

    /** Call under [lock]. */
    private fun claimLocked(uri: Uri): Long? {
        val key = uri.toString()
        val at = now()
        // The range, rather than a bare `<`, states the invariant the clock
        // gives us. Anything outside it is a fresh tap, so a source that ever
        // did run backwards would dispatch rather than silently swallow.
        if (key == lastUri && (at - lastAt) in 0 until DEDUP_WINDOW_MS) {
            return null
        }
        lastUri = key
        lastAt = at
        lastResult = null
        claimToken += 1
        return claimToken
    }

    /** Call under [lock]. */
    private fun waitersFor(uri: Uri) =
        waiting.getOrPut(uri.toString()) { mutableListOf() }

    private companion object {
        const val DEDUP_WINDOW_MS = 1500L
    }
}
