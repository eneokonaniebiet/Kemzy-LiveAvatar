#include <algorithm>
#include <array>
#include <cmath>
#include <cstddef>
#include <vector>

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

    // Reserve an empty mesh container. Actual topology/weights must be supplied by
    // a future validated asset loader; this intentionally invents no geometry.
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
