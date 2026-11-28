package io.github.hypercar.carplus

import org.junit.Assert.*
import org.junit.Test

class CarPlusLaunchPolicyTest {
    @Test fun earlyAndWrongAttemptCannotLaunch() {
        val policy = CarPlusLaunchPolicy()
        assertNull(policy.connected(1, 10))
        policy.begin(1)
        assertNull(policy.connected(2, 10))
        assertNull(policy.connected(1, 0))
        val ticket = policy.connected(1, 10)!!
        assertFalse(policy.claim(ticket, false, true))
        assertTrue(policy.claim(ticket, true, true))
    }

    @Test fun duplicateConnectedCanOnlyLaunchOnce() {
        val policy = CarPlusLaunchPolicy()
        policy.begin(1)
        val first = policy.connected(1, 10)!!
        val duplicate = policy.connected(1, 10)!!
        assertTrue(policy.claim(first, true, true))
        assertFalse(policy.claim(duplicate, true, true))
    }

    @Test fun newerEngineSupersedesStaleTicket() {
        val policy = CarPlusLaunchPolicy()
        policy.begin(1)
        val old = policy.connected(1, 10)!!
        // Native engine replacement without a terminal callback must still allow the next engine.
        val next = policy.connected(1, 11)!!
        assertFalse(policy.current(old))
        assertTrue(policy.current(next))
        assertTrue(policy.claim(next, true, true))
    }

    @Test fun staleEngineIsRejectedByWatermark() {
        val policy = CarPlusLaunchPolicy()
        policy.begin(1)
        assertNotNull(policy.connected(1, 11))
        assertNull(policy.connected(1, 10))
        assertNull(policy.connected(1, 11 - 2))
    }

    @Test fun dispatchAtomicallyConsumesBudgetAndSubmits() {
        val policy = CarPlusLaunchPolicy()
        policy.begin(1)
        val ticket = policy.connected(1, 10)!!
        var submitted = 0
        assertEquals(42, policy.dispatch(ticket, { true }, { true }) { submitted++; 42 })
        assertEquals(1, submitted)
        // Budget is consumed inside dispatch: a second call must not submit again.
        assertNull(policy.dispatch(ticket, { true }, { true }) { submitted++; 43 })
        assertEquals(1, submitted)
    }

    @Test fun dispatchDoesNotSubmitWhenGateFails() {
        val policy = CarPlusLaunchPolicy()
        policy.begin(1)
        val ticket = policy.connected(1, 10)!!
        var submitted = 0
        assertNull(policy.dispatch(ticket, { false }, { true }) { submitted++; 1 })
        assertNull(policy.dispatch(ticket, { true }, { false }) { submitted++; 1 })
        assertEquals(0, submitted)
    }

    @Test fun stopInvalidatesQueuedWorkAndIgnoresOldStop() {
        val policy = CarPlusLaunchPolicy()
        policy.begin(1)
        val old = policy.connected(1, 10)!!
        policy.stop(1)
        assertFalse(policy.current(old))
        assertFalse(policy.claim(old, true, true))
        assertNull(policy.connected(1, 10))
        policy.begin(2)
        val current = policy.connected(2, 20)!!
        assertFalse(policy.stop(1))
        assertTrue(policy.current(current))
    }

    @Test fun disconnectRetiresOldEngineWithoutResettingAttemptBudget() {
        val policy = CarPlusLaunchPolicy()
        policy.begin(1)
        val old = policy.connected(1, 10)!!
        assertTrue(policy.claim(old, true, true))
        assertFalse(policy.disconnected(2, 10))
        assertFalse(policy.disconnected(1, 11))
        assertTrue(policy.disconnected(1, 10))
        assertFalse(policy.current(old))
        assertNull(policy.connected(1, 10))
        val reconnect = policy.connected(1, 11)!!
        assertFalse(policy.claim(reconnect, true, true))
        assertFalse(policy.disconnected(1, 10))
        assertTrue(policy.current(reconnect))
    }

    @Test fun preSubmitCancellationPreservesBudgetForNextEngine() {
        val policy = CarPlusLaunchPolicy()
        policy.begin(1)
        val old = policy.connected(1, 10)!!
        assertFalse(policy.claim(old, false, true))
        assertTrue(policy.disconnected(1, 10))
        assertFalse(policy.claim(old, true, true))
        assertTrue(policy.claim(policy.connected(1, 11)!!, true, true))
    }

    @Test fun newAttemptInvalidatesOldEngineAndTicket() {
        val policy = CarPlusLaunchPolicy()
        policy.begin(1)
        val old = policy.connected(1, 10)!!
        policy.begin(2)
        assertFalse(policy.current(old))
        assertNull(policy.connected(1, 10))
        assertTrue(policy.claim(policy.connected(2, 20)!!, true, true))
    }

    @Test fun existingSourceIsNotRestarted() {
        val policy = CarPlusLaunchPolicy()
        policy.begin(1)
        val ticket = policy.connected(1, 10)!!
        assertFalse(policy.claim(ticket, true, false))
        assertFalse(policy.claim(ticket, false, false))
    }

    @Test fun connectionEntryRanksAbovePreferredAndLauncher() {
        val connect = CarPlusSource.entryScore("com.oplus.carplus", "com.oplus.carplus.carlife.CarLifeConnectActivity")
        val preferred = CarPlusSource.entryScore("com.oplus.ocar", "com.oplus.ocar.CarlifeActivity")
        val genericCarLife = CarPlusSource.entryScore("com.oplus.carplus", ".CarLifeHomeActivity")
        val launcher = CarPlusSource.entryScore("com.coloros.carplus", ".MainActivity", launcher = true)
        assertTrue(connect > preferred)
        assertTrue(preferred > genericCarLife)
        assertTrue(genericCarLife > launcher)
        assertTrue(launcher > 0)
        assertEquals(0, CarPlusSource.entryScore("com.oplus.carplus", ".CarLifeConnectSettingsActivity", true))
        assertEquals(0, CarPlusSource.entryScore("com.oplus.carplus", ".CarLifeConnectPermissionsActivity", true))
        assertEquals(0, CarPlusSource.entryScore("com.oplus.carplus", ".UnrelatedConnectActivity"))
        assertEquals(0, CarPlusSource.entryScore("evil.pkg", ".CarLifeConnectActivity", true))
        assertEquals(0, CarPlusSource.entryScore("com.oplus.carplus", ".CarLifeConnectActivity;reboot", true))
    }

    @Test fun mediaEvidenceTracksOnlyCurrentEngineVideo() {
        val evidence = CarPlusMediaEvidence()
        assertTrue(evidence.awaiting(10))
        evidence.observe(10, 1)
        assertFalse(evidence.awaiting(10))
        // A newer engine starts without its own frame yet; an old engine's frames must not count.
        assertTrue(evidence.awaiting(11))
        evidence.observe(10, 2)
        assertTrue(evidence.awaiting(11))
        evidence.observe(11, 3)
        assertFalse(evidence.awaiting(11))
    }

    @Test fun componentsRequireKnownPackageAndSafeActivity() {
        assertTrue(CarPlusSource.validComponent(CarPlusSource.COMPONENT))
        assertTrue(CarPlusSource.validComponent("com.oplus.carplus/com.oplus.carplus.MainActivity"))
        assertTrue(CarPlusSource.validComponent("com.coloros.carplus/.MainActivity"))
        for (value in listOf("evil.pkg/.Main", "com.oplus.ocar/.Main;reboot", "com.oplus.ocar/.Main extra",
            "com.oplus.ocar/other.pkg.Main", "com.oplus.ocar/$(id)", "com.oplus.ocar/.Main\n", "com.oplus.ocar/.Main/extra")) {
            assertFalse(value, CarPlusSource.validComponent(value))
        }
    }
}
