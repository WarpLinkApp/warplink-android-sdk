package app.warplink.internal

import android.content.Context
import android.os.Build
import android.os.LocaleList
import android.util.DisplayMetrics
import android.view.WindowManager
import app.warplink.WarpLink
import java.util.Locale
import java.util.TimeZone

internal class FingerprintCollector(private val context: Context) {

    fun collectFingerprint(
        callback: (Result<DeviceSignals>) -> Unit
    ) {
        val result = try {
            val signals = DeviceSignals(
                acceptLanguage = buildAcceptLanguage(),
                screenWidth = getScreenWidth(),
                screenHeight = getScreenHeight(),
                timezoneOffset = getTimezoneOffset(),
                userAgent = "WarpLink-Android/${WarpLink.SDK_VERSION}"
            )
            Result.success(signals)
        } catch (e: Exception) {
            Result.failure(e)
        }
        callback(result)
    }

    private fun buildAcceptLanguage(): String {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            val localeList = LocaleList.getDefault()
            (0 until localeList.size())
                .map { localeList[it].toLanguageTag() }
                .joinToString(", ")
        } else {
            Locale.getDefault().toLanguageTag()
        }
    }

    private fun getScreenWidth(): Int {
        val metrics = getDisplayMetrics()
        return metrics.widthPixels
    }

    private fun getScreenHeight(): Int {
        val metrics = getDisplayMetrics()
        return metrics.heightPixels
    }

    @Suppress("DEPRECATION")
    private fun getDisplayMetrics(): DisplayMetrics {
        val wm = context.getSystemService(Context.WINDOW_SERVICE)
            as WindowManager
        val metrics = DisplayMetrics()
        wm.defaultDisplay.getMetrics(metrics)
        return metrics
    }

    private fun getTimezoneOffset(): Int {
        return -(TimeZone.getDefault().rawOffset / 60000)
    }
}
