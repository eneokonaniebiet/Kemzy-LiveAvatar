package com.kemzy.liveavatar.engine

import android.content.Context
import android.graphics.Bitmap
import com.kemzy.liveavatar.camera.DriverMotion
import com.kemzy.liveavatar.inference.CustomOpsLoader
import com.kemzy.liveavatar.models.ModelDiscovery
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicReference

class LiveAvatarEngine(context: Context) : AutoCloseable {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val renderMutex = Mutex()
    private val stateRef = AtomicReference<EngineState>(EngineState.Ready)
    private val latestRef = AtomicReference<AiFrame?>(null)
    private var core: LivePortraitPipelineCore? = null
    private var renderer: WarpRender? = null
    private var sourceBitmap: Bitmap? = null

    val state: EngineState get() = stateRef.get()
    fun latestFrame(): AiFrame? = latestRef.get()

    suspend fun prepare(source: Bitmap): Result<Unit> = renderMutex.withLock {
        stateRef.set(EngineState.Preparing)
        return@withLock runCatching {
            val manifest = ModelDiscovery(appContext).discover()
            require(manifest.complete) { manifest.diagnostics.joinToString("; ") }
            sourceBitmap?.recycle()
            sourceBitmap = source.copy(Bitmap.Config.ARGB_8888, false)
            core?.close()
            val nextCore = LivePortraitPipelineCore(CustomOpsLoader(appContext))
            nextCore.prepare(manifest, sourceBitmap!!)
            core = nextCore
            renderer = WarpRender(nextCore)
            latestRef.set(AiFrame(sourceBitmap!!.copy(Bitmap.Config.ARGB_8888, false), System.nanoTime()))
            stateRef.set(EngineState.Running)
        }.onFailure { stateRef.set(EngineState.Error(it.message ?: "Unable to prepare LiveAvatar engine")) }
    }

    suspend fun submit(driver: DriverMotion, controls: MotionControls = MotionControls()): Boolean {
        if (stateRef.get() != EngineState.Running) return false
        return renderMutex.withLock {
            runCatching {
                val bitmap = renderer?.render(driver, controls) ?: return@runCatching false
                val previous = latestRef.getAndSet(AiFrame(bitmap, System.nanoTime()))
                previous?.bitmap?.recycle()
                true
            }.getOrElse {
                stateRef.set(EngineState.Degraded("AI frame failed: ${it.message ?: "unknown inference error"}"))
                false
            }
        }
    }

    fun resumeAfterFrameError() {
        if (core != null && renderer != null) stateRef.set(EngineState.Running)
    }

    fun stop() {
        stateRef.set(EngineState.Stopped)
    }

    override fun close() {
        stop()
        latestRef.getAndSet(null)?.bitmap?.recycle()
        sourceBitmap?.recycle()
        sourceBitmap = null
        core?.close()
        core = null
        renderer = null
        scope.coroutineContext.cancel()
    }
}
