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

package com.github.umer0586.sensagram.data.streamer

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import com.github.umer0586.sensagram.data.model.DeviceSensor
import com.github.umer0586.sensagram.data.model.toDeviceSensors
import com.github.umer0586.sensagram.data.util.JsonUtil
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import kotlin.math.sqrt

data class StreamingInfo(
    val address: String,
    val portNo: Int,
    val samplingRate: Int,
    val sendIntervalMs: Int,
    val sensors: List<DeviceSensor>
)

/**
 * Accumulates raw float arrays for one sensor type across a send interval.
 * Thread-safe: sensor callbacks add readings; the flush thread drains them.
 */
private class SensorBuffer {
    private val readings = mutableListOf<FloatArray>()

    @Synchronized
    fun add(values: FloatArray) {
        readings.add(values.copyOf())
    }

    /**
     * Drains all buffered readings and returns aggregated stats.
     * Returns null when there are no readings yet.
     *
     * Keys in the returned map:
     *   "latest" -> FloatArray  (last received snapshot, used as "values" in JSON)
     *   "min"    -> FloatArray
     *   "max"    -> FloatArray
     *   "avg"    -> FloatArray
     *   "stdDev" -> FloatArray
     */
    @Synchronized
    fun drainAndAggregate(): Map<String, FloatArray>? {
        if (readings.isEmpty()) return null

        val n      = readings[0].size
        val mins   = FloatArray(n) { Float.MAX_VALUE }
        val maxs   = FloatArray(n) { -Float.MAX_VALUE }
        val sums   = DoubleArray(n)
        val sumSqs = DoubleArray(n)
        val count  = readings.size.toDouble()

        for (snapshot in readings) {
            for (i in 0 until n) {
                val v = snapshot[i]
                if (v < mins[i]) mins[i] = v
                if (v > maxs[i]) maxs[i] = v
                sums[i]   += v
                sumSqs[i] += v * v
            }
        }

        val avgs = FloatArray(n) { i -> (sums[i] / count).toFloat() }

        val stdDevs = FloatArray(n) { i ->
            val mean     = sums[i] / count
            val variance = (sumSqs[i] / count) - (mean * mean)
            sqrt(variance.coerceAtLeast(0.0)).toFloat()
        }

        val latest = readings.last()
        readings.clear()

        return mapOf(
            "latest" to latest,
            "min"    to mins,
            "max"    to maxs,
            "avg"    to avgs,
            "stdDev" to stdDevs
        )
    }
}

class SensorStreamer(
    val context: Context,
    /** Accepts either an IPv4 address (e.g. "192.168.1.10") or a FQDN (e.g. "data.example.com"). */
    val address: String,
    val portNo: Int,
    val samplingRate: Int,
    /** How often (milliseconds) a UDP packet is sent with aggregated stats. */
    val sendIntervalMs: Int,
    private var sensors: List<Sensor>
) : SensorEventListener, LocationListener {

    private val sensorManager   = context.getSystemService(Context.SENSOR_SERVICE)  as SensorManager
    private val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager

    private var handlerThread: HandlerThread = HandlerThread("Handler Thread")
    private lateinit var handler: Handler

    // One buffer per sensor string-type; created lazily as events arrive
    private val sensorBuffers = ConcurrentHashMap<String, SensorBuffer>()

    // Scheduler that fires every sendIntervalMs to flush buffers and send UDP packets
    private var scheduler:  ScheduledExecutorService? = null
    private var flushTask:  ScheduledFuture<*>? = null

    var isStreaming = false
        private set

    private var isGPSStreamingEnabled = false

    val streamingInfo: StreamingInfo?
        get() = when (isStreaming) {
            true  -> StreamingInfo(
                address        = address,
                portNo         = portNo,
                samplingRate   = samplingRate,
                sendIntervalMs = sendIntervalMs,
                sensors        = sensors.toDeviceSensors()
            )
            false -> null
        }

    private val datagramSocket: DatagramSocket = DatagramSocket()

    private var onStart: ((StreamingInfo) -> Unit)? = null
    private var onStop:  (() -> Unit)? = null
    private var onError: ((Exception) -> Unit)? = null

    // -------------------------------------------------------------------------
    // Lifecycle
    // -------------------------------------------------------------------------

    fun startStreaming() {

        if (isStreaming) return

        handlerThread.start()
        handler = Handler(handlerThread.looper)

        sensors.forEach {
            sensorManager.registerListener(this, it, samplingRate, handler)
        }

        // Periodic flush: every sendIntervalMs aggregate buffered readings and send
        scheduler = Executors.newSingleThreadScheduledExecutor()
        flushTask = scheduler?.scheduleAtFixedRate(
            ::flushBuffersAndSend,
            sendIntervalMs.toLong(),
            sendIntervalMs.toLong(),
            TimeUnit.MILLISECONDS
        )

        onStart?.invoke(
            StreamingInfo(
                address        = address,
                portNo         = portNo,
                samplingRate   = samplingRate,
                sendIntervalMs = sendIntervalMs,
                sensors        = sensors.toDeviceSensors()
            )
        )
        isStreaming = true
    }

    fun stopStreaming() {
        sensors.forEach { sensorManager.unregisterListener(this, it) }

        flushTask?.cancel(false)
        scheduler?.shutdown()
        scheduler = null

        onStop?.invoke()

        if (!datagramSocket.isClosed)
            datagramSocket.close()

        isStreaming = false
        disableGPSStreaming()
    }

    fun onStreamingStarted(callBack: ((StreamingInfo) -> Unit)?) { onStart = callBack }
    fun onStreamingStopped(callBack: (() -> Unit)?)              { onStop  = callBack }
    fun onError(callBack: ((Exception) -> Unit)?)                { onError = callBack }

    fun changeSensors(newSensors: List<Sensor>) {

        if (!isStreaming) return

        sensors.forEach { sensorManager.unregisterListener(this, it) }
        newSensors.forEach { sensorManager.registerListener(this, it, samplingRate, handler) }

        // Drop stale buffers for sensors that are no longer active
        val activeTypes = newSensors.map { it.stringType }.toSet()
        sensorBuffers.keys.retainAll(activeTypes)

        sensors = newSensors
    }

    // -------------------------------------------------------------------------
    // SensorEventListener — buffer every incoming event; do NOT send immediately
    // -------------------------------------------------------------------------

    override fun onSensorChanged(sensorEvent: SensorEvent) {
        val type = sensorEvent.sensor.stringType
        sensorBuffers.getOrPut(type) { SensorBuffer() }.add(sensorEvent.values)
    }

    override fun onAccuracyChanged(p0: Sensor, p1: Int) { /* no-op */ }

    // -------------------------------------------------------------------------
    // Periodic flush — called every sendIntervalMs on the scheduler thread
    // -------------------------------------------------------------------------

    private fun flushBuffersAndSend() {
        val types = sensorBuffers.keys.toList()   // snapshot to avoid concurrent mutation

        for (type in types) {
            val buffer = sensorBuffers[type] ?: continue
            val stats  = buffer.drainAndAggregate() ?: continue  // no readings this window

            val payload = mutableMapOf<String, Any>()
            payload["type"]      = type
            payload["timestamp"] = System.nanoTime()
            payload["values"]    = stats["latest"]!!.toList()
            payload["min"]       = stats["min"]!!.toList()
            payload["max"]       = stats["max"]!!.toList()
            payload["avg"]       = stats["avg"]!!.toList()
            payload["stdDev"]    = stats["stdDev"]!!.toList()

            sendJson(JsonUtil.toJSON(payload))
        }
    }

    // -------------------------------------------------------------------------
    // GPS / LocationListener — sent immediately (GPS events are already infrequent)
    // -------------------------------------------------------------------------

    fun enableGPSStreaming() {

        if (isGPSStreamingEnabled) return

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
            context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED
        ) return

        locationManager.requestLocationUpdates(
            LocationManager.GPS_PROVIDER,
            0,
            0f,
            this,
            handlerThread.looper
        )

        isGPSStreamingEnabled = true
    }

    fun disableGPSStreaming() {
        if (isGPSStreamingEnabled) {
            locationManager.removeUpdates(this)
            isGPSStreamingEnabled = false
        }
    }

    override fun onLocationChanged(location: Location) {
        sendJson(location.toJson())
    }

    private fun Location.toJson(): String {
        val loc = mutableMapOf<String, Any>()
        loc["type"]      = "android.gps"
        loc["longitude"] = longitude
        loc["latitude"]  = latitude
        loc["altitude"]  = altitude
        loc["bearing"]   = bearing
        loc["accuracy"]  = accuracy
        loc["speed"]     = speed
        loc["time"]      = time

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            loc["speedAccuracyMetersPerSecond"] = speedAccuracyMetersPerSecond
            loc["bearingAccuracyDegrees"]       = bearingAccuracyDegrees
            loc["elapsedRealtimeNanos"]         = elapsedRealtimeNanos
            loc["verticalAccuracyMeters"]       = verticalAccuracyMeters
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            loc["elapsedRealtimeAgeMillis"] = elapsedRealtimeAgeMillis
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            loc["elapsedRealtimeUncertaintyNanos"] = elapsedRealtimeUncertaintyNanos
        }

        return JsonUtil.toJSON(loc)
    }

    // -------------------------------------------------------------------------
    // UDP helper
    // -------------------------------------------------------------------------

    /**
     * Resolves [address] (IPv4 or FQDN) then fires a UDP datagram.
     * Called on background threads only (scheduler or handler thread).
     * InetAddress.getByName() handles both numeric IPs and hostnames transparently.
     */
    private fun sendJson(jsonString: String) {
        try {
            val inetAddress = InetAddress.getByName(address)
            val bytes  = jsonString.toByteArray()
            val packet = DatagramPacket(bytes, bytes.size, inetAddress, portNo)
            datagramSocket.send(packet)
        } catch (e: Exception) {
            onError?.invoke(e)
            stopStreaming()
        }
    }

    // See https://github.com/UmerCodez/SensaGram/issues/9
    @Deprecated("Deprecated in Java")
    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) { /* no-op */ }

    override fun onProviderEnabled(provider: String) {}
    override fun onProviderDisabled(provider: String) {}
}
