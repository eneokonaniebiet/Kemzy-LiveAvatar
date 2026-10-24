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

}  // namespace kemzy
