package app.warplink.internal

import android.content.Context
import android.content.pm.PackageManager

/**
 * When THIS install was first installed. Android sets it once per install and
 * never moves it on an update; a reinstall gets a new one. Auto Backup restores
 * app DATA, never the package install record, so a value stored in
 * SharedPreferences and carried onto a reinstall no longer matches.
 *
 * That is what makes it the right key for any per-install fact that has to
 * live in SharedPreferences; Task 15 binds the gate fallback to it. It is never
 * used to decide whether a check already ran: 1.0.x's flag proved only that a check began, and gating on it
 * locked every upgrade out for good.
 */
internal fun readFirstInstallTime(context: Context): Long = try {
    context.packageManager.getPackageInfo(context.packageName, 0).firstInstallTime
} catch (_: PackageManager.NameNotFoundException) {
    // The package always exists while its own code is running. Zero can never
    // equal a real timestamp, so the impossible case can never close a gate.
    0L
}
