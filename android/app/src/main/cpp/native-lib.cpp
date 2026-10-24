#include <jni.h>
#include <android/log.h>
#include <string>

#define LOG_TAG "KemzyNative"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)

extern "C" JNIEXPORT jstring JNICALL
Java_com_kemzy_liveavatar_renderer_PortraitGlRenderer_nativeSystemDiagnostics(
        JNIEnv* env, jobject /* this */) {
    const std::string info =
        "Native subsystem: operational; C++ engine initialized; GLES3 renderer scaffold ready.";
    return env->NewStringUTF(info.c_str());
}

extern "C" JNIEXPORT void JNICALL
Java_com_kemzy_liveavatar_renderer_PortraitGlRenderer_nativeInit(
        JNIEnv* /* env */, jobject /* this */) {
    LOGI("Native OpenGL ES foundation initialized.");
}
