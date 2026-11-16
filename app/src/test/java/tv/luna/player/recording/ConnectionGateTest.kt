package tv.luna.player.recording

import org.junit.Assert.assertEquals
import org.junit.Test

class ConnectionGateTest {
    @Test fun unknownLimitAlwaysGoes() = assertEquals(Gate.GO, gate(null, 5, 0))
    @Test fun freeSlotGoes() = assertEquals(Gate.GO, gate(2, 1, 1))
    @Test fun onlySlotHeldByPlayerAsks() = assertEquals(Gate.ASK, gate(1, 1, 1))
    @Test fun onlySlotHeldByRecordingIsNoFree() = assertEquals(Gate.NO_FREE, gate(1, 1, 0))
    @Test fun twoOfTwoWithPlayerAsks() = assertEquals(Gate.ASK, gate(2, 2, 1))
}
