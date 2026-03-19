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
import android.util.Log
import com.github.umer0586.sensagram.data.model.DeviceSensor
import com.github.umer0586.sensagram.data.model.toDeviceSensors
import com.github.umer0586.sensagram.data.util.JsonUtil
import java.io.BufferedWriter
import java.io.OutputStreamWriter
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import kotlin.math.sqrt

data class StreamingInfo(
    val address       : String,
    val portNo        : Int,
    val samplingRate  : Int,
    val sendIntervalMs: Int,
    val useTcp        : Boolean,
    val deviceId      : String,
    val sensors       : List<DeviceSensor>
)

// ---------------------------------------------------------------------------
// SensorBuffer — accumulates raw readings for one sensor type per interval
// ---------------------------------------------------------------------------

private class SensorBuffer {
    private val readings = mutableListOf<FloatArray>()

    @Synchronized fun add(values: FloatArray) { readings.add(values.copyOf()) }

    /**
     * Drains all buffered readings and returns aggregated stats, or null if empty.
     * Keys: "latest", "min", "max", "avg", "stdDev"  — each a FloatArray.
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

        val avgs    = FloatArray(n) { i -> (sums[i] / count).toFloat() }
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

// ---------------------------------------------------------------------------
// SensorStreamer
// ---------------------------------------------------------------------------

class SensorStreamer(
    val context       : Context,
    /** Accepts either an IPv4 address or a FQDN. */
    val address       : String,
    val portNo        : Int,
    val samplingRate  : Int,
    /** How often (ms) a packet is sent with aggregated stats. */
    val sendIntervalMs: Int,
    /** true = TCP, false = UDP */
    val useTcp        : Boolean,
    /**
     * Stable device identifier stamped into every outgoing JSON packet so the
     * server can route data by device even when the source IP changes (LTE/GSM
     * reconnects, NAT rebinds, etc.).  Generated once by [SettingsRepositoryImp]
     * and reused for the lifetime of the installation.
     */
    val deviceId      : String,
    private var sensors: List<Sensor>
) : SensorEventListener, LocationListener {

    private val sensorManager   = context.getSystemService(Context.SENSOR_SERVICE)  as SensorManager
    private val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager

    private var handlerThread = HandlerThread("SensorStreamer-Handler")
    private lateinit var handler: Handler

    // Per-sensor reading buffers
    private val sensorBuffers = ConcurrentHashMap<String, SensorBuffer>()

    // Latest GPS fix — updated on every onLocationChanged, flushed on the send timer
    @Volatile private var latestLocation: Location? = null

    // Periodic flush scheduler
    private var scheduler: ScheduledExecutorService? = null
    private var flushTask:  ScheduledFuture<*>?      = null

    // --- UDP transport ---
    @Volatile private var datagramSocket: DatagramSocket? = null

    // --- TCP transport ---
    @Volatile private var tcpSocket: Socket?        = null
    @Volatile private var tcpWriter: BufferedWriter? = null

    // Prevents multiple simultaneous reconnect attempts
    @Volatile private var isReconnecting = false

    companion object {
        private const val TAG                    = "SensorStreamer"
        private const val TCP_CONNECT_TIMEOUT_MS = 5_000
        private const val TCP_CONNECT_RETRIES    = 3       // used for initial connect
        private const val TCP_RETRY_DELAY_MS     = 2_000L  // delay between retries
        private const val TCP_RECONNECT_DELAY_MS = 3_000L  // delay before each reconnect attempt
    }

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
                useTcp         = useTcp,
                deviceId       = deviceId,
                sensors        = sensors.toDeviceSensors()
            )
            false -> null
        }

    // Callbacks
    private var onStart:            ((StreamingInfo) -> Unit)? = null
    private var onStop:             (() -> Unit)?              = null
    private var onError:            ((Exception) -> Unit)?     = null
    private var onConnectionFailed: ((String) -> Unit)?        = null
    private var onReconnecting:     (() -> Unit)?              = null
    private var onReconnected:      (() -> Unit)?              = null

    // -----------------------------------------------------------------------
    // Lifecycle
    // -----------------------------------------------------------------------

    fun startStreaming() {

        if (isStreaming) return

        Log.d(TAG, "startStreaming() transport=${if (useTcp) "TCP" else "UDP"} target=$address:$portNo")

        if (useTcp) {
            if (!connectTcp()) {
                val msg = "Could not connect to TCP server at $address:$portNo " +
                          "after $TCP_CONNECT_RETRIES attempts. " +
                          "Make sure the server is running before starting the stream."
                Log.e(TAG, msg)
                onConnectionFailed?.invoke(msg)
                return
            }
        } else {
            datagramSocket = DatagramSocket()
        }

        handlerThread.start()
        handler = Handler(handlerThread.looper)

        Log.d(TAG, "registering ${sensors.size} sensor(s)")
        sensors.forEach { sensorManager.registerListener(this, it, samplingRate, handler) }

        scheduler = Executors.newSingleThreadScheduledExecutor()
        flushTask = scheduler?.scheduleAtFixedRate(
            ::flushBuffersAndSend,
            sendIntervalMs.toLong(),
            sendIntervalMs.toLong(),
            TimeUnit.MILLISECONDS
        )

        isStreaming = true
        Log.d(TAG, "streaming started")

        onStart?.invoke(
            StreamingInfo(
                address        = address,
                portNo         = portNo,
                samplingRate   = samplingRate,
                sendIntervalMs = sendIntervalMs,
                useTcp         = useTcp,
                deviceId       = deviceId,
                sensors        = sensors.toDeviceSensors()
            )
        )
    }

    fun stopStreaming() {
        Log.d(TAG, "stopStreaming()")
        isStreaming = false   // set first so reconnect loop exits cleanly

        sensors.forEach { sensorManager.unregisterListener(this, it) }

        flushTask?.cancel(false)
        scheduler?.shutdown()
        scheduler = null

        handlerThread.quitSafely()

        onStop?.invoke()

        closeSockets()
        disableGPSStreaming()
    }

    fun onStreamingStarted(cb: ((StreamingInfo) -> Unit)?) { onStart            = cb }
    fun onStreamingStopped(cb: (() -> Unit)?)              { onStop             = cb }
    fun onError(cb: ((Exception) -> Unit)?)                { onError            = cb }
    fun onConnectionFailed(cb: ((String) -> Unit)?)        { onConnectionFailed = cb }
    /** Fired when a TCP connection is lost and a reconnect attempt begins. */
    fun onReconnecting(cb: (() -> Unit)?)                  { onReconnecting     = cb }
    /** Fired when a TCP reconnect attempt succeeds and streaming resumes. */
    fun onReconnected(cb: (() -> Unit)?)                   { onReconnected      = cb }

    fun changeSensors(newSensors: List<Sensor>) {
        if (!isStreaming) return
        sensors.forEach { sensorManager.unregisterListener(this, it) }
        newSensors.forEach { sensorManager.registerListener(this, it, samplingRate, handler) }
        val activeTypes = newSensors.map { it.stringType }.toSet()
        sensorBuffers.keys.retainAll(activeTypes)
        sensors = newSensors
    }

    // -----------------------------------------------------------------------
    // TCP helpers
    // -----------------------------------------------------------------------

    /**
     * Attempts to open a TCP connection, retrying up to [TCP_CONNECT_RETRIES] times.
     * Always closes the socket on failure to avoid dangling half-open connections.
     * Returns true on success.
     */
    private fun connectTcp(): Boolean {
        for (attempt in 1..TCP_CONNECT_RETRIES) {
            Log.d(TAG, "TCP connect attempt $attempt/$TCP_CONNECT_RETRIES → $address:$portNo")
            val sock = Socket()
            try {
                sock.connect(InetSocketAddress(address, portNo), TCP_CONNECT_TIMEOUT_MS)
                tcpSocket = sock
                tcpWriter = BufferedWriter(OutputStreamWriter(sock.getOutputStream(), Charsets.UTF_8))
                Log.d(TAG, "TCP connected")
                return true
            } catch (e: Exception) {
                Log.w(TAG, "TCP attempt $attempt failed: ${e.message}")
                try { sock.close() } catch (_: Exception) {}
                tcpSocket = null
                tcpWriter = null
                if (attempt < TCP_CONNECT_RETRIES) Thread.sleep(TCP_RETRY_DELAY_MS)
            }
        }
        return false
    }

    /**
     * Called when a send fails while streaming is still active (network disruption).
     * Closes the broken socket and spins up a background thread that keeps retrying
     * the connection indefinitely until it succeeds or [stopStreaming] is called.
     * Sensor data continues to be buffered in the interim; packets are dropped until
     * the connection is restored.
     */
    private fun scheduleReconnect(cause: Exception) {
        if (!isStreaming) return
        if (isReconnecting) return
        isReconnecting = true

        Log.w(TAG, "TCP connection lost (${cause.message}) — scheduling reconnect")

        // Notify the UI that we're reconnecting (not stopped — streaming continues)
        onReconnecting?.invoke()

        // Close the broken socket so subsequent sendJson calls fast-fail cleanly
        try { tcpWriter?.close() } catch (_: Exception) {}
        try { tcpSocket?.close() } catch (_: Exception) {}
        tcpWriter = null
        tcpSocket = null

        Thread {
            while (isStreaming) {
                Log.d(TAG, "Reconnect attempt in ${TCP_RECONNECT_DELAY_MS}ms…")
                Thread.sleep(TCP_RECONNECT_DELAY_MS)
                if (!isStreaming) break
                if (connectTcp()) {
                    Log.d(TAG, "Reconnected successfully")
                    isReconnecting = false
                    // Notify the UI that the connection is restored
                    onReconnected?.invoke()
                    return@Thread
                }
            }
            isReconnecting = false
            Log.d(TAG, "Reconnect loop exited (streaming stopped)")
        }.also { it.isDaemon = true; it.name = "SensorStreamer-Reconnect" }.start()
    }

    private fun closeSockets() {
        try { tcpWriter?.close()      } catch (_: Exception) {}
        try { tcpSocket?.close()      } catch (_: Exception) {}
        try { datagramSocket?.close() } catch (_: Exception) {}
        tcpWriter      = null
        tcpSocket      = null
        datagramSocket = null
    }

    // -----------------------------------------------------------------------
    // SensorEventListener — buffer every incoming event
    // -----------------------------------------------------------------------

    override fun onSensorChanged(sensorEvent: SensorEvent) {
        val type = sensorEvent.sensor.stringType
        sensorBuffers.getOrPut(type) { SensorBuffer() }.add(sensorEvent.values)
    }

    override fun onAccuracyChanged(p0: Sensor, p1: Int) { /* no-op */ }

    // -----------------------------------------------------------------------
    // Periodic flush — called every sendIntervalMs
    // Sends all buffered sensor stats AND the latest GPS fix (if any).
    // GPS is included here rather than immediately in onLocationChanged so that
    // GPS data respects the same send interval as all other sensors.
    // -----------------------------------------------------------------------

    private fun flushBuffersAndSend() {
        // 1. Flush sensor buffers
        for (type in sensorBuffers.keys.toList()) {
            val stats = sensorBuffers[type]?.drainAndAggregate() ?: continue

            val payload = mutableMapOf<String, Any>()
            payload["device_id"] = deviceId
            payload["type"]      = type
            payload["timestamp"] = System.nanoTime()
            payload["values"]    = stats["latest"]!!.toList()
            payload["min"]       = stats["min"]!!.toList()
            payload["max"]       = stats["max"]!!.toList()
            payload["avg"]       = stats["avg"]!!.toList()
            payload["stdDev"]    = stats["stdDev"]!!.toList()

            sendJson(JsonUtil.toJSON(payload))
        }

        // 2. Flush latest GPS fix (if one has arrived since the last flush)
        latestLocation?.let { loc ->
            latestLocation = null          // clear so we don't resend a stale fix
            sendJson(loc.toJson())
        }
    }

    // -----------------------------------------------------------------------
    // GPS / LocationListener
    // Store the latest fix; the flush timer sends it at the send interval.
    // -----------------------------------------------------------------------

    fun enableGPSStreaming() {
        if (isGPSStreamingEnabled) return
        if (!::handler.isInitialized) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
            context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED
        ) return

        // requestLocationUpdates must run on a Looper thread.
        handler.post {
            // Register GPS_PROVIDER for accurate fixes.
            locationManager.requestLocationUpdates(
                LocationManager.GPS_PROVIDER, 0, 0f, this, handlerThread.looper
            )

            // Also register NETWORK_PROVIDER as a warm-up source.
            // On a cold start the GPS chipset can take minutes to acquire satellites.
            // The network provider (cell towers + Wi-Fi) delivers a coarse fix in
            // 2-3 seconds, which (a) immediately populates latestLocation so GPS
            // columns are never blank, and (b) seeds AGPS so the GPS chipset gets
            // its first precise fix much faster.
            // Both providers share the same onLocationChanged callback; GPS fixes
            // will naturally supersede network fixes once they arrive because they
            // are more accurate and arrive later.
            if (locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                locationManager.requestLocationUpdates(
                    LocationManager.NETWORK_PROVIDER, 0, 0f, this, handlerThread.looper
                )
                Log.d(TAG, "GPS + network location streaming enabled (fast cold-start mode)")
            } else {
                Log.d(TAG, "GPS streaming enabled (network provider unavailable)")
            }

            isGPSStreamingEnabled = true
        }
    }

    fun disableGPSStreaming() {
        if (!isGPSStreamingEnabled) return
        if (!::handler.isInitialized) {
            isGPSStreamingEnabled = false
            return
        }
        handler.post {
            // removeUpdates(this) unregisters from ALL providers this listener
            // was registered with, so a single call covers both GPS and network.
            locationManager.removeUpdates(this)
            isGPSStreamingEnabled = false
            Log.d(TAG, "GPS streaming disabled")
        }
    }

    override fun onLocationChanged(location: Location) {
        // Just store the fix; flushBuffersAndSend() will send it at the next interval.
        latestLocation = location
    }

    private fun Location.toJson(): String {
        val loc = mutableMapOf<String, Any>()
        loc["device_id"] = deviceId
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

    // -----------------------------------------------------------------------
    // Transport — single dispatch point for UDP and TCP
    // -----------------------------------------------------------------------

    /**
     * Sends [jsonString] over the active transport.
     * TCP messages are newline-terminated for receiver framing.
     * On TCP send failure, triggers a background reconnect instead of stopping.
     */
    private fun sendJson(jsonString: String) {
        try {
            if (useTcp) {
                val writer = tcpWriter ?: run {
                    // Either not yet connected, or mid-reconnect — drop silently
                    Log.v(TAG, "sendJson: no TCP writer, dropping packet during reconnect")
                    return
                }
                writer.write(jsonString)
                writer.newLine()
                writer.flush()
            } else {
                val socket = datagramSocket ?: run {
                    Log.w(TAG, "sendJson: datagramSocket is null, dropping packet")
                    return
                }
                val bytes    = jsonString.toByteArray(Charsets.UTF_8)
                val inetAddr = InetAddress.getByName(address)
                socket.send(DatagramPacket(bytes, bytes.size, inetAddr, portNo))
            }
        } catch (e: Exception) {
            if (useTcp) {
                // Network disruption — reconnect in background, keep streaming
                scheduleReconnect(e)
            } else {
                Log.e(TAG, "UDP send error: ${e.message}", e)
                onError?.invoke(e)
                stopStreaming()
            }
        }
    }

    // See https://github.com/UmerCodez/SensaGram/issues/9
    @Deprecated("Deprecated in Java")
    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
    override fun onProviderEnabled(provider: String) {}
    override fun onProviderDisabled(provider: String) {}
}
