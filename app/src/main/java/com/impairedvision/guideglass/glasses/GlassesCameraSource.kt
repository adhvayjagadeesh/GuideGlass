package com.impairedvision.guideglass.glasses

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.meta.wearable.dat.camera.Camera
import com.meta.wearable.dat.camera.addCamera
import com.meta.wearable.dat.camera.removeCamera
import com.meta.wearable.dat.camera.types.StreamConfiguration
import com.meta.wearable.dat.camera.types.StreamState
import com.meta.wearable.dat.camera.types.VideoFrame
import com.meta.wearable.dat.camera.types.VideoQuality
import com.meta.wearable.dat.core.Wearables
import com.meta.wearable.dat.core.selectors.AutoDeviceSelector
import com.meta.wearable.dat.core.session.DeviceSession
import com.meta.wearable.dat.core.session.DeviceSessionState
import com.meta.wearable.dat.core.types.DonState
import com.meta.wearable.dat.core.types.LinkState
import com.meta.wearable.dat.core.types.Permission
import com.meta.wearable.dat.core.types.PermissionStatus
import com.meta.wearable.dat.core.types.RegistrationState
import com.meta.wearable.dat.core.types.ThermalLevel
import com.meta.wearable.dat.core.types.WearablesError
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** What the glasses camera is doing, as far as the reflex needs to know. */
sealed interface GlassesEvent {
    data object Connecting : GlassesEvent

    /** Frames are flowing. The reflex gates must be the glasses gates from now on. */
    data object Streaming : GlassesEvent

    /**
     * Never started. The caller should use the phone camera. When [needsRegistration] is true the
     * user has not yet approved this app in the Meta AI app, so the caller should start that flow.
     */
    data class Unavailable(val reason: String, val needsRegistration: Boolean = false) : GlassesEvent

    /**
     * Was streaming and stopped, for example the glasses were taken off. The reflex has lost its
     * eyes, so the caller must switch to the phone camera immediately and tell the user.
     */
    data class Lost(val reason: String) : GlassesEvent
}

/**
 * Streams the Ray-Ban Meta camera into the existing reflex through the Meta Wearables Device Access
 * Toolkit, in place of the phone's CameraX feed.
 *
 * Only the eyes move. Frames still go to the same `processFrame(bitmap, timestamp)` on the phone, so
 * the reflex keeps running on-device and never waits on the network.
 *
 * Safety contract: any loss of the glasses feed (taken off, disconnected, overheating, stream ended)
 * is reported as [GlassesEvent.Lost] exactly once, so the caller can fall back to the phone camera
 * rather than walk the user forward with no obstacle detection.
 */
class GlassesCameraSource(
        context: Context,
        private val scope: CoroutineScope,
        /**
         * Layout of the toolkit's decoded frames. Pinned by GlassesCameraSourceTest on the emulator.
         * Phone hardware decoders can differ, so re-run that test on a real phone before field use.
         */
        private val layout: Yuv420Layout = Yuv420Layout.I420,
) {
    private val appContext = context.applicationContext

    private var session: DeviceSession? = null
    private var camera: Camera? = null
    private val jobs = mutableListOf<Job>()

    @Volatile private var lostReported = false
    @Volatile var framesReceived = 0L
        private set
    @Volatile var framesDelivered = 0L
        private set

    private var yuvBytes = ByteArray(0)
    private var argb = IntArray(0)

    /**
     * Connects to the glasses and starts streaming. Never throws: every failure becomes an event.
     *
     * @param requestCameraPermission asks the Meta AI app for glasses camera access. It must be
     *   registered by the caller with `Wearables.RequestPermissionContract` before the activity
     *   starts, so it is passed in rather than created here.
     * @param onFrame called off the main thread with a fresh bitmap per frame, never reused, because
     *   the reflex hands frames to ML Kit and Gemini asynchronously.
     */
    fun start(
            requestCameraPermission: suspend () -> Boolean,
            onFrame: (Bitmap, Long) -> Unit,
            onEvent: (GlassesEvent) -> Unit,
    ) {
        lostReported = false
        jobs += scope.launch {
            onEvent(GlassesEvent.Connecting)
            val failure = connectAndStream(requestCameraPermission, onFrame, onEvent)
            if (failure != null) {
                Log.w(TAG, "Glasses unavailable: ${failure.reason}")
                stop()
                onEvent(failure)
            }
        }
    }

    /** Returns why the stream could not start, or null once frames are flowing. */
    private suspend fun connectAndStream(
            requestCameraPermission: suspend () -> Boolean,
            onFrame: (Bitmap, Long) -> Unit,
            onEvent: (GlassesEvent) -> Unit,
    ): GlassesEvent.Unavailable? {
        fun fail(reason: String) = GlassesEvent.Unavailable(reason)
        ensureInitialized(appContext)?.let { return fail(it) }
        // Registration state is loaded asynchronously after initialize(), so reading it at once
        // can report a registered user as unregistered. Give it a moment to settle.
        withTimeoutOrNull(REGISTRATION_SETTLE_MS) {
            Wearables.registrationState.first { it == RegistrationState.REGISTERED }
        }
                ?: return GlassesEvent.Unavailable(
                        "this app is not yet approved in the Meta AI app", needsRegistration = true)

        val status = Wearables.checkPermissionStatus(Permission.CAMERA).getOrNull()
        if (status != PermissionStatus.Granted && !requestCameraPermission()) {
            return fail("glasses camera permission was not granted")
        }

        val newSession =
                Wearables.createSession(AutoDeviceSelector()).getOrNull()
                        ?: return fail("no glasses are available")
        session = newSession
        newSession.start()
        withTimeoutOrNull(SESSION_TIMEOUT_MS) {
            newSession.state.first { it == DeviceSessionState.STARTED }
        } ?: return fail("the glasses did not connect within ${SESSION_TIMEOUT_MS / 1000} seconds")

        val config =
                StreamConfiguration(
                        videoQuality = VideoQuality.MEDIUM, // 504 x 896, enough for the detector
                        frameRate = FRAME_RATE,
                        compressVideo = false, // the toolkit decodes; we receive raw 4:2:0
                )
        val newCamera =
                newSession.addCamera(config).getOrNull() ?: return fail("the glasses camera could not attach")
        camera = newCamera
        val stream = newCamera.stream

        // Subscribe before start(). The state flow replays STOPPED on subscription, so a loss only
        // counts after the stream has actually been active.
        jobs += scope.launch {
            var active = false
            stream.state.collect { state ->
                when (state) {
                    StreamState.STARTED, StreamState.STREAMING -> {
                        if (!active) onEvent(GlassesEvent.Streaming)
                        active = true
                    }
                    StreamState.STOPPED, StreamState.CLOSED ->
                            if (active) reportLost("the glasses camera stream ended", onEvent)
                    else -> Unit
                }
            }
        }
        jobs += scope.launch { watchDevice(newSession, onEvent) }
        jobs += scope.launch(frameDispatcher) {
            // conflate: if the reflex is slower than the stream, skip to the newest frame rather
            // than warn the user about where an obstacle used to be.
            stream.videoStream.conflate().collect { frame -> deliver(frame, onFrame) }
        }

        stream.start().errorOrNull()?.let { return fail("the stream failed to start: ${it.description}") }
        return null
    }

    /** Taken off, disconnected, or overheating all mean the reflex has lost its camera. */
    private suspend fun watchDevice(session: DeviceSession, onEvent: (GlassesEvent) -> Unit) {
        session.deviceInfo.collect { device ->
            val reason =
                    when {
                        device.donState == DonState.DOFFED -> "the glasses were taken off"
                        device.linkState == LinkState.DISCONNECTED -> "the glasses disconnected"
                        device.thermalLevel in OVERHEATING -> "the glasses are overheating"
                        else -> null
                    }
            if (reason != null && framesReceived > 0) reportLost(reason, onEvent)
        }
    }

    private fun reportLost(reason: String, onEvent: (GlassesEvent) -> Unit) {
        if (lostReported) return
        lostReported = true
        Log.w(TAG, "Glasses camera lost: $reason")
        onEvent(GlassesEvent.Lost(reason))
    }

    private fun deliver(frame: VideoFrame, onFrame: (Bitmap, Long) -> Unit) {
        framesReceived++
        if (framesReceived == 1L) {
            Log.i(TAG, "First glasses frame: ${frame.width}x${frame.height}, " +
                    "${frame.buffer.remaining()} bytes, compressed=${frame.isCompressed}, " +
                    "packed 4:2:0 would be ${Yuv420.expectedSize(frame.width, frame.height)}")
        }
        if (frame.isCompressed || frame.isCodecConfig) return

        val size = frame.buffer.remaining()
        if (yuvBytes.size != size) yuvBytes = ByteArray(size)
        frame.buffer.duplicate().get(yuvBytes)
        val pixels = frame.width * frame.height
        if (argb.size != pixels) argb = IntArray(pixels)

        try {
            Yuv420.toArgb(yuvBytes, frame.width, frame.height, layout, argb)
        } catch (e: IllegalArgumentException) {
            // Padded rows or an unexpected format. Never guess: drop it, and log once.
            if (framesReceived <= 1L) Log.e(TAG, "Unusable glasses frame format", e)
            return
        }
        val bitmap = Bitmap.createBitmap(argb, frame.width, frame.height, Bitmap.Config.ARGB_8888)
        framesDelivered++
        onFrame(bitmap, System.currentTimeMillis())
    }

    fun stop() {
        jobs.forEach { it.cancel() }
        jobs.clear()
        camera?.stream?.stop()
        camera = null
        session?.let {
            it.removeCamera()
            it.stop()
        }
        session = null
    }

    companion object {
        private const val TAG = "GlassesCameraSource"

        @Volatile private var initialized = false

        /**
         * The toolkit must be initialized exactly once per process. Returns a reason on failure.
         * Synchronized because the activity and the stream setup can both reach it.
         */
        @Synchronized
        fun ensureInitialized(context: Context): String? {
            if (initialized) return null
            val error = Wearables.initialize(context.applicationContext).errorOrNull()
            // Already initialized elsewhere (the mock device kit does this, and so could any other
            // component) is success: the toolkit is ready, which is all we need.
            if (error != null && error != WearablesError.ALREADY_INITIALIZED) {
                return "toolkit failed to initialize: ${error.description}"
            }
            initialized = true
            return null
        }

        private const val SESSION_TIMEOUT_MS = 10_000L
        private const val REGISTRATION_SETTLE_MS = 3_000L

        /** 15 fps is the toolkit's floor under Bluetooth pressure, and above the reflex's rate. */
        private const val FRAME_RATE = 15

        private val OVERHEATING =
                setOf(ThermalLevel.CRITICAL, ThermalLevel.EMERGENCY, ThermalLevel.SHUTDOWN)

        /** One thread, so frames convert in order and the reused buffers are never shared. */
        @OptIn(ExperimentalCoroutinesApi::class)
        private val frameDispatcher = Dispatchers.Default.limitedParallelism(1)
    }
}
