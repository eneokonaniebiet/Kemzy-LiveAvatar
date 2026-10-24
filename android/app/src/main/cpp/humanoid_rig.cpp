#include <jni.h>
#include <algorithm>
#include <array>
#include <cmath>
#include <cstddef>
#include <vector>
#include <mutex>

// Isolated prototype only. This file does not change the active diagnostic face mesh.
// Real LBS requires a supplied 3D mesh, per-vertex weights, and inverse-bind matrices.
namespace kemzy {

struct JointNode {
    float x = 0.0f;
    float y = 0.0f;
    float z = 0.0f;
    int parentId = -1;
};

struct RiggedCostumeVertex {
    float x = 0.0f;
    float y = 0.0f;
    float z = 0.0f;
    float u = 0.0f;
    float v = 0.0f;
    float restX = 0.0f;
    float restY = 0.0f;
    float restZ = 0.0f;
    int boneId = 0;       // 0=head/root, 1=torso, 2=left arm, 3=right arm
    float skinWeight = 0.0f;
};

class HumanoidRig {
public:
    static constexpr std::size_t kPoseJointCount = 33;
    static constexpr std::size_t kMaxVertices = 200000;

    // Generate deterministic test geometry for validating the rigged 40-byte vertex
    // layout. This is a simple UV-sphere proxy (not a realistic costume or photo-derived mesh).
    // Each region has one bone influence; the active portrait renderer remains unchanged.
    bool generateTestingHumanoidTopology() {
        constexpr int kLatitudeSegments = 8;
        constexpr int kLongitudeSegments = 12;
        vertices_.clear();
        indices_.clear();

        const auto appendEllipsoid = [&](float cx, float cy, float cz,
                                         float rx, float ry, float rz,
                                         int boneId) -> bool {
            const std::size_t firstVertex = vertices_.size();
            const std::size_t added = static_cast<std::size_t>(
                (kLatitudeSegments + 1) * (kLongitudeSegments + 1));
            if (firstVertex + added > kMaxVertices ||
                firstVertex + added > 65536U) return false;

            for (int lat = 0; lat <= kLatitudeSegments; ++lat) {
                const float phi = 3.14159265358979323846f *
                    static_cast<float>(lat) / static_cast<float>(kLatitudeSegments);
                const float sinPhi = std::sin(phi);
                const float cosPhi = std::cos(phi);
                for (int lon = 0; lon <= kLongitudeSegments; ++lon) {
                    const float theta = 6.28318530717958647692f *
                        static_cast<float>(lon) / static_cast<float>(kLongitudeSegments);
                    RiggedCostumeVertex v;
                    v.x = cx + rx * sinPhi * std::cos(theta);
                    v.y = cy + ry * cosPhi;
                    v.z = cz + rz * sinPhi * std::sin(theta);
                    v.u = static_cast<float>(lon) / static_cast<float>(kLongitudeSegments);
                    v.v = static_cast<float>(lat) / static_cast<float>(kLatitudeSegments);
                    v.restX = v.x;
                    v.restY = v.y;
                    v.restZ = v.z;
                    v.boneId = boneId;
                    v.skinWeight = 1.0f;
                    vertices_.push_back(v);
                }
            }

            for (int lat = 0; lat < kLatitudeSegments; ++lat) {
                for (int lon = 0; lon < kLongitudeSegments; ++lon) {
                    const auto a = static_cast<unsigned short>(
                        firstVertex + static_cast<std::size_t>(lat * (kLongitudeSegments + 1) + lon));
                    const auto b = static_cast<unsigned short>(a + kLongitudeSegments + 1);
                    const auto c = static_cast<unsigned short>(a + 1);
                    const auto d = static_cast<unsigned short>(b + 1);
                    indices_.push_back(a); indices_.push_back(b); indices_.push_back(c);
                    indices_.push_back(c); indices_.push_back(b); indices_.push_back(d);
                }
            }
            return true;
        };

        // Head/root, torso, left arm, right arm; all coordinates are in local test space.
        if (!appendEllipsoid(0.0f, 0.58f, 0.0f, 0.15f, 0.18f, 0.11f, 0) ||
            !appendEllipsoid(0.0f, 0.12f, 0.0f, 0.25f, 0.31f, 0.12f, 1) ||
            !appendEllipsoid(0.34f, 0.20f, 0.0f, 0.10f, 0.28f, 0.09f, 2) ||
            !appendEllipsoid(-0.34f, 0.20f, 0.0f, 0.10f, 0.28f, 0.09f, 3)) {
            vertices_.clear();
            indices_.clear();
            return false;
        }
        hasPose_ = false;
        return !vertices_.empty() && !indices_.empty() && indices_.size() % 3U == 0U;
    }

    // Reserve an empty mesh container for a validated external asset.
    bool initializeVertexCapacity(std::size_t vertexCount) {
        if (vertexCount > kMaxVertices) return false;
        vertices_.clear();
        vertices_.resize(vertexCount);
        return true;
    }

    bool setVertex(std::size_t index, const RiggedCostumeVertex& vertex) {
        if (index >= vertices_.size()) return false;
        if (!finite(vertex.x) || !finite(vertex.y) || !finite(vertex.z) ||
            !finite(vertex.u) || !finite(vertex.v) ||
            !finite(vertex.restX) || !finite(vertex.restY) || !finite(vertex.restZ) ||
            !finite(vertex.skinWeight) || vertex.boneId < 0 || vertex.boneId > 3 ||
            vertex.skinWeight < 0.0f || vertex.skinWeight > 1.0f) {
            return false;
        }
        vertices_[index] = vertex;
        return true;
    }

    bool setIndices(const std::vector<unsigned short>& indices) {
        if (indices.size() % 3 != 0) return false;
        for (unsigned short index : indices) {
            if (static_cast<std::size_t>(index) >= vertices_.size()) return false;
        }
        indices_ = indices;
        return true;
    }

    // Accept normalized MediaPipe XYZ triplets. This isolated prototype uses
    // landmark displacement as a safe translation test, not production LBS.
    bool updatePose(const float* pose, std::size_t landmarkCount) {
        if (pose == nullptr || landmarkCount < 17 || landmarkCount > kPoseJointCount) {
            return false;
        }
        for (std::size_t i = 0; i < landmarkCount * 3; ++i) {
            if (!finite(pose[i])) return false;
        }

        for (std::size_t i = 0; i < landmarkCount; ++i) {
            joints_[i].x = pose[i * 3] * 2.0f - 1.0f;
            joints_[i].y = 1.0f - pose[i * 3 + 1] * 2.0f;
            joints_[i].z = pose[i * 3 + 2];
        }

        // Preserve vertices as-is until a real reference mesh and bind-pose
        // anchors are provided. This verifies pose data can be validated/stored.
        hasPose_ = true;
        return true;
    }

    const std::vector<RiggedCostumeVertex>& vertices() const { return vertices_; }
    const std::vector<unsigned short>& indices() const { return indices_; }
    bool hasPose() const { return hasPose_; }

private:
    static bool finite(float value) { return std::isfinite(value); }

    std::vector<RiggedCostumeVertex> vertices_;
    std::vector<unsigned short> indices_;
    std::array<JointNode, kPoseJointCount> joints_{};
    bool hasPose_ = false;
};


// Validated asset-to-rig binding gateway. The caller must supply a real, already
// rigged mesh; a single bone ID/weight per vertex is intentionally limited and
// is not a substitute for multi-influence skinning or inverse-bind matrices.
class HumanoidRigMeshBinder {
public:
    bool bind3DPhotoAssetTopology(
        HumanoidRig& targetRig,
        const float* sourceVerticesCoords,
        const float* sourceTexCoords,
        const int* assignedBoneIds,
        const float* blendSkinWeights,
        std::size_t rawVertexCount,
        const unsigned short* indexMatrix,
        std::size_t indexCount) {
        if (rawVertexCount == 0 || rawVertexCount > HumanoidRig::kMaxVertices ||
            sourceVerticesCoords == nullptr || sourceTexCoords == nullptr ||
            assignedBoneIds == nullptr || blendSkinWeights == nullptr ||
            indexMatrix == nullptr || indexCount == 0 || indexCount % 3 != 0 ||
            indexCount > 600000 || rawVertexCount > 65536) {
            return false;
        }

        // Stage and validate everything before modifying targetRig, so invalid
        // asset input cannot leave a partially populated mesh.
        std::vector<RiggedCostumeVertex> stagedVertices;
        stagedVertices.reserve(rawVertexCount);
        for (std::size_t i = 0; i < rawVertexCount; ++i) {
            RiggedCostumeVertex vertexLayout;
            vertexLayout.restX = sourceVerticesCoords[i * 3];
            vertexLayout.restY = sourceVerticesCoords[i * 3 + 1];
            vertexLayout.restZ = sourceVerticesCoords[i * 3 + 2];
            vertexLayout.x = vertexLayout.restX;
            vertexLayout.y = vertexLayout.restY;
            vertexLayout.z = vertexLayout.restZ;
            vertexLayout.u = sourceTexCoords[i * 2];
            vertexLayout.v = sourceTexCoords[i * 2 + 1];
            vertexLayout.boneId = assignedBoneIds[i];
            vertexLayout.skinWeight = blendSkinWeights[i];

            if (!std::isfinite(vertexLayout.x) || !std::isfinite(vertexLayout.y) ||
                !std::isfinite(vertexLayout.z) || !std::isfinite(vertexLayout.u) ||
                !std::isfinite(vertexLayout.v) || vertexLayout.boneId < 0 ||
                vertexLayout.boneId > 3 || !std::isfinite(vertexLayout.skinWeight) ||
                vertexLayout.skinWeight < 0.0f || vertexLayout.skinWeight > 1.0f) {
                return false;
            }
            stagedVertices.push_back(vertexLayout);
        }

        std::vector<unsigned short> stagedIndices(indexMatrix, indexMatrix + indexCount);
        for (unsigned short index : stagedIndices) {
            if (static_cast<std::size_t>(index) >= rawVertexCount) return false;
        }

        if (!targetRig.initializeVertexCapacity(rawVertexCount)) return false;
        for (std::size_t i = 0; i < stagedVertices.size(); ++i) {
            if (!targetRig.setVertex(i, stagedVertices[i])) return false;
        }
        return targetRig.setIndices(stagedIndices);
    }
};

}  // namespace kemzy


// Four-bone landmark-to-matrix bridge. Matrices use OpenGL column-major layout.
// This is a translation-only prototype, not rotational skeletal skinning.
namespace kemzy {
struct Matrix4x4 {
    std::array<float, 16> m{{
        1.0f, 0.0f, 0.0f, 0.0f,
        0.0f, 1.0f, 0.0f, 0.0f,
        0.0f, 0.0f, 1.0f, 0.0f,
        0.0f, 0.0f, 0.0f, 1.0f
    }};

    void setTranslation(float tx, float ty, float tz) {
        m[12] = tx;
        m[13] = ty;
        m[14] = tz;
    }
};

class HumanoidRigBoneProcessor {
public:
    static constexpr std::size_t kBoneCount = 4;
    static constexpr float kSmoothingAlpha = 0.32f;

    bool calculateBoneMatricesFromLandmarks(const float* poseJoints, std::size_t jointCount) {
        if (poseJoints == nullptr || jointCount < 25 || jointCount > 33) {
            reset();
            return false;
        }
        for (std::size_t i = 0; i < jointCount * 3U; ++i) {
            if (!std::isfinite(poseJoints[i])) {
                reset();
                return false;
            }
        }

        // MediaPipe Pose: nose=0, shoulders=11/12, hips=23/24.
        const auto point = [poseJoints](std::size_t joint) {
            return std::array<float, 3>{{
                poseJoints[joint * 3U] * 2.0f - 1.0f,
                1.0f - poseJoints[joint * 3U + 1U] * 2.0f,
                poseJoints[joint * 3U + 2U]
            }};
        };
        const auto nose = point(0);
        const auto leftShoulder = point(11);
        const auto rightShoulder = point(12);
        const auto leftHip = point(23);
        const auto rightHip = point(24);

        const auto midpoint = [](const std::array<float, 3>& a,
                                 const std::array<float, 3>& b) {
            return std::array<float, 3>{{
                (a[0] + b[0]) * 0.5f, (a[1] + b[1]) * 0.5f, (a[2] + b[2]) * 0.5f
            }};
        };
        const auto shoulderCenter = midpoint(leftShoulder, rightShoulder);
        const auto hipCenter = midpoint(leftHip, rightHip);
        const std::array<std::array<float, 3>, kBoneCount> target{{
            {{nose[0] - shoulderCenter[0], nose[1] - shoulderCenter[1], nose[2] - shoulderCenter[2]}},
            {{(shoulderCenter[0] + hipCenter[0]) * 0.5f,
              (shoulderCenter[1] + hipCenter[1]) * 0.5f,
              (shoulderCenter[2] + hipCenter[2]) * 0.5f}},
            {{leftShoulder[0] - (-0.4f), leftShoulder[1] - 0.3f, leftShoulder[2]}},
            {{rightShoulder[0] - 0.4f, rightShoulder[1] - 0.3f, rightShoulder[2]}}
        }};

        for (std::size_t bone = 0; bone < kBoneCount; ++bone) {
            for (std::size_t axis = 0; axis < 3; ++axis) {
                const float next = target[bone][axis];
                float& current = boneMatrices_[bone].m[12U + axis];
                current = hasPose_ ? current + (next - current) * kSmoothingAlpha : next;
            }
        }
        hasPose_ = true;
        return true;
    }

    const float* data() const { return boneMatrices_[0].m.data(); }
    std::size_t byteSize() const { return sizeof(boneMatrices_); }

    void reset() {
        for (auto& matrix : boneMatrices_) matrix = Matrix4x4{};
        hasPose_ = false;
    }

private:
    std::array<Matrix4x4, kBoneCount> boneMatrices_{};
    bool hasPose_ = false;
};

static HumanoidRigBoneProcessor gBoneProcessor;
} // namespace kemzy

extern "C" bool kemzyUpdateBoneMatrices(const float* poseJoints, std::size_t jointCount) {
    return kemzy::gBoneProcessor.calculateBoneMatricesFromLandmarks(poseJoints, jointCount);
}

extern "C" const float* kemzyGetBoneMatricesData() {
    return kemzy::gBoneProcessor.data();
}

extern "C" std::size_t kemzyGetBoneMatricesByteSize() {
    return kemzy::gBoneProcessor.byteSize();
}

extern "C" JNIEXPORT jobject JNICALL
Java_com_kemzy_liveavatar_renderer_PortraitGlRenderer_nativeGetBoneMatricesBuffer(
        JNIEnv* env, jobject) {
    const float* data = kemzyGetBoneMatricesData();
    const auto bytes = static_cast<jlong>(kemzyGetBoneMatricesByteSize());
    if (data == nullptr || bytes != static_cast<jlong>(4U * 16U * sizeof(float))) return nullptr;
    return env->NewDirectByteBuffer(const_cast<float*>(data), bytes);
}

/**
 * Offline, model-agnostic viseme smoothing/deformation stage.
 * The caller supplies numeric viseme weights from a future on-device audio model;
 * this class does not capture or transmit microphone audio.
 *
 * Current mesh format has one bone influence per vertex. Mouth-region thresholds
 * are provisional and must be calibrated against the final normalized head mesh.
 */
namespace kemzy {
class HumanoidVoiceLipSyncProcessor {
public:
    static constexpr std::size_t kVisemeCount = 12;
    static constexpr float kAudioSmoothingAlpha = 0.28f;

    void updateMouthShapesFromAudio(const float* incomingVisemeWeights,
                                    std::size_t weightCount) {
        if (incomingVisemeWeights == nullptr || weightCount < kVisemeCount) return;
        const std::lock_guard<std::mutex> guard(mutex_);
        for (std::size_t i = 0; i < kVisemeCount; ++i) {
            if (!std::isfinite(incomingVisemeWeights[i])) continue;
            const float target = std::max(0.0f, std::min(1.0f, incomingVisemeWeights[i]));
            targetVisemes_[i] =
                kAudioSmoothingAlpha * target +
                (1.0f - kAudioSmoothingAlpha) * targetVisemes_[i];
        }
    }

    // Apply smoothed audio-driven jaw motion to the lower-mouth region.
    // This performs no heap allocation. Bounds are provisional and must be
    // calibrated to the final normalized head mesh before production use.
    void executeLiveMouthDeformation(RiggedCostumeVertex* mutableVertices,
                                     std::size_t totalVertices) const {
        if (mutableVertices == nullptr || totalVertices == 0) return;
        const std::lock_guard<std::mutex> guard(mutex_);

        const float jawOpenFactor = std::max(0.0f, std::min(1.0f, targetVisemes_[0]));
        const float jawDisplacementY = std::max(0.0f, std::min(0.09f, jawOpenFactor * 0.12f));

        for (std::size_t i = 0; i < totalVertices; ++i) {
            auto& vertex = mutableVertices[i];
            // Only touch the head's lower central mouth region. Rebuild this
            // region from restY each call to avoid cumulative frame-to-frame drift.
            if (vertex.boneId == 0 && vertex.restY < 0.54f &&
                vertex.restY > 0.44f && std::abs(vertex.restX) < 0.15f) {
                vertex.y = vertex.restY - jawDisplacementY *
                    std::max(0.0f, std::min(1.0f, vertex.skinWeight));
            }
        }
    }

    // Backwards-compatible name for existing prototype callers.
    void blendMouthDeformation(RiggedCostumeVertex* meshVertices,
                               std::size_t vertexCount) const {
        executeLiveMouthDeformation(meshVertices, vertexCount);
    }

    const std::array<float, kVisemeCount>& visemes() const { return targetVisemes_; }

private:
    mutable std::mutex mutex_;
    std::array<float, kVisemeCount> targetVisemes_{};
};
} // namespace kemzy

// One process-wide smoothed voice processor. The incoming audio coefficient
// updates state only; mesh deformation is called separately by a future active draw pass.
static kemzy::HumanoidVoiceLipSyncProcessor gVoiceProcessor;

extern "C" JNIEXPORT void JNICALL
Java_com_kemzy_liveavatar_MainActivity_nativeUpdateVoiceViseme(
        JNIEnv* /* env */, jobject /* thiz */, jfloat jawOpenFactor) {
    if (!std::isfinite(jawOpenFactor)) return;
    const float visemePayload[kemzy::HumanoidVoiceLipSyncProcessor::kVisemeCount] = {
        std::max(0.0f, std::min(1.0f, jawOpenFactor)),
        0.0f, 0.0f, 0.0f, 0.0f, 0.0f,
        0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f
    };
    gVoiceProcessor.updateMouthShapesFromAudio(
        visemePayload, kemzy::HumanoidVoiceLipSyncProcessor::kVisemeCount);
}

