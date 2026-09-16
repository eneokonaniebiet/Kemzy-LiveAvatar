package com.kemzy.liveavatar.inference

import android.content.Context
import java.io.File

class CustomOpsLoader(private val context: Context) {
    fun libraryFile(): File {
        val file = File(context.applicationInfo.nativeLibraryDir, "libkemzy_ort_customops.so")
        require(file.isFile) { "GridSample3D custom-op library is missing from APK: ${file.path}" }
        return file
    }

    fun configure(options: ai.onnxruntime.OrtSession.SessionOptions) {
        options.registerCustomOpLibrary(libraryFile().absolutePath)
    }
}
