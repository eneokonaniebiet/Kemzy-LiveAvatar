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
        input.use { val result = session.run(mapOf(name to input)); result.use { val t=it[output].get().value as OnnxTensor; t.use { val b=t.floatBuffer; return TensorData(FloatArray(b.remaining()).also(b::get), t.info.shape) } } }
    }

    override fun close() = closeSessions()
    private fun closeSessions(){ warping?.close(); stitching?.close(); stitchingEye?.close(); stitchingLip?.close(); warping=null; stitching=null; stitchingEye=null; stitchingLip=null; feature=null; kpSource=null; defaultStitch=null }
}

data class TensorData(val data: FloatArray, val shape: LongArray)

private class ImageTensor(val env: OrtEnvironment, bitmap: Bitmap) {
    val tensor: OnnxTensor
    init {
        val scaled=Bitmap.createScaledBitmap(bitmap,256,256,true); val pixels=IntArray(256*256); scaled.getPixels(pixels,0,256,0,0,256,256); if(scaled!==bitmap) scaled.recycle()
        val plane=256*256; val data=FloatArray(plane*3)
        for(i in pixels.indices){ val c=pixels[i]; data[i]=((c ushr 16) and 255)/255f; data[plane+i]=((c ushr 8) and 255)/255f; data[plane*2+i]=(c and 255)/255f }
        tensor=OnnxTensor.createTensor(env,FloatBuffer.wrap(data),longArrayOf(1,3,256,256))
    }
}
