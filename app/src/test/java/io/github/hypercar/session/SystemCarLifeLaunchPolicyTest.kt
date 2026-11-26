package io.github.hypercar.session

import org.junit.Assert.*
import org.junit.Test

class SystemCarLifeLaunchPolicyTest {
    private fun SystemCarLifeLaunchPolicy.claim(
        attempt: Long = 1, now: Long = 0, source: String = "system_carlife",
        pkg: String? = SystemCarLifeLaunchPolicy.OPPO_PACKAGE, shizuku: Boolean = true,
        startup: Boolean = true, connected: Boolean = false,
    ) = claim(attempt, source, pkg, shizuku, startup, connected, now)

    @Test fun onlyOppoSystemSourceWithAuthorizedShellAndRefusedStartupMayLaunch() {
        val policy = SystemCarLifeLaunchPolicy()
        policy.begin(1, false, 0)
        for (source in listOf("carwith", "self", "")) assertNotNull(policy.claim(source = source))
        for (pkg in listOf(null, "com.baidu.carlife.oppo", "com.samsung.android.carlink", "com.baidu.carlife.honor")) {
            assertNotNull(policy.claim(pkg = pkg))
        }
        assertNotNull(policy.claim(shizuku = false))
        assertNotNull(policy.claim(startup = false))
        assertNotNull(policy.claim(connected = true))
        assertNull(policy.claim())
        assertTrue(policy.canStart(1))
        assertNotNull(policy.claim())
    }

    @Test fun successConsumesBudgetAcrossAutomaticReconnectsButManualCanRearm() {
        val policy = SystemCarLifeLaunchPolicy()
        policy.begin(1, false, 0)
        assertNull(policy.claim())
        policy.complete(1, true, 1)
        assertFalse(policy.canStart(1))
        policy.begin(2, false, 20_000)
        assertNotNull(policy.claim(attempt = 2, now = 20_000))
        policy.begin(3, true, 20_001)
        assertNull(policy.claim(attempt = 3, now = 20_001))
    }

    @Test fun binderFailureUsesBackoffAndRetriesRemainBoundedAcrossRounds() {
        val policy = SystemCarLifeLaunchPolicy()
        policy.begin(1, false, 0)
        assertNull(policy.claim())
        policy.complete(1, false, 100)
        policy.begin(2, false, 200)
        assertNotNull(policy.claim(attempt = 2, now = 15_099))
        assertNull(policy.claim(attempt = 2, now = 15_100))
        policy.complete(2, false, 15_200)
        for (attempt in 3L..20L) {
            policy.begin(attempt, false, attempt * 20_000)
            assertNotNull(policy.claim(attempt = attempt, now = attempt * 20_000))
        }
    }

    @Test fun stopInvalidatesOldShellButAcceptedPostPauseSessionCanRearm() {
        val policy = SystemCarLifeLaunchPolicy()
        policy.begin(1, false, 0)
        assertNull(policy.claim())
        policy.stop(1)
        assertFalse(policy.canStart(1))
        policy.complete(1, true, 1)
        policy.begin(2, false, SystemCarLifeLaunchPolicy.NEW_TRIP_GAP_MILLIS)
        assertNull(policy.claim(attempt = 2, now = SystemCarLifeLaunchPolicy.NEW_TRIP_GAP_MILLIS))
        policy.complete(1, true, SystemCarLifeLaunchPolicy.NEW_TRIP_GAP_MILLIS)
        assertTrue(policy.canStart(2))
        policy.begin(3, true, SystemCarLifeLaunchPolicy.NEW_TRIP_GAP_MILLIS + 1)
        assertNull(policy.claim(attempt = 3, now = SystemCarLifeLaunchPolicy.NEW_TRIP_GAP_MILLIS + 1))
        policy.stop(1)
        assertTrue(policy.canStart(3))
    }

    @Test fun newTripAfterIdleGapRearmsButRepeatedWakeDoesNotExtendTheBudget() {
        val policy = SystemCarLifeLaunchPolicy()
        policy.begin(1, false, 0)
        assertNull(policy.claim())
        policy.complete(1, true, 1)
        policy.begin(2, false, 100)
        assertNotNull(policy.claim(attempt = 2, now = 100))
        policy.begin(3, false, 100 + SystemCarLifeLaunchPolicy.NEW_TRIP_GAP_MILLIS)
        assertNull(policy.claim(attempt = 3, now = 100 + SystemCarLifeLaunchPolicy.NEW_TRIP_GAP_MILLIS))
    }

    @Test fun staleCommandCannotStartOrCompleteIntoTheSuccessor() {
        val policy = SystemCarLifeLaunchPolicy()
        policy.begin(1, false, 0)
        assertNull(policy.claim())
        policy.begin(2, false, 20_000)
        assertFalse(policy.canStart(1))
        assertNotNull(policy.claim(attempt = 1, now = 20_000))
        policy.complete(1, true, 20_000)
        assertNull(policy.claim(attempt = 2, now = 20_000))
        policy.complete(2, false, 20_001)
        policy.begin(3, false, 40_000)
        assertNotNull(policy.claim(attempt = 3, now = 40_000))
    }
}
