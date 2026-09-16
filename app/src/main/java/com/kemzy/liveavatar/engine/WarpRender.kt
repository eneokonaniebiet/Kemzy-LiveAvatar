package com.kemzy.liveavatar.engine

import ai.onnxruntime.OnnxTensor
import android.graphics.Bitmap
import com.kemzy.liveavatar.camera.DriverMotion
import java.nio.FloatBuffer
import kotlin.math.cos
import kotlin.math.sin

class WarpRender(private val core: LivePortraitPipelineCore) {
    fun render(m: DriverMotion, c: MotionControls): Bitmap {
        val s = core.kpSource ?: error("Source features are not prepared")
        val f = core.feature ?: error("Source features are not prepared")
        var d = transform(s, m, c)

        if (c.eyeBlink > 0f && (m.eyeLeft > 0f || m.eyeRight > 0f)) {
            val delta = core.runRetarget(core.stitchingEye ?: error("Eye retarget model unavailable"), s, m.eyeRetargetRatios())
            d = addDelta(d, delta, c.eyeBlink)
        }
        if (c.mouth > 0f && (m.mouthOpen > 0f || m.smile > 0f)) {
            val delta = core.runRetarget(core.stitchingLip ?: error("Lip retarget model unavailable"), s, m.lipRetargetRatios())
            d = addDelta(d, delta, c.mouth)
        }

        val k = if (c.stitching) stitch(s, d, c.intensity) else d
        val ft = f.toOrtTensor(core.env)
        val ks = tensor(s)
        val kd = tensor(k)
        ft.use { ks.use { kd.use {
            val r = core.warping!!.run(mapOf("feature_3d" to ft, "kp_source" to ks, "kp_driving" to kd))
            r.use { val t = it["out"].get().value as OnnxTensor; t.use { return bitmap(t.floatBuffer, t.info.shape) } }
        } } }
    }

    private fun addDelta(base: FloatArray, delta: FloatArray, strength: Float): FloatArray {
        require(base.size == 63 && delta.size >= 63) { "Unexpected LivePortrait keypoint shape" }
        return base.copyOf().also { out ->
            for (i in 0 until 63) out[i] += delta[i] * strength.coerceIn(0f, 2f)
        }
    }

    private fun stitch(s: FloatArray, d: FloatArray, intensity: Float): FloatArray {
        val delta = core.runStitch(s, d)
        val base = core.defaultStitch ?: FloatArray(delta.size)
        val k = d.copyOf()
        for (i in 0 until 63) k[i] += (delta.getOrElse(i) { 0f } - base.getOrElse(i) { 0f }) * intensity
        return k
    }

    private fun transform(s: FloatArray, m: DriverMotion, c: MotionControls): FloatArray {
        val o = s.copyOf()
        val y = m.yaw * c.yaw * c.intensity
        val p = m.pitch * c.pitch * c.intensity
        val r = m.roll * c.roll * c.intensity
        val cy = cos(y); val sy = sin(y); val cp = cos(p); val sp = sin(p); val cr = cos(r); val sr = sin(r)
        for (i in 0 until 21) {
            val q = i * 3
            val x = s[q]; val yy = s[q + 1]; val z = s[q + 2]
            val x1 = cy * x + sy * z
            val z1 = -sy * x + cy * z
            val y1 = cp * yy - sp * z1
            val z2 = sp * yy + cp * z1
            o[q] = cr * x1 - sr * y1 + c.x
            o[q + 1] = sr * x1 + cr * y1 + c.y
            o[q + 2] = z2 + c.z
        }
        return o
    }

    private fun tensor(v: FloatArray) = OnnxTensor.createTensor(core.env, FloatBuffer.wrap(v), longArrayOf(1, 21, 3))

    private fun bitmap(b: FloatBuffer, s: LongArray): Bitmap {
        require(s.size == 4 && s[0] == 1L && s[1] == 3L)
        val h = s[2].toInt(); val w = s[3].toInt(); val n = w * h
        val r = FloatArray(n); val g = FloatArray(n); val bl = FloatArray(n)
        b.get(r); b.get(g); b.get(bl)
        val px = IntArray(n)
        for (i in 0 until n) {
            val rr = (r[i].coerceIn(0f, 1f) * 255).toInt()
            val gg = (g[i].coerceIn(0f, 1f) * 255).toInt()
            val bb = (bl[i].coerceIn(0f, 1f) * 255).toInt()
            px[i] = (-0x1000000) or (rr shl 16) or (gg shl 8) or bb
        }
        return Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888)
    }
}
