package com.kemzy.liveavatar.camera

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

data class DriverFrame(val image: Any, val motion: DriverMotion, val timestampNanos: Long)

class FrameGate {
    private val busy = AtomicBoolean(false)
    private val newest = AtomicReference<DriverFrame?>(null)

    fun offer(frame: DriverFrame): Boolean {
        newest.getAndSet(frame)?.let { closeIfPossible(it.image) }
        return true
    }

    fun tryAcquire(): DriverFrame? {
        if (!busy.compareAndSet(false, true)) return null
        return newest.getAndSet(null)
    }

    fun release() { busy.set(false) }

    private fun closeIfPossible(value: Any) = runCatching { value.javaClass.getMethod("close").invoke(value) }
}
