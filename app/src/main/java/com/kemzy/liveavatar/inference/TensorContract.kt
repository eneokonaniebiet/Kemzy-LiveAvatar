package com.kemzy.liveavatar.inference

class TensorContractException(message: String) : IllegalArgumentException(message)

object TensorContract {
    fun requireRank(name: String, shape: LongArray, expectedRank: Int) {
        if (shape.size != expectedRank) throw TensorContractException("$name expected rank $expectedRank but got ${shape.size}")
    }

    fun requireFeature3d(shape: LongArray) {
        requireRank("feature_3d", shape, 5)
        if (shape.any { it <= 0 }) throw TensorContractException("feature_3d dimensions must be positive")
    }

    fun elementCount(shape: LongArray): Long = shape.fold(1L) { acc, d -> Math.multiplyExact(acc, d) }
}
