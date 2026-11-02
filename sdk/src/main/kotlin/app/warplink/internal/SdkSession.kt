package app.warplink.internal

import android.app.Application
import android.content.Context
import android.content.pm.ApplicationInfo
import android.net.Uri
import app.warplink.WarpLinkDeepLink
import app.warplink.WarpLinkOptions

/**
 * Everything one `configure` call builds, replaced as a whole by the next one.
 *
 * Callers read it as a single snapshot, so the gates, the handler and the
 * clients one operation uses never come from two different `configure` calls.
 */
internal class SdkSession(
    val options: WarpLinkOptions,
    val logger: Logger,
    val storage: Storage,
    val apiClient: ApiClient,
    val fingerprintCollector: FingerprintCollector,
    val installReferrerReader: InstallReferrerReader,
    val autoHandler: AutoLinkHandler?,
) {

    fun deferredCheckInputs(settings: RetrySettings) = DeferredCheckInputs(
        storage, fingerprintCollector, apiClient, installReferrerReader, logger, settings
    )

    companion object {

        /** Builds the session for [apiKey]; [resolve] answers the automatic handler's links. */
        fun create(
            context: Context,
            apiKey: String,
            options: WarpLinkOptions,
            resolve: (Uri, (Result<WarpLinkDeepLink>) -> Unit) -> Unit,
        ): SdkSession {
            val logger = Logger(options.debugLogging)
            return SdkSession(
                options = options,
                logger = logger,
                storage = Storage(context, KeyEnvironment.of(apiKey)),
                apiClient = ApiClient(apiKey, options.apiEndpoint, context.packageName),
                fingerprintCollector = FingerprintCollector(),
                installReferrerReader = InstallReferrerReader(context, logger),
                autoHandler = options.onLink?.let { sink ->
                    AutoLinkHandler(
                        application = context as? Application,
                        logger = logger,
                        isWarpLinkUri = UriParser::isWarpLinkUri,
                        onLink = sink,
                        resolve = resolve
                    )
                }
            )
        }
    }
}

/**
 * The device-seen marker lives in SharedPreferences and outlives an uninstall
 * only because Auto Backup restores it. With backup off, a reinstall on this
 * device is reported as a first install. Attribution itself is unaffected, so
 * this is a debug line rather than a warning.
 */
internal fun logBackupCaveat(context: Context, logger: Logger) {
    if (context.applicationInfo.flags and ApplicationInfo.FLAG_ALLOW_BACKUP == 0) {
        logger.log(
            "Auto Backup is disabled (android:allowBackup=false): a reinstall on " +
                "this device will be reported as a first install"
        )
    }
}
