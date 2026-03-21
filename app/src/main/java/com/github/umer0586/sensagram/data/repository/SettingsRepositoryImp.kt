/*
 *     This file is a part of SensaGram (https://github.com/UmerCodez/SensaGram)
 *     Copyright (C) 2024 Umer Farooq (umerfarooq2383@gmail.com)
 *
 *     SensaGram is free software: you can redistribute it and/or modify
 *     it under the terms of the GNU General Public License as published by
 *     the Free Software Foundation, either version 3 of the License, or
 *     (at your option) any later version.
 *
 *     SensaGram is distributed in the hope that it will be useful,
 *     but WITHOUT ANY WARRANTY; without even the implied warranty of
 *     MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *     GNU General Public License for more details.
 *
 *     You should have received a copy of the GNU General Public License
 *     along with SensaGram.  If not, see <https://www.gnu.org/licenses/>.
 *
 */

package com.github.umer0586.sensagram.data.repository

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorManager
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.github.umer0586.sensagram.data.model.Setting
import com.github.umer0586.sensagram.data.model.toDeviceSensor
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.util.UUID

//The delegate will ensure that we have a single instance of DataStore with that name in our application.
private val Context.userPreferencesDataStore: DataStore<Preferences> by preferencesDataStore("user_pref")


class SettingsRepositoryImp(
    private val context: Context,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) : SettingsRepository {

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager

    private val KEY_IP_ADDRESS = stringPreferencesKey("IP_ADDRESS")
    private val KEY_PORT_NO = intPreferencesKey("PORT_NO")
    private val KEY_SENSOR_LIST = stringPreferencesKey("SENSOR_LIST")
    private val KEY_SAMPLING_RATE = intPreferencesKey("SAMPLING_RATE")
    private val KEY_STREAM_ON_BOOT = booleanPreferencesKey("STREAM_ON_BOOT")
    private val KEY_GPS_STREAMING = booleanPreferencesKey("GPS_STREAMING")
    private val KEY_SEND_INTERVAL_MS = intPreferencesKey("SEND_INTERVAL_MS")
    private val KEY_USE_TCP          = booleanPreferencesKey("USE_TCP")
    // Stable device identifier — written once, never changed or exposed in the UI.
    private val KEY_DEVICE_ID        = stringPreferencesKey("DEVICE_ID")

    private val DEFAULT_IP = "127.0.0.1"
    private val DEFAULT_PORT = 47892
    private val DEFAULT_SAMPLING_RATE = 20000
    private val DEFAULT_STREAM_ON_BOOT = false
    private val DEFAULT_GPS_STREAMING = false
    private val DEFAULT_SEND_INTERVAL_MS = 500
    private val DEFAULT_USE_TCP = false

    override suspend fun saveSetting(setting: Setting) = withContext<Unit>(ioDispatcher) {
        context.userPreferencesDataStore.edit { pref ->
            pref[KEY_IP_ADDRESS] = setting.ipAddress
            pref[KEY_PORT_NO] = setting.portNo
            pref[KEY_SENSOR_LIST] = setting.selectedSensors.map { it.stringType }.joinToString(separator = ",")
            pref[KEY_SAMPLING_RATE] = setting.samplingRate
            pref[KEY_STREAM_ON_BOOT] = setting.streamOnBoot
            pref[KEY_GPS_STREAMING] = setting.gpsStreaming
            pref[KEY_SEND_INTERVAL_MS] = setting.sendIntervalMs
            pref[KEY_USE_TCP]          = setting.useTcp
        }
    }

    override val setting: Flow<Setting>
        get() = context.userPreferencesDataStore.data.map { pref ->
            Setting(
                ipAddress = pref[KEY_IP_ADDRESS] ?: DEFAULT_IP,
                portNo = pref[KEY_PORT_NO] ?: DEFAULT_PORT,
                selectedSensors = pref[KEY_SENSOR_LIST]?.split(",")?.mapNotNull { sensorType ->
                    sensorManager.getSensorFromStringType(sensorType)?.toDeviceSensor()
                } ?: emptyList(),
                samplingRate = pref[KEY_SAMPLING_RATE] ?: DEFAULT_SAMPLING_RATE,
                streamOnBoot = pref[KEY_STREAM_ON_BOOT] ?: DEFAULT_STREAM_ON_BOOT,
                gpsStreaming = pref[KEY_GPS_STREAMING] ?: DEFAULT_GPS_STREAMING,
                sendIntervalMs = pref[KEY_SEND_INTERVAL_MS] ?: DEFAULT_SEND_INTERVAL_MS,
                useTcp         = pref[KEY_USE_TCP]          ?: DEFAULT_USE_TCP
            )
        }.flowOn(ioDispatcher)

    private fun SensorManager.getSensorFromStringType(sensorStringType: String): Sensor? {
        return getSensorList(Sensor.TYPE_ALL).firstOrNull {
            it.stringType.equals(
                sensorStringType,
                ignoreCase = true
            )
        }

    }

    /**
     * Returns the persistent device identifier.
     *
     * On the very first call the DataStore has no entry for DEVICE_ID, so a
     * random UUID is generated, written, and returned.  Every subsequent call
     * (across sessions, reconnections, and IP changes) returns the same value.
     *
     * The ID is never shown in the UI and is never written by [saveSetting],
     * so user edits to other settings cannot accidentally overwrite it.
     */
    override suspend fun getOrCreateDeviceId(): String = withContext(ioDispatcher) {
        val prefs = context.userPreferencesDataStore.data.first()
        val existing = prefs[KEY_DEVICE_ID]
        if (!existing.isNullOrBlank()) return@withContext existing

        val newId = UUID.randomUUID().toString()
        context.userPreferencesDataStore.edit { it[KEY_DEVICE_ID] = newId }
        newId
    }
}