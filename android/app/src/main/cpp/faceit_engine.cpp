#include <algorithm>
#include <cstddef>
#include <vector>

// Foundation only: a CPU-side container for the grid topology that will later
// be uploaded to OpenGL ES buffers. This file deliberately does not read or
// retain camera pixels; driving input will be numeric landmarks only.
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
    bool initializeTestingGrid(int columns, int rows) {
        if (columns < 2 || rows < 2 || columns > 256 || rows > 256) {
            return false;
        }

        const auto count = static_cast<std::size_t>(columns) *
                           static_cast<std::size_t>(rows);
        portraitMesh.clear();
        portraitMesh.reserve(count);

        for (int row = 0; row < rows; ++row) {
            const float fy = static_cast<float>(row) /
                             static_cast<float>(rows - 1);
            for (int column = 0; column < columns; ++column) {
                const float fx = static_cast<float>(column) /
                                 static_cast<float>(columns - 1);
                portraitMesh.push_back({
                    -1.0F + 2.0F * fx,
                    1.0F - 2.0F * fy,
                    0.0F,
                    fx,
                    1.0F - fy
                });
            }
        }
        return portraitMesh.size() == count;
    }

private:
    std::vector<MeshVertex> portraitMesh;
};

}  // namespace kemzy
