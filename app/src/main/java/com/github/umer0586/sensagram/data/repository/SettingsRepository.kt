package com.github.umer0586.sensagram.data.repository

import com.github.umer0586.sensagram.data.model.Setting
import kotlinx.coroutines.flow.Flow


interface SettingsRepository {
    suspend fun saveSetting(setting: Setting)
    val setting : Flow<Setting>

    /**
     * Returns the persistent device identifier, generating and storing a new
     * random UUID on the very first call.  The same value is returned on every
     * subsequent call regardless of IP address, reconnections, or app restarts.
     */
    suspend fun getOrCreateDeviceId(): String
}

