#include <android/log.h>
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
