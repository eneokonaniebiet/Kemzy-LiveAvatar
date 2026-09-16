package com.kemzy.liveavatar.engine

import org.junit.Assert.assertTrue
import org.junit.Test

class StateTest {
 @Test fun degradedStateExists(){assertTrue(EngineState.Degraded("AI frame failed") is EngineState.Degraded)}
 @Test fun runningStateExists(){assertTrue(EngineState.Running is EngineState)}
}
