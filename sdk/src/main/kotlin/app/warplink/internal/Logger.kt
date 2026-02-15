package app.warplink.internal

import android.util.Log

internal class Logger(private val debugEnabled: Boolean) {

    fun log(message: String) {
        if (debugEnabled) {
            Log.d(TAG, message)
        }
    }

    companion object {
        private const val TAG = "WarpLink"

        fun maskApiKey(key: String): String {
            if (key.length <= 3) return "***"
            return "${"*".repeat(key.length - 3)}${key.takeLast(3)}"
        }
    }
}
