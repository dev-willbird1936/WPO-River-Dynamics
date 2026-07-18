package net.skds.wpo.river;

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

final class FlowConsensusTest {

    @Test
    void signedReferencePreservesWestwardPolarity() {
        FlowConsensus.Axis axis = FlowConsensus.signedReferenceAxis(-3.0D, 0.0D).orElseThrow();

        assertTrue(axis.x() < -0.999D);
        assertTrue(Math.abs(axis.z()) < 1.0E-9D);
    }

    @Test
    void opposingSignedReferencesRemainUnresolved() {
        assertTrue(FlowConsensus.signedReferenceAxis(0.0D, 0.0D).isEmpty());
    }

    @Test
    void opposingSeedsReinforceChannelAxisInsteadOfCancelling() {
        FlowConsensus.Summary summary = new FlowConsensus.Summary();
        for (int x = -40; x <= 40; x += 4) {
            for (int z = -8; z <= 8; z += 4) {
                double rawX = Math.floorMod(x / 4, 2) == 0 ? 1.0D : -1.0D;
                summary.add(x, z, 64, rawX, 0.0D, 1.0D);
            }
        }
        summary.add(0, -8, 64, 0.0D, 1.0D, 1.0D);
        summary.add(0, 8, 64, 0.0D, -1.0D, 1.0D);

        FlowConsensus.Axis axis = summary.resolve().orElseThrow();

        assertTrue(axis.x() > 0.95D, "flat tie should use one stable eastward sign");
        assertTrue(Math.abs(axis.z()) < 0.1D, "cross-channel seed noise must not rotate flow");
    }

    @Test
    void bedSlopeChoosesSameDownstreamSignAcrossOpposingSeeds() {
        FlowConsensus.Summary summary = new FlowConsensus.Summary();
        for (int x = -40; x <= 40; x += 4) {
            for (int z = -8; z <= 8; z += 4) {
                int bedHeight = 70 - ((x + 40) / 16);
                double rawX = Math.floorMod(x / 4, 2) == 0 ? -1.0D : 1.0D;
                summary.add(x, z, bedHeight, rawX, 0.0D, 1.0D);
            }
        }

        FlowConsensus.Axis axis = summary.resolve().orElseThrow();

        assertTrue(axis.x() > 0.95D, "axis must point toward lower streambed");
        assertTrue(Math.abs(axis.z()) < 0.1D);
    }

    @Test
    void reversedBedSlopeReversesWholeReach() {
        FlowConsensus.Summary summary = new FlowConsensus.Summary();
        for (int x = -40; x <= 40; x += 4) {
            for (int z = -8; z <= 8; z += 4) {
                int bedHeight = 64 + ((x + 40) / 16);
                summary.add(x, z, bedHeight, 1.0D, 0.0D, 1.0D);
            }
        }

        FlowConsensus.Axis axis = summary.resolve().orElseThrow();

        assertTrue(axis.x() < -0.95D, "axis must reverse toward lower streambed");
        assertTrue(Math.abs(axis.z()) < 0.1D);
    }

    @Test
    void neighborhoodConsensusFollowsEachBendInsteadOfWholeReachAxis() {
        FlowConsensus.Neighborhood east = FlowConsensus.neighborhood(0.0D, 0.0D, 10.0D);
        FlowConsensus.Neighborhood south = FlowConsensus.neighborhood(20.0D, 20.0D, 10.0D);
        for (int i = -8; i <= 8; i += 4) {
            east.add(i, 0, 64, 1.0D, 0.0D, 1.0D);
            east.add(i, 4, 64, 1.0D, 0.0D, 1.0D);
            south.add(20, i + 20, 64, 0.0D, 1.0D, 1.0D);
            south.add(24, i + 20, 64, 0.0D, 1.0D, 1.0D);
        }

        FlowConsensus.Axis eastAxis = east.resolve(6).orElseThrow();
        FlowConsensus.Axis southAxis = south.resolve(6).orElseThrow();

        assertTrue(eastAxis.x() > 0.95D, "local east segment must point east");
        assertTrue(Math.abs(eastAxis.z()) < 0.1D);
        assertTrue(southAxis.z() > 0.95D, "local south segment must point south");
        assertTrue(Math.abs(southAxis.x()) < 0.1D);
    }

    @Test
    void localBedGradientSuppliesDiagonalTangent() {
        FlowConsensus.Summary summary = new FlowConsensus.Summary();
        for (int x = -8; x <= 8; x += 4) {
            for (int z = -8; z <= 8; z += 4) {
                double bed = 80.0D - ((x + z) * 0.25D);
                summary.add(x, z, bed, 1.0D, 0.0D, 1.0D);
            }
        }

        FlowConsensus.Axis axis = summary.resolve().orElseThrow();

        assertTrue(axis.x() > 0.65D, "downhill tangent must include east component");
        assertTrue(axis.z() > 0.65D, "downhill tangent must include south component");
    }
}
