package io.github.hypercar.carplus

import org.junit.Assert.*
import org.junit.Test

class CarSourceChoicesTest {
    @Test fun fourUniqueKeysMatchExactlyOneSelection() {
        val keys = listOf("car_ui", "carwith", "system_carlife", "carplus")
        keys.forEach { current ->
            val choices = carSourceChoices(current, true, true, true, false)
            assertEquals(keys, choices.map { it.key })
            assertEquals(4, choices.map { it.key }.toSet().size)
            assertEquals(listOf(current), choices.filter { it.selected }.map { it.key })
        }
    }

    @Test fun unknownOrMissingSourceDoesNotSelectAnotherMode() {
        listOf(null, "unknown").forEach { current ->
            assertTrue(carSourceChoices(current, true, true, true, false).none { it.selected })
        }
    }

    @Test fun availabilityUsesEachSourcesOwnRequirement() {
        for (shizuku in listOf(false, true)) {
            for (carWith in listOf(false, true)) {
                for (carPlus in listOf(false, true)) {
                    val choices = carSourceChoices("carplus", shizuku, carWith, carPlus, false)
                        .associateBy { it.key }
                    assertEquals(shizuku, choices.getValue("car_ui").enabled)
                    assertEquals(carWith, choices.getValue("carwith").enabled)
                    assertTrue(choices.getValue("system_carlife").enabled)
                    assertEquals(carPlus, choices.getValue("carplus").enabled)
                    assertTrue(choices.getValue("carplus").selected)
                }
            }
        }
    }

    @Test fun connectedDisablesAllChoicesWithoutHidingSelection() {
        listOf("car_ui", "carwith", "system_carlife", "carplus").forEach { current ->
            val choices = carSourceChoices(current, true, true, true, true)
            assertTrue(choices.none { it.enabled })
            assertEquals(listOf(current), choices.filter { it.selected }.map { it.key })
        }
    }
}
