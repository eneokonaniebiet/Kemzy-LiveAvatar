#include <android/log.h>
#include <jni.h>
#include <cstddef>
#include <limits>
#include <vector>

#define LOG_TAG "KemzyEngine"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace kemzy {

struct MeshVertex {
    float x;
    float y;
    float z;
    float u;
    float v;
};

class FaceItEngine {
public:
    bool generatePlanarMeshGrid(int cols, int rows) {
        // uint16 indices can address at most 65,536 vertices. Keep dimensions
        // bounded to protect mobile memory and prevent integer overflow.
        if (cols < 2 || rows < 2 || cols > 256 || rows > 256 ||
            static_cast<std::size_t>(cols) * static_cast<std::size_t>(rows) >
                static_cast<std::size_t>(std::numeric_limits<unsigned short>::max()) + 1U) {
            LOGE("Invalid mesh grid dimensions: %d x %d", cols, rows);
            return false;
        }

        vertices.clear();
        indices.clear();
        const auto vertexCount = static_cast<std::size_t>(cols) *
                                 static_cast<std::size_t>(rows);
        vertices.reserve(vertexCount);
        indices.reserve(static_cast<std::size_t>(cols - 1) *
                        static_cast<std::size_t>(rows - 1) * 6U);

        for (int r = 0; r < rows; ++r) {
            const float vCoord = static_cast<float>(r) / static_cast<float>(rows - 1);
            const float yPos = 1.0F - (2.0F * vCoord);
            for (int c = 0; c < cols; ++c) {
                const float uCoord = static_cast<float>(c) / static_cast<float>(cols - 1);
                const float xPos = (2.0F * uCoord) - 1.0F;
                vertices.push_back({xPos, yPos, 0.0F, uCoord, vCoord});
            }
        }

        for (int r = 0; r < rows - 1; ++r) {
            for (int c = 0; c < cols - 1; ++c) {
                const auto topLeft = static_cast<unsigned short>(r * cols + c);
                const auto topRight = static_cast<unsigned short>(topLeft + 1U);
                const auto bottomLeft =
                    static_cast<unsigned short>((r + 1) * cols + c);
                const auto bottomRight =
                    static_cast<unsigned short>(bottomLeft + 1U);

                // Two triangles per grid cell.
                indices.insert(indices.end(),
                    {topLeft, bottomLeft, topRight,
                     topRight, bottomLeft, bottomRight});
            }
        }

        return vertices.size() == vertexCount;
    }

    const MeshVertex* getVertexData() const { return vertices.data(); }
    int getVertexCount() const { return static_cast<int>(vertices.size()); }
    const unsigned short* getIndexData() const { return indices.data(); }
    int getIndexCount() const { return static_cast<int>(indices.size()); }

private:
    std::vector<MeshVertex> vertices;
    std::vector<unsigned short> indices;
};

}  // namespace kemzy

// JNI owns the lifecycle entry points; mesh state remains native and camera
// pixels are never accepted by this interface.
namespace {
kemzy::FaceItEngine gEngine;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_kemzy_liveavatar_renderer_PortraitGlRenderer_nativeInitializeMesh(
        JNIEnv*, jobject, jint cols, jint rows) {
    return gEngine.generatePlanarMeshGrid(cols, rows) ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_kemzy_liveavatar_renderer_PortraitGlRenderer_nativeMeshVertexCount(
        JNIEnv*, jobject) {
    return static_cast<jint>(gEngine.getVertexCount());
}

extern "C" JNIEXPORT jint JNICALL
Java_com_kemzy_liveavatar_renderer_PortraitGlRenderer_nativeMeshIndexCount(
        JNIEnv*, jobject) {
    return static_cast<jint>(gEngine.getIndexCount());
}


extern "C" JNIEXPORT jobject JNICALL
Java_com_kemzy_liveavatar_renderer_PortraitGlRenderer_nativeVertexBuffer(
        JNIEnv* env, jobject) {
    const auto* data = gEngine.getVertexData();
    const auto bytes = static_cast<jlong>(gEngine.getVertexCount()) *
                       static_cast<jlong>(sizeof(kemzy::MeshVertex));
    if (data == nullptr || bytes <= 0) return nullptr;
    // The direct buffer views native vector storage; it remains valid until the
    // next mesh regeneration. Kotlin uploads it immediately on the GL thread.
    return env->NewDirectByteBuffer(const_cast<kemzy::MeshVertex*>(data), bytes);
}

extern "C" JNIEXPORT jobject JNICALL
Java_com_kemzy_liveavatar_renderer_PortraitGlRenderer_nativeIndexBuffer(
        JNIEnv* env, jobject) {
    const auto* data = gEngine.getIndexData();
    const auto bytes = static_cast<jlong>(gEngine.getIndexCount()) *
                       static_cast<jlong>(sizeof(unsigned short));
    if (data == nullptr || bytes <= 0) return nullptr;
    return env->NewDirectByteBuffer(
        const_cast<unsigned short*>(data), bytes);
}
