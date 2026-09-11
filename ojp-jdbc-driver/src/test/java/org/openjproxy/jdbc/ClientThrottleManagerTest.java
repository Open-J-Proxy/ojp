package org.openjproxy.jdbc;

import com.openjproxy.grpc.SessionInfo;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link ClientThrottleManager} hardening:
 * - Overload cooldown (burst-coalescing).
 * - Soft floor on reactiveLimit (no collapse to 1).
 * - Autonomous additive recovery on success.
 */
class ClientThrottleManagerTest {

    private static SessionInfo session(int maxAdmission, int observedPeak, int clientCount) {
        return SessionInfo.newBuilder()
                .setConnHash("test")
                .setMaxAdmission(maxAdmission)
                .setObservedPeak(observedPeak)
                .setClientCount(clientCount)
                .build();
    }

    @Test
    void burstOverloadCoalescedByCooldown() {
        // 1s cooldown, floor divisor 4, factor 0.5
        ClientThrottleManager mgr = new ClientThrottleManager(1_000L, 4, 0.5d, 0);
        mgr.updateFromSessionInfo(session(32, 32, 1));
        // proactive ≈ 32*0.9 = 28; reactive starts ≈ 28
        int initial = mgr.getReactiveLimit();
        assertTrue(initial >= 25 && initial <= 32, "initial reactive = " + initial);

        // First overload halves
        mgr.notifyServerOverload();
        int afterOne = mgr.getReactiveLimit();
        assertTrue(afterOne < initial, "first halving should reduce");

        // Further overloads within cooldown are ignored
        for (int i = 0; i < 10; i++) {
            mgr.notifyServerOverload();
        }
        assertEquals(afterOne, mgr.getReactiveLimit(),
                "cooldown should coalesce burst into a single halving");
    }

    @Test
    void floorPreventsCollapseToOne() {
        // floor divisor 4, no cooldown to allow many halvings
        ClientThrottleManager mgr = new ClientThrottleManager(0L, 4, 0.5d, 0);
        mgr.updateFromSessionInfo(session(40, 40, 1));
        // proactive ≈ 40*0.9 = 36; floor = 36/4 = 9

        // Hammer overloads — should never fall below floor
        for (int i = 0; i < 50; i++) {
            mgr.notifyServerOverload();
        }
        int rl = mgr.getReactiveLimit();
        int proactive = mgr.getProactiveLimit();
        int expectedFloor = Math.max(1, proactive / 4);
        assertTrue(rl >= expectedFloor,
                "reactiveLimit (" + rl + ") must stay above floor (" + expectedFloor + ")");
        assertTrue(rl > 1, "reactiveLimit must not collapse to 1");
    }

    @Test
    void autonomousRecoveryFromReleases() {
        // No cooldown, low recoveryThreshold so test runs quickly.
        ClientThrottleManager mgr = new ClientThrottleManager(0L, 4, 0.5d, 5);
        mgr.updateFromSessionInfo(session(40, 40, 1));
        int initial = mgr.getReactiveLimit();
        // Bring reactiveLimit down via one overload event.
        mgr.notifyServerOverload();
        int reduced = mgr.getReactiveLimit();
        assertTrue(reduced < initial);

        // Acquire/release 1000 times; reactiveLimit should grow back, bounded by
        // proactive.
        for (int i = 0; i < 1000; i++) {
            assertTrue(mgr.tryAcquire(ClientThrottleMode.REACTIVE, false));
            mgr.release(ClientThrottleMode.REACTIVE, false);
        }
        int recovered = mgr.getReactiveLimit();
        assertTrue(recovered > reduced,
                "reactiveLimit (" + recovered + ") should have recovered above " + reduced);
        // Should not exceed proactive limit.
        assertTrue(recovered <= mgr.getProactiveLimit(),
                "reactiveLimit (" + recovered + ") must not exceed proactive limit ("
                        + mgr.getProactiveLimit() + ")");
    }

    @Test
    void sessionInfoUpdateDoesNotPushReactiveBelowFloor() {
        ClientThrottleManager mgr = new ClientThrottleManager(0L, 4, 0.5d, 0);
        mgr.updateFromSessionInfo(session(40, 40, 1));
        int proactive = mgr.getProactiveLimit();
        int floor = Math.max(1, proactive / 4);

        // Server reports a very low observedPeak — must not collapse below floor.
        mgr.updateFromSessionInfo(session(40, 1, 1));
        assertTrue(mgr.getReactiveLimit() >= floor,
                "reactiveLimit (" + mgr.getReactiveLimit() + ") must stay >= floor (" + floor + ")");
    }

    @Test
    void emptySessionInfoDoesNotResetReactive() {
        ClientThrottleManager mgr = new ClientThrottleManager(0L, 4, 0.5d, 0);
        mgr.updateFromSessionInfo(session(40, 40, 1));
        mgr.notifyServerOverload();
        int reduced = mgr.getReactiveLimit();
        // SQL response — no throttle data
        mgr.updateFromSessionInfo(session(0, 0, 0));
        assertEquals(reduced, mgr.getReactiveLimit(),
                "empty SessionInfo (maxAdmission=0) must not change reactive limit");
    }

    @Test
    void gentlerDecreaseFactorReducesSlower() {
        ClientThrottleManager halve = new ClientThrottleManager(0L, 8, 0.5d, 0);
        ClientThrottleManager soft = new ClientThrottleManager(0L, 8, 0.75d, 0);
        halve.updateFromSessionInfo(session(100, 100, 1));
        soft.updateFromSessionInfo(session(100, 100, 1));

        halve.notifyServerOverload();
        soft.notifyServerOverload();

        assertTrue(soft.getReactiveLimit() > halve.getReactiveLimit(),
                "factor=0.75 should leave a higher reactiveLimit than factor=0.5");
    }

    @Test
    void defaultDecreaseFactorIsPointSeventyFive() {
        String previous = System.getProperty(ClientThrottleManager.PROP_REACTIVE_DECREASE_FACTOR);
        try {
            System.clearProperty(ClientThrottleManager.PROP_REACTIVE_DECREASE_FACTOR);
            ClientThrottleManager mgr = new ClientThrottleManager();
            mgr.updateFromSessionInfo(session(40, 40, 1)); // proactive/reactive = floor(40 * 0.9) = 36
            mgr.notifyServerOverload();
            assertEquals(27, mgr.getReactiveLimit(), "default factor 0.75 should reduce 36 -> 27");
        } finally {
            if (previous == null) {
                System.clearProperty(ClientThrottleManager.PROP_REACTIVE_DECREASE_FACTOR);
            } else {
                System.setProperty(ClientThrottleManager.PROP_REACTIVE_DECREASE_FACTOR, previous);
            }
        }
    }

    @Test
    void offModeAlwaysAdmits() {
        ClientThrottleManager mgr = new ClientThrottleManager(0L, 4, 0.5d, 0);
        mgr.updateFromSessionInfo(session(1, 1, 1));
        for (int i = 0; i < 1000; i++) {
            assertTrue(mgr.tryAcquire(ClientThrottleMode.OFF, false));
        }
    }

    @Test
    void reactiveModeRejectsOverLimit() {
        ClientThrottleManager mgr = new ClientThrottleManager(0L, 4, 0.5d, 0);
        mgr.updateFromSessionInfo(session(4, 4, 1));
        int rl = mgr.getReactiveLimit();
        for (int i = 0; i < rl; i++) {
            assertTrue(mgr.tryAcquire(ClientThrottleMode.REACTIVE, false));
        }
        assertFalse(mgr.tryAcquire(ClientThrottleMode.REACTIVE, false));
    }

    @Test
    void slowLaneOverloadSuppressedFromReactiveDecrease() {
        // No cooldown so a halving could otherwise fire on every call.
        ClientThrottleManager mgr = new ClientThrottleManager(0L, 4, 0.5d, 0);
        mgr.updateFromSessionInfo(session(40, 40, 1));
        int before = mgr.getReactiveLimit();
        // Slow-lane signals must NOT depress the (predominantly fast) reactive limit —
        // cross-lane contamination fix.
        for (int i = 0; i < 20; i++) {
            mgr.notifyServerOverload(ClientThrottleManager.OverloadLane.SLOW);
        }
        assertEquals(before, mgr.getReactiveLimit(),
                "slow-lane overload must not change the reactive limit");
    }

    @Test
    void queueLaneOverloadSuppressedFromReactiveDecrease() {
        ClientThrottleManager mgr = new ClientThrottleManager(0L, 4, 0.5d, 0);
        mgr.updateFromSessionInfo(session(40, 40, 1));
        int before = mgr.getReactiveLimit();
        // Queue-depth overloads are transient burst signals, not saturation; suppress.
        for (int i = 0; i < 20; i++) {
            mgr.notifyServerOverload(ClientThrottleManager.OverloadLane.QUEUE);
        }
        assertEquals(before, mgr.getReactiveLimit());
    }

    @Test
    void fastLaneOverloadStillHalves() {
        ClientThrottleManager mgr = new ClientThrottleManager(0L, 4, 0.5d, 0);
        mgr.updateFromSessionInfo(session(40, 40, 1));
        int before = mgr.getReactiveLimit();
        mgr.notifyServerOverload(ClientThrottleManager.OverloadLane.FAST);
        assertTrue(mgr.getReactiveLimit() < before,
                "fast-lane overload must still apply AIMD decrease");
    }

    @Test
    void unknownLaneTreatedAsFastForSafety() {
        // Legacy server (no trailer) — must continue to back off, otherwise reactive
        // throttling would silently regress.
        ClientThrottleManager mgr = new ClientThrottleManager(0L, 4, 0.5d, 0);
        mgr.updateFromSessionInfo(session(40, 40, 1));
        int before = mgr.getReactiveLimit();
        mgr.notifyServerOverload(ClientThrottleManager.OverloadLane.UNKNOWN);
        assertTrue(mgr.getReactiveLimit() < before);
    }

    @Test
    void overloadLaneParseHandlesAllValues() {
        assertEquals(ClientThrottleManager.OverloadLane.FAST,
                ClientThrottleManager.OverloadLane.parse("fast"));
        assertEquals(ClientThrottleManager.OverloadLane.SLOW,
                ClientThrottleManager.OverloadLane.parse("SLOW"));
        assertEquals(ClientThrottleManager.OverloadLane.QUEUE,
                ClientThrottleManager.OverloadLane.parse("queue"));
        assertEquals(ClientThrottleManager.OverloadLane.UNKNOWN,
                ClientThrottleManager.OverloadLane.parse("garbage"));
        assertEquals(ClientThrottleManager.OverloadLane.UNKNOWN,
                ClientThrottleManager.OverloadLane.parse(null));
    }
}
