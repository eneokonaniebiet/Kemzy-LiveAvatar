package com.kemzy.liveavatar.inference

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class TensorContractTest {
 @Test fun feature3dMustBeRankFive(){assertThrows(TensorContractException::class.java){TensorContract.requireFeature3d(longArrayOf(1))};TensorContract.requireFeature3d(longArrayOf(1,32,16,64,64))}
 @Test fun elementCountMatchesShape(){assertEquals(2097152L,TensorContract.elementCount(longArrayOf(1,32,16,64,64)))}
 @Test fun zeroDimensionRejected(){assertThrows(TensorContractException::class.java){TensorContract.requireFeature3d(longArrayOf(1,32,0,64,64))}}
}
