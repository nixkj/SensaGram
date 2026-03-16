package com.github.umer0586.sensagram.data.model

const val DEFAULT_IP = "127.0.0.1"
const val DEFAULT_PORT = 8080
const val DEFAULT_SAMPLING_RATE = 20000
const val DEFAULT_STREAM_ON_BOOT = false
const val DEFAULT_GPS_STREAMING = false
// How often (in milliseconds) the app sends a UDP packet with aggregated stats.
// Readings are buffered within each interval; min, max, and std dev are computed per send.
const val DEFAULT_SEND_INTERVAL_MS = 500

data class Setting(
    val ipAddress : String = DEFAULT_IP,
    val portNo : Int = DEFAULT_PORT,
    val selectedSensors : List<DeviceSensor> = emptyList(),
    val samplingRate : Int = DEFAULT_SAMPLING_RATE,
    val streamOnBoot : Boolean = DEFAULT_STREAM_ON_BOOT,
    val gpsStreaming : Boolean = DEFAULT_GPS_STREAMING,
    val sendIntervalMs : Int = DEFAULT_SEND_INTERVAL_MS,
)