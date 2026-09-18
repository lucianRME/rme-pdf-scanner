package org.synapseworks.pageharbor.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class DoubleBackExitControllerTest {
    @Test
    fun firstBackShowsHintAndSecondBackWithinWindowExits() {
        var now = 1_000L
        val controller = DoubleBackExitController(nowMillis = { now })

        assertEquals(DoubleBackExitController.Result.ShowHint, controller.onBack())
        now += 1_999L
        assertEquals(DoubleBackExitController.Result.Exit, controller.onBack())
    }

    @Test
    fun expiredWindowRequiresAnotherPair() {
        var now = 1_000L
        val controller = DoubleBackExitController(nowMillis = { now })

        assertEquals(DoubleBackExitController.Result.ShowHint, controller.onBack())
        now += 2_001L
        assertEquals(DoubleBackExitController.Result.ShowHint, controller.onBack())
        now += 1L
        assertEquals(DoubleBackExitController.Result.Exit, controller.onBack())
    }

    @Test
    fun resetDisarmsTheExitSequence() {
        val controller = DoubleBackExitController(nowMillis = { 1_000L })

        assertEquals(DoubleBackExitController.Result.ShowHint, controller.onBack())
        controller.reset()
        assertEquals(DoubleBackExitController.Result.ShowHint, controller.onBack())
    }
}
