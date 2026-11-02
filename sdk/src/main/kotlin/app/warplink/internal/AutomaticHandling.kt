package app.warplink.internal

import app.warplink.WarpLinkDeepLink

/**
 * Starts the cold-start link handler and the automatic deferred check that
 * [SdkSession.options] ask for.
 *
 * Works on one session snapshot, so the two gates and the handler cannot come
 * from different `configure` calls. A host that reconfigures from an abandoned
 * check's answer replaces the session behind this call.
 *
 * [checkDeferred] runs the deferred check and [onDeferred] routes its result.
 */
internal fun SdkSession.startAutomaticHandling(
    checkDeferred: ((Result<WarpLinkDeepLink?>) -> Unit) -> Unit,
    onDeferred: (Result<WarpLinkDeepLink?>) -> Unit,
) {
    if (options.automaticDeepLinks) {
        if (autoHandler != null) {
            autoHandler.registerColdStart()
            logger.log("Automatic cold-start deep link handling enabled")
        } else {
            logger.log("automaticDeepLinks enabled but no onLink set; nothing to dispatch to")
        }
    }
    fireAutomaticDeferredCheck(checkDeferred, onDeferred)
}

/**
 * Runs the automatic deferred check when the options ask for it and this
 * install has not completed one. Also the restart after `clearAttribution`.
 */
internal fun SdkSession.fireAutomaticDeferredCheck(
    checkDeferred: ((Result<WarpLinkDeepLink?>) -> Unit) -> Unit,
    onDeferred: (Result<WarpLinkDeepLink?>) -> Unit,
) {
    if (!options.automaticDeferredDeepLinks) return
    // A completed deferred result is delivered at most once. A cached match
    // stays readable via attributionResult but is not re-pushed through onLink
    // on every later launch.
    if (storage.deferredCheckCompleted) {
        logger.log("Deferred check already completed; not re-dispatching to onLink")
        return
    }
    // Fires with or without a sink: a bare configure(context, apiKey) attributes
    // the install automatically, and the match is recorded server-side and cached
    // locally either way. A manual caller that races this one is coalesced onto it.
    logger.log("Auto-firing deferred deep link check")
    checkDeferred(onDeferred)
}
