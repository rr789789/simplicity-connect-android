package com.zg.sensormonitor.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AlarmEvaluatorTest {
    @Test fun raisesAfterDwellAndClearsOnRecovery() {
        val evaluator = AlarmEvaluator()
        val policy = AlarmPolicy(high = 10.0, dwellMs = 3000)
        assertNull(evaluator.evaluate("s1", 11.0, policy, 1000))
        assertNull(evaluator.evaluate("s1", 11.0, policy, 3999))
        assertEquals(AlarmChange.Raised, evaluator.evaluate("s1", 11.0, policy, 4000))
        assertEquals(AlarmChange.Cleared, evaluator.evaluate("s1", 9.0, policy, 5000))
    }
}
