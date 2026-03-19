/*
 *     This file is a part of SensaGram (https://www.github.com/UmerCodez/SensaGram)
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

package com.github.umer0586.sensagram.ui.screens.settings

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.github.umer0586.sensagram.data.model.DEFAULT_GPS_STREAMING
import com.github.umer0586.sensagram.data.model.DEFAULT_IP
import com.github.umer0586.sensagram.data.model.DEFAULT_PORT
import com.github.umer0586.sensagram.data.model.DEFAULT_SAMPLING_RATE
import com.github.umer0586.sensagram.data.model.DEFAULT_STREAM_ON_BOOT
import com.github.umer0586.sensagram.data.model.DEFAULT_SEND_INTERVAL_MS
import com.github.umer0586.sensagram.data.model.DEFAULT_USE_TCP
import com.github.umer0586.sensagram.data.repository.SettingsRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch


data class SettingsScreenUiState(
    val ipAddress: String = DEFAULT_IP,
    val isIpAddressValid: Boolean = true,
    val savedIpAddress: String = DEFAULT_IP,
    val portNo: Int = DEFAULT_PORT,
    val savedPortNo: Int = DEFAULT_PORT,
    val isPortNoValid : Boolean = true,
    val samplingRate: Int = DEFAULT_SAMPLING_RATE,
    val savedSamplingRate: Int = DEFAULT_SAMPLING_RATE,
    val isSamplingRateValid: Boolean = true,
    val streamOnBoot : Boolean = DEFAULT_STREAM_ON_BOOT,
    val gpsStreaming : Boolean = DEFAULT_GPS_STREAMING,
    val sendIntervalMs: Int = DEFAULT_SEND_INTERVAL_MS,
    val savedSendIntervalMs: Int = DEFAULT_SEND_INTERVAL_MS,
    val isSendIntervalValid: Boolean = true,
    val useTcp: Boolean = DEFAULT_USE_TCP,
)

sealed class SettingScreenEvent {
    data class OnPortNoChange(val portNo: Int) : SettingScreenEvent()
    data class OnIpAddressChange(val ipAddress: String) : SettingScreenEvent()
    data class OnSamplingRateChange(val samplingRate: Int) : SettingScreenEvent()
    data class OnSaveIpAddress(val ipAddress: String) : SettingScreenEvent()
    data class OnSavePortNo(val portNo: Int) : SettingScreenEvent()
    data class OnSaveSamplingRate(val samplingRate: Int) : SettingScreenEvent()
    data class OnStreamOnBootChange(val streamOnBoot: Boolean) : SettingScreenEvent()
    data class OnSaveStreamOnBoot(val streamOnBoot: Boolean) : SettingScreenEvent()
    data class OnSendIntervalChange(val sendIntervalMs: Int) : SettingScreenEvent()
    data class OnSaveSendInterval(val sendIntervalMs: Int) : SettingScreenEvent()
    data class OnUseTcpChange(val useTcp: Boolean) : SettingScreenEvent()
    data class OnSaveUseTcp(val useTcp: Boolean) : SettingScreenEvent()
    data class OnGpsStreamingChange(val gpsStreaming: Boolean) : SettingScreenEvent()
    data class OnSaveGpsStreaming(val gpsStreaming: Boolean) : SettingScreenEvent()
}

class SettingsScreenViewModel(private val settingsRepository: SettingsRepository) : ViewModel() {


    private val _uiState = MutableStateFlow(SettingsScreenUiState())
    val uiState = _uiState.asStateFlow()

    private val TAG: String = SettingsScreenViewModel::class.java.getSimpleName()

    companion object {
        private val IPV4_REGEX =
            "^(([0-1]?[0-9]{1,2}\\.)|(2[0-4][0-9]\\.)|(25[0-5]\\.)){3}(([0-1]?[0-9]{1,2})|(2[0-4][0-9])|(25[0-5]))\$".toRegex()

        /**
         * RFC-1123 FQDN: one or more dot-separated labels where each label is
         * 1–63 alphanumeric chars (hyphens allowed mid-label), ending with a
         * recognised TLD of at least 2 chars.  Accepts e.g. "data.example.com".
         */
        private val FQDN_REGEX =
            "^(?:[a-zA-Z0-9](?:[a-zA-Z0-9\\-]{0,61}[a-zA-Z0-9])?\\.)+[a-zA-Z]{2,}\$".toRegex()

        private fun isValidRemoteAddress(input: String): Boolean =
            IPV4_REGEX.matches(input) || FQDN_REGEX.matches(input)
    }


    init {

        Log.d(TAG, "created()")

        viewModelScope.launch {
            settingsRepository.setting.collect { settings ->
                _uiState.update {
                    it.copy(
                        savedIpAddress      = settings.ipAddress,
                        savedPortNo         = settings.portNo,
                        savedSamplingRate   = settings.samplingRate,
                        streamOnBoot        = settings.streamOnBoot,
                        gpsStreaming        = settings.gpsStreaming,
                        savedSendIntervalMs = settings.sendIntervalMs,
                        useTcp              = settings.useTcp,
                    )
                }
            }
        }

    }

    fun onUiEvent(event: SettingScreenEvent) {
        when (event) {
            is SettingScreenEvent.OnIpAddressChange    -> onIpAddressChange(event.ipAddress)
            is SettingScreenEvent.OnPortNoChange       -> onPortNoChange(event.portNo)
            is SettingScreenEvent.OnSamplingRateChange -> onSamplingRateChange(event.samplingRate)
            is SettingScreenEvent.OnSaveIpAddress      -> saveIpAddress(event.ipAddress)
            is SettingScreenEvent.OnSavePortNo         -> savePortNo(event.portNo)
            is SettingScreenEvent.OnSaveSamplingRate   -> saveSamplingRate(event.samplingRate)
            is SettingScreenEvent.OnSaveStreamOnBoot   -> saveStreamOnBoot(event.streamOnBoot)
            is SettingScreenEvent.OnStreamOnBootChange -> onStreamOnBootChange(event.streamOnBoot)
            is SettingScreenEvent.OnSendIntervalChange -> onSendIntervalChange(event.sendIntervalMs)
            is SettingScreenEvent.OnSaveSendInterval   -> saveSendInterval(event.sendIntervalMs)
            is SettingScreenEvent.OnUseTcpChange       -> onUseTcpChange(event.useTcp)
            is SettingScreenEvent.OnSaveUseTcp         -> saveUseTcp(event.useTcp)
            is SettingScreenEvent.OnGpsStreamingChange -> onGpsStreamingChange(event.gpsStreaming)
            is SettingScreenEvent.OnSaveGpsStreaming   -> saveGpsStreaming(event.gpsStreaming)
        }
    }


    private fun onIpAddressChange(ipAddress: String) {
        _uiState.update {
            it.copy(
                ipAddress        = ipAddress,
                isIpAddressValid = isValidRemoteAddress(ipAddress)
            )
        }
    }

    private fun onPortNoChange(portNo: Int) {
        _uiState.update {
            it.copy(
                portNo        = portNo,
                isPortNoValid = portNo in 0..65534
            )
        }
    }

    private fun onSamplingRateChange(samplingRate: Int) {
        _uiState.update {
            it.copy(
                samplingRate        = samplingRate,
                isSamplingRateValid = samplingRate in 0..200000
            )
        }
    }

    private fun onStreamOnBootChange(streamOnBoot: Boolean) {
        _uiState.update {
            it.copy(streamOnBoot = streamOnBoot)
        }
    }

    private fun onSendIntervalChange(sendIntervalMs: Int) {
        _uiState.update {
            it.copy(
                sendIntervalMs      = sendIntervalMs,
                // Valid range: 50 ms (prevent hammering) to 60 000 ms (1 minute)
                isSendIntervalValid = sendIntervalMs in 50..60000
            )
        }
    }

    private fun saveIpAddress(ipAddress: String) {
        viewModelScope.launch {
            val oldSettings = settingsRepository.setting.first()
            settingsRepository.saveSetting(oldSettings.copy(ipAddress = ipAddress))
        }
    }

    private fun savePortNo(portNo: Int) {
        viewModelScope.launch {
            val oldSettings = settingsRepository.setting.first()
            settingsRepository.saveSetting(oldSettings.copy(portNo = portNo))
        }
    }

    private fun saveSamplingRate(samplingRate: Int) {
        viewModelScope.launch {
            val oldSettings = settingsRepository.setting.first()
            settingsRepository.saveSetting(oldSettings.copy(samplingRate = samplingRate))
        }
    }

    private fun saveStreamOnBoot(streamOnBoot: Boolean) {
        viewModelScope.launch {
            val oldSettings = settingsRepository.setting.first()
            settingsRepository.saveSetting(oldSettings.copy(streamOnBoot = streamOnBoot))
        }
    }

    private fun saveSendInterval(sendIntervalMs: Int) {
        viewModelScope.launch {
            val oldSettings = settingsRepository.setting.first()
            settingsRepository.saveSetting(oldSettings.copy(sendIntervalMs = sendIntervalMs))
        }
    }

    private fun onUseTcpChange(useTcp: Boolean) {
        _uiState.update { it.copy(useTcp = useTcp) }
    }

    private fun saveUseTcp(useTcp: Boolean) {
        viewModelScope.launch {
            val oldSettings = settingsRepository.setting.first()
            settingsRepository.saveSetting(oldSettings.copy(useTcp = useTcp))
        }
    }

    private fun onGpsStreamingChange(gpsStreaming: Boolean) {
        _uiState.update { it.copy(gpsStreaming = gpsStreaming) }
    }

    private fun saveGpsStreaming(gpsStreaming: Boolean) {
        viewModelScope.launch {
            val oldSettings = settingsRepository.setting.first()
            settingsRepository.saveSetting(oldSettings.copy(gpsStreaming = gpsStreaming))
        }
    }

}

