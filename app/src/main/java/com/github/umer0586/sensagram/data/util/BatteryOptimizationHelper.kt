/*
 *     This file is a part of SensaGram (https://github.com/UmerCodez/SensaGram)
 *
 *     SensaGram is free software: you can redistribute it and/or modify
 *     it under the terms of the GNU General Public License as published by
 *     the Free Software Foundation, either version 3 of the License, or
 *     (at your option) any later version.
 */

package com.github.umer0586.sensagram.data.util

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings

/**
 * Returns true if the app is already excluded from battery optimisation.
 * Always returns true on API < 23 (Doze does not exist below M).
 */
fun Context.isIgnoringBatteryOptimizations(): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true
    val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
    return pm.isIgnoringBatteryOptimizations(packageName)
}

/**
 * Returns an Intent that opens the system "Disable battery optimisation" dialog
 * directly for this app.  The user sees a single-tap Allow/Deny prompt.
 *
 * This works on stock Android and on most OEM skins (Xiaomi HyperOS, Samsung One UI,
 * etc.) because it targets the standard ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS
 * action which all Android 6+ devices must implement.
 */
fun Context.buildBatteryOptimizationIntent(): Intent =
    Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
        data = Uri.parse("package:$packageName")
    }
