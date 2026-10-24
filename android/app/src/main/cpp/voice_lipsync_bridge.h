#pragma once

#include <cstddef>

// Native bridge for the process-wide voice processor. The concrete mesh type
// remains private to humanoid_rig.cpp; callers only provide its stable storage.
extern "C" void kemzyExecuteLiveMouthDeformation(
    void* mutableVertices,
    std::size_t totalVertices);
