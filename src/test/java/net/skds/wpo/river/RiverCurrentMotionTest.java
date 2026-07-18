package net.skds.wpo.river;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

final class RiverCurrentMotionTest {

    @Test
    void stationaryEntityAcceleratesTowardCurrentSpeed() {
        assertTrue(RiverCurrentMotion.correction(0.0D, 0.10D, 0.25D, 0.03D) > 0.0D);
    }

    @Test
    void controllerStopsAddingSpeedAfterTarget() {
        assertEquals(0.0D, RiverCurrentMotion.correction(0.12D, 0.10D, 0.25D, 0.03D));
    }

    @Test
    void strongUpstreamMotionStillUsesBoundedCorrection() {
        assertEquals(0.03D, RiverCurrentMotion.correction(-0.30D, 0.10D, 0.25D, 0.03D));
    }
}
