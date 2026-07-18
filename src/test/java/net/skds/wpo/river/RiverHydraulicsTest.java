package net.skds.wpo.river;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

final class RiverHydraulicsTest {

    @Test
    void widerDeeperChannelCarriesAFasterGentleCurrent() {
        double stream = RiverHydraulics.speed(3.0D, 1.0D, 0.0005D);
        double river = RiverHydraulics.speed(18.0D, 5.0D, 0.0005D);

        assertTrue(river > stream);
    }

    @Test
    void slopeRaisesSpeedForTheSameChannel() {
        double gentle = RiverHydraulics.speed(8.0D, 2.0D, 0.0005D);
        double rapid = RiverHydraulics.speed(8.0D, 2.0D, 0.03D);

        assertTrue(rapid > gentle * 2.0D);
    }

    @Test
    void gameplaySpeedStaysInsideSafeBounds() {
        assertEquals(RiverHydraulics.MIN_SPEED, RiverHydraulics.speed(1.0D, 0.5D, 0.0D));
        assertEquals(RiverHydraulics.MAX_SPEED, RiverHydraulics.speed(64.0D, 16.0D, 0.25D));
    }

    @Test
    void strengthMappingRoundTripsPhysicalSpeed() {
        double speed = RiverHydraulics.speed(10.0D, 3.0D, 0.004D);
        double strength = RiverHydraulics.biasStrength(speed, 0.12D, 1.25D);

        assertEquals(speed, RiverHydraulics.speedFromBiasStrength(strength, 0.12D, 1.25D), 1.0E-9D);
    }
}
