package com.unshoo.pixelmusic.utils

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings

/**
 * Vivo / iQOO / OPPO / realme / OnePlus (ColorOS) kill an app's whole process when it is swiped out
 * of Recents unless it is allowed "High background power consumption" / "Allow background
 * activity" in the vendor's own power manager. Android's battery-optimisation exemption does not
 * cover that, and no service code can block the kill. This opens the vendor screen directly.
 */
object OemBackgroundSettings {

    private val brand: String
        get() = (Build.MANUFACTURER + " " + Build.BRAND).lowercase()

    val isAggressiveRecentsKiller: Boolean
        get() = listOf("vivo", "iqoo", "oppo", "realme", "oneplus").any { it in brand }

    val isVivoFamily: Boolean
        get() = "vivo" in brand || "iqoo" in brand

    val showBackgroundActivitySetting: Boolean
        get() = isAggressiveRecentsKiller && !isVivoFamily

    private fun vendorIntents(context: Context): List<Intent> {
        val pkg = context.packageName
        return when {
            isVivoFamily -> listOf(
                // "High background power consumption" (Funtouch OS / Origin OS)
                Intent().setComponent(ComponentName("com.vivo.abe", "com.vivo.applicationbehaviorengine.ui.ExcessivePowerManagerActivity")),
                Intent().setComponent(ComponentName("com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.BgStartUpManager")),
                Intent().setComponent(ComponentName("com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.BgStartUpManagerActivity")),
                Intent().setComponent(ComponentName("com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity")),
            )
            else -> listOf(
                // ColorOS / realme UI / OxygenOS: app battery usage -> allow background activity
                Intent().setComponent(ComponentName("com.coloros.oppoguardelf", "com.coloros.powermanager.fuelgaue.PowerUsageModelActivity")),
                Intent().setComponent(ComponentName("com.oplus.battery", "com.oplus.powermanager.fuelgaue.PowerControlActivity")),
                Intent().setComponent(ComponentName("com.coloros.safecenter", "com.coloros.safecenter.startupapp.StartupAppListActivity")),
            )
        } + Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$pkg"))
    }

    /** Opens the most specific vendor screen available. Returns false if nothing could be opened. */
    fun open(context: Context): Boolean {
        for (intent in vendorIntents(context)) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            val opened = runCatching { context.startActivity(intent) }.isSuccess
            if (opened) return true
        }
        return false
    }
}
