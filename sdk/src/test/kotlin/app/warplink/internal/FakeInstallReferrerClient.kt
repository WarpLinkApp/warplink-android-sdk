package app.warplink.internal

import android.os.Bundle
import android.os.RemoteException
import com.android.installreferrer.api.InstallReferrerClient
import com.android.installreferrer.api.InstallReferrerStateListener
import com.android.installreferrer.api.ReferrerDetails

/**
 * A Play Install Referrer client a test can steer.
 *
 * Hand-written rather than mocked, in the style of [app.warplink.LoopbackJsonServer]:
 * the SDK ships no third-party test doubles, and the real client cannot be
 * driven without a Play Store.
 *
 * Every branch of [InstallReferrerReader] is a failure branch, so these are the
 * cases that matter:
 *  - [respondWith] a non-OK response code
 *  - [respondWith] OK carrying a referrer string, or one that is not ours
 *  - [failOnRead] so `getInstallReferrer` throws after an OK
 *  - [failOnStart] so `startConnection` throws
 *  - [neverRespond] so the reader's own timeout is the only thing left
 *  - [disconnect] so the service drops before answering
 */
internal class FakeInstallReferrerClient private constructor(
    private val responseCode: Int?,
    private val referrer: String?,
    private val failOnRead: Boolean,
    private val failOnStart: Boolean,
    private val disconnect: Boolean
) : InstallReferrerClient() {

    var endConnectionCalls = 0
        private set

    override fun isReady(): Boolean = responseCode == InstallReferrerResponseCodes.OK

    override fun startConnection(listener: InstallReferrerStateListener) {
        if (failOnStart) throw IllegalStateException("Service is already connected")
        if (disconnect) {
            listener.onInstallReferrerServiceDisconnected()
            return
        }
        // null means "never answer", which leaves the reader's timeout as the
        // only path out.
        responseCode?.let { listener.onInstallReferrerSetupFinished(it) }
    }

    override fun endConnection() {
        endConnectionCalls++
    }

    override fun getInstallReferrer(): ReferrerDetails {
        if (failOnRead) throw RemoteException("Play Store died")
        return ReferrerDetails(
            Bundle().apply { putString("install_referrer", referrer.orEmpty()) }
        )
    }

    companion object {
        fun respondWith(code: Int, referrer: String? = null) =
            FakeInstallReferrerClient(code, referrer, false, false, false)

        fun failingRead() = FakeInstallReferrerClient(
            InstallReferrerResponseCodes.OK, null, true, false, false
        )

        fun failingStart() =
            FakeInstallReferrerClient(null, null, false, true, false)

        fun neverResponding() =
            FakeInstallReferrerClient(null, null, false, false, false)

        fun disconnecting() =
            FakeInstallReferrerClient(null, null, false, false, true)
    }
}
