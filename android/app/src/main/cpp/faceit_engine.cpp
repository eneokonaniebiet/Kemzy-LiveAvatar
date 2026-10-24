#include <android/log.h>
#include <jni.h>
#include <algorithm>
#include <cmath>
#include <cstddef>
#include <limits>
#include <vector>

#include "voice_lipsync_bridge.h"

#define LOG_TAG "KemzyEngine"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

extern "C" bool kemzyUpdateBoneMatrices(const float* poseJoints, std::size_t jointCount);

namespace kemzy {

struct MeshVertex {
    float x;
    float y;
    float z;
    float u;
    float v;
    float base_x;
    float base_y;
    float rest_z;
    int bone_id;
    float skin_weight;
};

static_assert(sizeof(MeshVertex) == 40, "MeshVertex must match the native rigged 40-byte layout");

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
        referenceLandmarks.clear();
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
                vertices.push_back({xPos, yPos, 0.0F, uCoord, vCoord,
                                    xPos, yPos, 0.0F, 0, 1.0F});
            }
        }

        for (int r = 0; r < rows - 1; ++r) {
            for (int c = 0; c < cols - 1; ++c) {
                const auto topLeft = static_cast<unsigned short>(r * cols + c);
                const auto topRight = static_cast<unsigned short>(topLeft + 1U);
                const auto bottomLeft =
                    static_cast<unsigned short>((r + 1) * cols + c);
                const auto bottomRight = static_cast<unsigned short>(bottomLeft + 1U);

                // Two triangles per grid cell.
                indices.insert(indices.end(),
                    {topLeft, bottomLeft, topRight,
                     topRight, bottomLeft, bottomRight});
            }
        }

        return vertices.size() == vertexCount;
    }

    bool updateHolisticTracking(const float* face, int faceCount,
                                const float* pose, int poseCount,
                                const float* leftHand, int leftHandCount,
                                const float* rightHand, int rightHandCount) {
        if (!validStream(face, faceCount, 478) || !validStream(pose, poseCount, 33) ||
            !validStream(leftHand, leftHandCount, 21) ||
            !validStream(rightHand, rightHandCount, 21)) {
            LOGE("Rejected invalid holistic landmark payload");
            return false;
        }
        if (faceCount > 0 && !deformMeshWithLandmarks(face, faceCount)) return false;
        poseLandmarks.clear(); leftHandLandmarks.clear(); rightHandLandmarks.clear();
        if (poseCount > 0) poseLandmarks.assign(pose, pose + poseCount * 3);
        if (leftHandCount > 0) leftHandLandmarks.assign(leftHand, leftHand + leftHandCount * 3);
        if (rightHandCount > 0) rightHandLandmarks.assign(rightHand, rightHand + rightHandCount * 3);
        return faceCount > 0;
    }

    const MeshVertex* getVertexData() const { return vertices.data(); }
    int getVertexCount() const { return static_cast<int>(vertices.size()); }
    const unsigned short* getIndexData() const { return indices.data(); }
    int getIndexCount() const { return static_cast<int>(indices.size()); }

    // Input is MediaPipe normalized XYZ triplets. The first valid frame becomes
    // the neutral pose; subsequent per-landmark motion deforms the portrait mesh.
    // This is a lightweight motion-transfer prototype, not identity/pose fitting.
    bool deformMeshWithLandmarks(const float* points, int count) {
        if (vertices.empty() || points == nullptr || count < 1 || count > 478) {
            return false;
        }
        const std::size_t required = static_cast<std::size_t>(count) * 3U;
        for (std::size_t i = 0; i < required; ++i) {
            if (!std::isfinite(points[i])) return false;
        }

        if (referenceLandmarks.size() != required) {
            referenceLandmarks.assign(points, points + required);
            for (auto& vertex : vertices) {
                vertex.x = vertex.base_x;
                vertex.y = vertex.base_y;
            }
            return true;
        }

        constexpr float influenceRadiusSquared = 0.10F;
        constexpr float maxDisplacement = 0.12F;
        constexpr float smoothingAlpha = 0.32F;
        for (auto& vertex : vertices) {
            float weightSum = 0.0F;
            float weightedDx = 0.0F;
            float weightedDy = 0.0F;
            for (int i = 0; i < count; ++i) {
                const std::size_t offset = static_cast<std::size_t>(i) * 3U;
                const float refX = referenceLandmarks[offset] * 2.0F - 1.0F;
                const float refY = 1.0F - referenceLandmarks[offset + 1U] * 2.0F;
                const float dx = vertex.base_x - refX;
                const float dy = vertex.base_y - refY;
                const float distanceSquared = dx * dx + dy * dy;
                // Gaussian-like local influence avoids the global pull of plain IDW.
                if (distanceSquared > influenceRadiusSquared) continue;
                const float weight = 1.0F / (0.0025F + distanceSquared);
                const float moveX = (points[offset] - referenceLandmarks[offset]) * 2.0F;
                const float moveY = -(points[offset + 1U] - referenceLandmarks[offset + 1U]) * 2.0F;
                weightedDx += moveX * weight;
                weightedDy += moveY * weight;
                weightSum += weight;
            }

            float targetDx = 0.0F;
            float targetDy = 0.0F;
            if (weightSum > 0.0F) {
                targetDx = (weightedDx / weightSum) * 0.65F;
                targetDy = (weightedDy / weightSum) * 0.65F;
            }
            targetDx = std::max(-maxDisplacement, std::min(maxDisplacement, targetDx));
            targetDy = std::max(-maxDisplacement, std::min(maxDisplacement, targetDy));
            vertex.x += (vertex.base_x + targetDx - vertex.x) * smoothingAlpha;
            vertex.y += (vertex.base_y + targetDy - vertex.y) * smoothingAlpha;
        }

        // Approach A native hook: audio state is owned by the process-wide
        // voice processor and is applied here after tracking deformation,
        // immediately before this native vertex buffer is exposed for upload.
        kemzyExecuteLiveMouthDeformation(vertices.data(), vertices.size());
        return true;
    }

private:
    static bool validStream(const float* data, int count, int maxCount) {
        if (count < 0 || count > maxCount || (count > 0 && data == nullptr)) return false;
        const std::size_t size = static_cast<std::size_t>(count) * 3U;
        for (std::size_t i = 0; i < size; ++i) {
            if (!std::isfinite(data[i])) return false;
        }
        return true;
    }

    std::vector<MeshVertex> vertices;
    std::vector<unsigned short> indices;
    std::vector<float> referenceLandmarks;
    std::vector<float> poseLandmarks;
    std::vector<float> leftHandLandmarks;
    std::vector<float> rightHandLandmarks;
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

// Numeric XYZ arrays only; JNI copies inputs into temporary vectors so no
// pinned Java arrays survive the call. Null arrays represent temporarily missing tracking.
extern "C" JNIEXPORT jboolean JNICALL
Java_com_kemzy_liveavatar_renderer_PortraitGlRenderer_nativeUpdateHolisticTracking(
        JNIEnv* env, jobject, jfloatArray faceArray, jfloatArray poseArray,
        jfloatArray leftHandArray, jfloatArray rightHandArray) {
    auto readStream = [env](jfloatArray array, int maxCount, std::vector<float>& out) -> bool {
        out.clear();
        if (array == nullptr) return true;
        const jsize length = env->GetArrayLength(array);
        if (length % 3 != 0 || length / 3 > maxCount) return false;
        out.resize(static_cast<std::size_t>(length));
        if (length > 0) env->GetFloatArrayRegion(array, 0, length, out.data());
        if (env->ExceptionCheck()) {
            env->ExceptionClear();
            out.clear();
            return false;
        }
        for (float value : out) if (!std::isfinite(value)) return false;
        return true;
    };

    std::vector<float> face, pose, leftHand, rightHand;
    if (!readStream(faceArray, 478, face) || !readStream(poseArray, 33, pose) ||
        !readStream(leftHandArray, 21, leftHand) || !readStream(rightHandArray, 21, rightHand)) {
        LOGE("Rejected malformed JNI tracking arrays");
        return JNI_FALSE;
    }
    const bool engineUpdated = gEngine.updateHolisticTracking(
        face.empty() ? nullptr : face.data(), static_cast<int>(face.size() / 3U),
        pose.empty() ? nullptr : pose.data(), static_cast<int>(pose.size() / 3U),
        leftHand.empty() ? nullptr : leftHand.data(), static_cast<int>(leftHand.size() / 3U),
        rightHand.empty() ? nullptr : rightHand.data(), static_cast<int>(rightHand.size() / 3U));
    // Update the stable native matrix buffer on the same GL thread that owns this JNI call.
    // The processor validates pose length and applies its 0.32 temporal smoothing.
    if (!pose.empty()) {
        (void)kemzyUpdateBoneMatrices(pose.data(), pose.size() / 3U);
    }
    return engineUpdated ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_kemzy_liveavatar_renderer_PortraitGlRenderer_nativeDeformMesh(
        JNIEnv* env, jobject, jfloatArray landmarkData, jint count) {
    if (landmarkData == nullptr || count < 1 || count > 478 ||
        env->GetArrayLength(landmarkData) < count * 3) {
        return JNI_FALSE;
    }
    jboolean copied = JNI_FALSE;
    jfloat* points = env->GetFloatArrayElements(landmarkData, &copied);
    if (points == nullptr) return JNI_FALSE;
    const bool ok = gEngine.deformMeshWithLandmarks(points, count);
    env->ReleaseFloatArrayElements(landmarkData, points, JNI_ABORT);
    return ok ? JNI_TRUE : JNI_FALSE;
}
