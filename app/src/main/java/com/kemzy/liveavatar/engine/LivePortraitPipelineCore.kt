package com.kemzy.liveavatar.engine

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.graphics.Bitmap
import com.kemzy.liveavatar.inference.CustomOpsLoader
import com.kemzy.liveavatar.inference.Feature3dTensor
import com.kemzy.liveavatar.inference.OrtSessionFactory
import com.kemzy.liveavatar.inference.TensorContract
import com.kemzy.liveavatar.models.ModelManifest
import com.kemzy.liveavatar.models.ModelNames
import java.nio.FloatBuffer

class LivePortraitPipelineCore(private val customOps: CustomOpsLoader) : AutoCloseable {
    val env: OrtEnvironment = OrtEnvironment.getEnvironment()
    private val factory = OrtSessionFactory(env)
    var feature: Feature3dTensor? = null; private set
    var kpSource: FloatArray? = null; private set
    var defaultStitch: FloatArray? = null; private set
    var warping: OrtSession? = null; private set
    var stitching: OrtSession? = null; private set
    var stitchingEye: OrtSession? = null; private set
    var stitchingLip: OrtSession? = null; private set

    fun prepare(manifest: ModelManifest, source: Bitmap) {
        closeSessions()
        require(manifest.complete) { "LivePortrait models incomplete: ${manifest.missingRequired.joinToString()}" }
        val appearance = factory.create(manifest.files.getValue(ModelNames.APPEARANCE).file)
        val motion = factory.create(manifest.files.getValue(ModelNames.MOTION).file)
        stitching = factory.create(manifest.files.getValue(ModelNames.STITCHING).file)
        stitchingEye = factory.create(manifest.files.getValue(ModelNames.STITCHING_EYE).file)
        stitchingLip = factory.create(manifest.files.getValue(ModelNames.STITCHING_LIP).file)
        val warpOptions = factory.defaultOptions().also(customOps::configure)
        warping = factory.create(manifest.files.getValue(ModelNames.WARPING).file, warpOptions)
        val input = ImageTensor(env, source)
        input.tensor.use {
            val featureResult = runOne(appearance, "img", it, "output")
            val motionResult = runOne(motion, "img", it, "kp")
            TensorContract.requireFeature3d(featureResult.shape)
            feature = Feature3dTensor(featureResult.data, featureResult.shape)
            kpSource = motionResult.data
            defaultStitch = runStitch(kpSource!!, kpSource!!)
        }
        appearance.close(); motion.close()
    }

    fun runStitch(source: FloatArray, driving: FloatArray): FloatArray {
        val input = FloatArray(126); source.copyInto(input); driving.copyInto(input, 63)
        return runOne(stitching!!, "input", OnnxTensor.createTensor(env, FloatBuffer.wrap(input), longArrayOf(1,126)), "output").data
    }

    fun runRetarget(session: OrtSession, kp: FloatArray, ratios: FloatArray): FloatArray {
        val input = FloatArray(63 + ratios.size); kp.copyInto(input); ratios.copyInto(input, 63)
        return runOne(session, "input", OnnxTensor.createTensor(env, FloatBuffer.wrap(input), longArrayOf(1,input.size.toLong())), "output").data
    }

    private fun runOne(session: OrtSession, name: String, input: OnnxTensor, output: String): TensorData {
        input.use {
            session.run(mapOf(name to input)).use { result ->
                val value = result[output].get().value
                return tensorData(value)
            }
        }
    }

    private fun tensorData(value: Any): TensorData {
        if (value is OnnxTensor) {
            value.use {
                val shape = it.info.shape
                val buffer = it.floatBuffer
                return TensorData(FloatArray(buffer.remaining()).also(buffer::get), shape)
            }
        }

        val flattened = FloatArrayBuilder()
        val shape = nestedFloatShape(value)
        flattenFloats(value, flattened)
        return TensorData(flattened.toArray(), shape)
    }

    private fun nestedFloatShape(value: Any): LongArray {
        val dims = ArrayList<Long>()
        var current: Any? = value
        while (current != null && current.javaClass.isArray) {
            val length = java.lang.reflect.Array.getLength(current)
            dims += length.toLong()
            if (length == 0) break
            current = java.lang.reflect.Array.get(current, 0)
        }
        if (dims.isEmpty()) throw IllegalArgumentException("Unsupported ONNX output type: ${value.javaClass.name}")
        return dims.toLongArray()
    }

    private fun flattenFloats(value: Any, out: FloatArrayBuilder) {
        if (value is FloatArray) {
            value.forEach(out::add)
            return
        }
        if (!value.javaClass.isArray) {
            when (value) {
                is Number -> out.add(value.toFloat())
                else -> throw IllegalArgumentException("Unsupported ONNX output element type: ${value.javaClass.name}")
            }
            return
        }
        val length = java.lang.reflect.Array.getLength(value)
        for (i in 0 until length) flattenFloats(java.lang.reflect.Array.get(value, i), out)
    }

    override fun close() = closeSessions()
    private fun closeSessions(){ warping?.close(); stitching?.close(); stitchingEye?.close(); stitchingLip?.close(); warping=null; stitching=null; stitchingEye=null; stitchingLip=null; feature=null; kpSource=null; defaultStitch=null }
}

data class TensorData(val data: FloatArray, val shape: LongArray)

private class FloatArrayBuilder {
    private var data = FloatArray(1024)
    private var size = 0
    fun add(value: Float) {
        if (size == data.size) data = data.copyOf(data.size * 2)
        data[size++] = value
    }
    fun toArray(): FloatArray = data.copyOf(size)
}

private class ImageTensor(val env: OrtEnvironment, bitmap: Bitmap) {
    val tensor: OnnxTensor
    init {
        val scaled=Bitmap.createScaledBitmap(bitmap,256,256,true); val pixels=IntArray(256*256); scaled.getPixels(pixels,0,256,0,0,256,256); if(scaled!==bitmap) scaled.recycle()
        val plane=256*256; val data=FloatArray(plane*3)
        for(i in pixels.indices){ val c=pixels[i]; data[i]=((c ushr 16) and 255)/255f; data[plane+i]=((c ushr 8) and 255)/255f; data[plane*2+i]=(c and 255)/255f }
        tensor=OnnxTensor.createTensor(env,FloatBuffer.wrap(data),longArrayOf(1,3,256,256))
    }
}
