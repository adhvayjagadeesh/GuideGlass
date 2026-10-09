package com.impairedvision.guideglass.glasses

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.meta.wearable.dat.mockdevice.MockDeviceKit
import com.meta.wearable.dat.mockdevice.api.GlassesModel
import com.meta.wearable.dat.mockdevice.api.MockDeviceKitConfig
import com.meta.wearable.dat.mockdevice.api.MockDeviceKitInterface
import com.meta.wearable.dat.mockdevice.api.MockGlasses
import java.io.File
import java.io.FileOutputStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Runs [GlassesCameraSource] against Meta's simulated Ray-Ban Meta glasses.
 *
 * The color-bar test is the one that pins [Yuv420Layout]. A wrong layout does not crash, it
 * scrambles the chroma, so red comes out green or magenta. That makes this test unable to pass
 * by accident. It runs on whatever device it is pointed at, so run it on a real phone too: phone
 * hardware decoders can lay frames out differently from the emulator's software decoder.
 */
@RunWith(AndroidJUnit4::class)
class GlassesCameraSourceTest {

    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext

    private lateinit var mockDeviceKit: MockDeviceKitInterface
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var source: GlassesCameraSource? = null

    @Before
    fun setUp() {
        grant("android.permission.BLUETOOTH_CONNECT")
        grant("android.permission.CAMERA")
        mockDeviceKit = MockDeviceKit.getInstance(context)
    }

    @After
    fun tearDown() {
        source?.stop()
        scope.cancel()
        mockDeviceKit.disable()
    }

    @Test
    fun colorBarsArriveWithTheRightColors() = runBlocking<Unit> {
        mockDeviceKit.enable(MockDeviceKitConfig(true, true)) // registered, permission granted
        pairWornGlasses().services.camera.setCameraFeed(assetFileUri("color_bars.mp4"))

        val frame = startAndAwaitFrame(Yuv420Layout.I420)
        val y = frame.height / 2
        val sampled = listOf("red" to 1, "green" to 3, "blue" to 5, "white" to 7).map { (name, eighth) ->
            name to frame.getPixel(frame.width * eighth / 8, y)
        }
        Log.i(TAG, "frame ${frame.width}x${frame.height}: " +
                sampled.joinToString { (n, c) -> "$n=(${Color.red(c)},${Color.green(c)},${Color.blue(c)})" })

        val (red, green, blue, white) = sampled.map { it.second }
        assertTrue("red bar is ${describe(red)}", dominant(red, Color::red))
        assertTrue("green bar is ${describe(green)}", dominant(green, Color::green))
        assertTrue("blue bar is ${describe(blue)}", dominant(blue, Color::blue))
        assertTrue("white bar is ${describe(white)}",
                minOf(Color.red(white), Color.green(white), Color.blue(white)) > 180)
        assertTrue(source!!.framesDelivered > 0)
    }

    @Test
    fun takingTheGlassesOffIsReportedAsLost() = runBlocking<Unit> {
        mockDeviceKit.enable(MockDeviceKitConfig(true, true))
        val glasses = pairWornGlasses()
        glasses.services.camera.setCameraFeed(assetFileUri("color_bars.mp4"))

        val events = Channel<GlassesEvent>(Channel.UNLIMITED)
        startAndAwaitFrame(Yuv420Layout.I420, events)
        glasses.doff()

        // The app falls back to the phone camera on Lost. If this never arrives, a user who takes
        // the glasses off mid-walk keeps walking with no obstacle detection.
        val lost = withTimeout(EVENT_TIMEOUT_MS) {
            var e = events.receive()
            while (e !is GlassesEvent.Lost) e = events.receive()
            e
        }
        Log.i(TAG, "lost: ${lost.reason}")
    }

    @Test
    fun anUnregisteredAppFailsCleanlyAndAsksForRegistration() = runBlocking<Unit> {
        mockDeviceKit.enable(MockDeviceKitConfig(false, true)) // not registered
        pairWornGlasses()

        val events = Channel<GlassesEvent>(Channel.UNLIMITED)
        source = GlassesCameraSource(context, scope).also {
            it.start({ true }, { _, _ -> }, { e -> events.trySend(e) })
        }
        val result = withTimeout(EVENT_TIMEOUT_MS) {
            var e = events.receive()
            while (e is GlassesEvent.Connecting) e = events.receive()
            e
        }
        assertTrue("expected Unavailable, got $result", result is GlassesEvent.Unavailable)
        assertTrue("should ask for registration: $result",
                (result as GlassesEvent.Unavailable).needsRegistration)
    }

    // ---------------------------------------------------------------- helpers

    private fun pairWornGlasses(): MockGlasses =
            mockDeviceKit.pairGlasses(GlassesModel.RAYBAN_META).getOrThrow().also {
                it.powerOn()
                it.don()
            }

    private suspend fun startAndAwaitFrame(
            layout: Yuv420Layout,
            events: Channel<GlassesEvent> = Channel(Channel.UNLIMITED),
    ): Bitmap {
        val frames = Channel<Bitmap>(Channel.CONFLATED)
        source = GlassesCameraSource(context, scope, layout).also {
            it.start({ true }, { bitmap, _ -> frames.trySend(bitmap) }, { e ->
                // Fail fast with the real reason instead of timing out with no explanation.
                if (e is GlassesEvent.Unavailable) frames.close(AssertionError("unavailable: ${e.reason}"))
                events.trySend(e)
            })
        }
        return withTimeout(FRAME_TIMEOUT_MS) {
            var bitmap = frames.receive()
            // The first frames can be black while the decoder warms up.
            while (isMostlyBlack(bitmap)) bitmap = frames.receive()
            bitmap
        }.also { if (it.width == 0) fail("empty frame") }
    }

    private fun isMostlyBlack(b: Bitmap): Boolean {
        val c = b.getPixel(b.width * 7 / 8, b.height / 2) // the white bar
        return Color.red(c) + Color.green(c) + Color.blue(c) < 120
    }

    private fun dominant(c: Int, channel: (Int) -> Int): Boolean {
        val mine = channel(c)
        val others = listOf(Color.red(c), Color.green(c), Color.blue(c)).sorted()
        return mine > 150 && mine == others.last() && others[1] < 100
    }

    private fun describe(c: Int) = "(${Color.red(c)}, ${Color.green(c)}, ${Color.blue(c)})"

    private fun assetFileUri(name: String): Uri {
        val out = File(context.cacheDir, name)
        InstrumentationRegistry.getInstrumentation().context.assets.open(name).use { input ->
            FileOutputStream(out).use { input.copyTo(it) }
        }
        return Uri.fromFile(out)
    }

    private fun grant(permission: String) {
        InstrumentationRegistry.getInstrumentation().uiAutomation
                .executeShellCommand("pm grant ${context.packageName} $permission")
                .close()
    }

    companion object {
        private const val TAG = "GlassesCameraSourceTest"
        private const val FRAME_TIMEOUT_MS = 30_000L
        private const val EVENT_TIMEOUT_MS = 20_000L
    }
}
