package net.skds.wpo.river;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

final class RiverPotentialSolverTest {

    @Test
    void wideStraightReachHasOneDirectionAcrossItsWidth() {
        Map<Long, RiverPotentialSolver.Cell> cells = new HashMap<>();
        for (int x = 0; x <= 80; x++) {
            for (int z = -8; z <= 8; z++) {
                put(cells, x, z, 64, 90 - x / 10);
            }
        }

        RiverPotentialSolver.Result result = RiverPotentialSolver.solve(cells.values());

        assertEquals(RiverPotentialSolver.State.FLOWING, result.state());
        for (int x = 12; x <= 68; x += 8) {
            for (int z = -6; z <= 6; z += 3) {
                int probeX = x;
                int probeZ = z;
                RiverPotentialSolver.Flow flow = result.flowAt(x, z)
                        .orElseThrow(() -> new AssertionError("missing flow at " + probeX + "," + probeZ));
                assertTrue(flow.x() > 0.75D, "every cross-section must point east");
                assertTrue(Math.abs(flow.z()) < 0.66D);
            }
        }
        assertStrictlyDownstream(result);
    }

    @Test
    void tightUTurnRotatesWithoutReversingEitherLeg() {
        Map<Long, RiverPotentialSolver.Cell> cells = new HashMap<>();
        int station = 0;
        for (int x = 0; x <= 40; x++, station++) {
            stroke(cells, x, 0, 2, station);
        }
        for (int z = 1; z <= 20; z++, station++) {
            stroke(cells, 40, z, 2, station);
        }
        for (int x = 39; x >= 0; x--, station++) {
            stroke(cells, x, 20, 2, station);
        }

        RiverPotentialSolver.Result result = RiverPotentialSolver.solve(cells.values());

        assertEquals(RiverPotentialSolver.State.FLOWING, result.state());
        RiverPotentialSolver.Flow upper = result.flowAt(15, 0).orElseThrow();
        RiverPotentialSolver.Flow turn = result.flowAt(40, 10).orElseThrow();
        RiverPotentialSolver.Flow lower = result.flowAt(25, 20).orElseThrow();
        assertTrue(upper.x() > 0.65D, "upper leg must run east");
        assertTrue(turn.z() > 0.65D, "turn must rotate south");
        assertTrue(lower.x() < -0.65D, "lower leg must run west");
        assertStrictlyDownstream(result);
    }

    @Test
    void roundPondIsIdle() {
        Map<Long, RiverPotentialSolver.Cell> cells = new HashMap<>();
        int radius = 16;
        for (int z = -radius; z <= radius; z++) {
            for (int x = -radius; x <= radius; x++) {
                if ((x * x) + (z * z) <= radius * radius) {
                    put(cells, x, z, 64, 60);
                }
            }
        }

        RiverPotentialSolver.Result result = RiverPotentialSolver.solve(cells.values());

        assertEquals(RiverPotentialSolver.State.IDLE, result.state());
        assertTrue(result.flows().isEmpty());
    }

    @Test
    void confluenceBranchesShareOneOutlet() {
        Map<Long, RiverPotentialSolver.Cell> cells = new HashMap<>();
        int station = 0;
        for (int i = 0; i <= 24; i++, station++) {
            stroke(cells, i, i, 2, station);
            stroke(cells, 48 - i, i, 2, station);
        }
        for (int z = 25; z <= 72; z++, station++) {
            stroke(cells, 24, z, 3, station);
        }

        RiverPotentialSolver.Result result = RiverPotentialSolver.solve(cells.values());

        assertEquals(RiverPotentialSolver.State.FLOWING, result.state());
        RiverPotentialSolver.Flow left = result.flowAt(10, 10).orElseThrow();
        RiverPotentialSolver.Flow right = result.flowAt(38, 10).orElseThrow();
        RiverPotentialSolver.Flow trunk = result.flowAt(24, 50).orElseThrow();
        assertTrue(left.x() > 0.35D && left.z() > 0.35D);
        assertTrue(right.x() < -0.35D && right.z() > 0.35D);
        assertTrue(trunk.z() > 0.75D);
        assertStrictlyDownstream(result);
    }

    @Test
    void islandArmsBothTravelTowardSameOutlet() {
        Map<Long, RiverPotentialSolver.Cell> cells = new HashMap<>();
        for (int x = 0; x <= 90; x++) {
            for (int z = -9; z <= 9; z++) {
                boolean island = x >= 34 && x <= 56 && Math.abs(z) <= 3;
                if (!island) {
                    put(cells, x, z, 64, 95 - x / 10);
                }
            }
        }

        RiverPotentialSolver.Result result = RiverPotentialSolver.solve(cells.values());

        assertEquals(RiverPotentialSolver.State.FLOWING, result.state());
        RiverPotentialSolver.Flow upper = result.flowAt(45, -6).orElseThrow();
        RiverPotentialSolver.Flow lower = result.flowAt(45, 6).orElseThrow();
        assertTrue(upper.x() > 0.55D, "upper island arm must remain downstream");
        assertTrue(lower.x() > 0.55D, "lower island arm must remain downstream");
        assertStrictlyDownstream(result);
    }

    @Test
    void flatSidePondDoesNotBecomeATributary() {
        Map<Long, RiverPotentialSolver.Cell> cells = new HashMap<>();
        for (int x = 0; x <= 100; x++) {
            for (int z = -3; z <= 3; z++) {
                put(cells, x, z, 64, 100 - x / 10);
            }
        }
        for (int z = 4; z <= 12; z++) {
            put(cells, 50, z, 64, 95);
        }
        for (int z = 10; z <= 30; z++) {
            for (int x = 40; x <= 60; x++) {
                int dx = x - 50;
                int dz = z - 20;
                if ((dx * dx) + (dz * dz) <= 100) {
                    put(cells, x, z, 64, 95);
                }
            }
        }

        RiverPotentialSolver.Result result = RiverPotentialSolver.solve(cells.values());

        assertEquals(RiverPotentialSolver.State.FLOWING, result.state());
        assertTrue(result.flowAt(50, 20).isEmpty(), "closed flat pond interior must stay idle");
        assertTrue(result.flowAt(25, 0).orElseThrow().x() > 0.7D);
        assertTrue(result.flowAt(75, 0).orElseThrow().x() > 0.7D);
        assertStrictlyDownstream(result);
    }

    @Test
    void sCurveRotatesTwiceInOppositeSenseWithoutReversing() {
        Map<Long, RiverPotentialSolver.Cell> cells = new HashMap<>();
        int station = 0;
        for (int x = 0; x <= 20; x++, station++) {
            stroke(cells, x, 0, 2, station);
        }
        for (int z = 1; z <= 20; z++, station++) {
            stroke(cells, 20, z, 2, station);
        }
        for (int x = 21; x <= 40; x++, station++) {
            stroke(cells, x, 20, 2, station);
        }

        RiverPotentialSolver.Result result = RiverPotentialSolver.solve(cells.values());

        assertEquals(RiverPotentialSolver.State.FLOWING, result.state());
        RiverPotentialSolver.Flow upper = result.flowAt(10, 0).orElseThrow();
        RiverPotentialSolver.Flow turn = result.flowAt(20, 10).orElseThrow();
        RiverPotentialSolver.Flow lower = result.flowAt(30, 20).orElseThrow();
        assertTrue(upper.x() > 0.65D, "upper leg must run east");
        assertTrue(turn.z() > 0.65D, "the first bend must turn south");
        assertTrue(lower.x() > 0.65D, "the second bend must resume east - an S, not a U-turn");
        assertStrictlyDownstream(result);
    }

    @Test
    void diagonalSkeletonEdgeRequiresOrthogonalBridge() {
        Set<Long> skeleton = Set.of(RiverPotentialSolver.key(0, 0), RiverPotentialSolver.key(1, 1));

        Set<Long> waterCellsNoBridge = Set.of(RiverPotentialSolver.key(0, 0), RiverPotentialSolver.key(1, 1));
        List<Long> unbridged = RiverPotentialSolver.skeletonNeighbors(
                skeleton, waterCellsNoBridge, RiverPotentialSolver.key(0, 0), true);
        assertTrue(unbridged.isEmpty(),
                "a diagonal skeleton edge with no orthogonal water on either flank must not count as connected");

        Set<Long> waterCellsWithBridge = Set.of(
                RiverPotentialSolver.key(0, 0), RiverPotentialSolver.key(1, 1), RiverPotentialSolver.key(1, 0));
        List<Long> bridged = RiverPotentialSolver.skeletonNeighbors(
                skeleton, waterCellsWithBridge, RiverPotentialSolver.key(0, 0), true);
        assertEquals(List.of(RiverPotentialSolver.key(1, 1)), bridged,
                "an orthogonal water cell on either flank must bridge the diagonal edge");

        List<Long> permissive = RiverPotentialSolver.skeletonNeighbors(
                skeleton, waterCellsNoBridge, RiverPotentialSolver.key(0, 0), false);
        assertEquals(List.of(RiverPotentialSolver.key(1, 1)), permissive,
                "without requiring a bridge, the diagonal edge is still permitted");
    }

    @Test
    void diagonalOnlyLinkFallsBackToUnbridgedInsteadOfBeingLost() {
        Map<Long, RiverPotentialSolver.Cell> cells = new HashMap<>();
        for (int x = 0; x <= 20; x++) {
            for (int z = 0; z <= 5; z++) {
                put(cells, x, z, 64, 90 - x / 5);
            }
        }
        for (int x = 21; x <= 41; x++) {
            for (int z = 6; z <= 11; z++) {
                put(cells, x, z, 64, 85 - (x - 21) / 5);
            }
        }
        // The only connection between the two blocks is this one diagonal corner touch, with
        // both flanking orthogonal cells (20,6) and (21,5) left dry - no real cardinal path
        // exists. Bridged analysis alone would fragment this into two unreachable pieces.
        RiverPotentialSolver.Result result = RiverPotentialSolver.solve(cells.values());

        assertEquals(RiverPotentialSolver.State.FLOWING, result.state());
        assertStrictlyDownstream(result);
    }

    @Test
    void mergedRegionKeepsTheMajorityPriorOrientation() {
        RiverPotentialSolver.Orientation orientationA = new RiverPotentialSolver.Orientation(1L, 2L);
        RiverPotentialSolver.Orientation orientationB = new RiverPotentialSolver.Orientation(3L, 4L);

        Map<Long, Long> priorComponentByColumn = new HashMap<>();
        // Component A previously owned five columns of the now-merged region...
        for (long column = 100L; column < 105L; column++) {
            priorComponentByColumn.put(column, 10L);
        }
        // ...component B only owned two.
        for (long column = 200L; column < 202L; column++) {
            priorComponentByColumn.put(column, 20L);
        }
        Map<Long, RiverPotentialSolver.Orientation> priorOrientations = Map.of(10L, orientationA, 20L, orientationB);

        RiverPotentialSolver.Orientation preserved = RiverPotentialSolver.previousOrientationFor(
                priorComponentByColumn.keySet(), priorComponentByColumn, priorOrientations);

        assertEquals(orientationA, preserved, "the merge must keep whichever prior component covers more of the merged region");
    }

    @Test
    void unchangedRegionKeepsItsOwnPriorOrientation() {
        RiverPotentialSolver.Orientation orientation = new RiverPotentialSolver.Orientation(5L, 6L);
        Map<Long, Long> priorComponentByColumn = Map.of(100L, 10L, 101L, 10L, 102L, 10L);
        Map<Long, RiverPotentialSolver.Orientation> priorOrientations = Map.of(10L, orientation);

        RiverPotentialSolver.Orientation preserved = RiverPotentialSolver.previousOrientationFor(
                priorComponentByColumn.keySet(), priorComponentByColumn, priorOrientations);

        assertEquals(orientation, preserved);
    }

    @Test
    void brandNewRegionHasNoPriorOrientationToPreserve() {
        Set<Long> region = Set.of(900L, 901L, 902L);

        RiverPotentialSolver.Orientation preserved =
                RiverPotentialSolver.previousOrientationFor(region, Map.of(), Map.of());

        assertNull(preserved, "a region with no prior overlap must not invent an anchor");
    }

    @Test
    void unequalIslandArmsBothTravelTowardSameOutlet() {
        Map<Long, RiverPotentialSolver.Cell> cells = new HashMap<>();
        for (int x = 0; x <= 90; x++) {
            for (int z = -9; z <= 9; z++) {
                boolean island = x >= 34 && x <= 56 && z >= -6 && z <= 2;
                if (!island) {
                    put(cells, x, z, 64, 95 - x / 10);
                }
            }
        }

        RiverPotentialSolver.Result result = RiverPotentialSolver.solve(cells.values());

        assertEquals(RiverPotentialSolver.State.FLOWING, result.state());
        RiverPotentialSolver.Flow narrowArm = result.flowAt(45, -8).orElseThrow();
        RiverPotentialSolver.Flow wideArm = result.flowAt(45, 6).orElseThrow();
        assertTrue(narrowArm.x() > 0.5D, "narrow island arm must remain downstream");
        assertTrue(wideArm.x() > 0.5D, "wide island arm must remain downstream");
        assertStrictlyDownstream(result);
    }

    @Test
    void perpendicularTConfluenceJoinsMainTrunkCleanly() {
        Map<Long, RiverPotentialSolver.Cell> cells = new HashMap<>();
        int station = 0;
        for (int x = 0; x <= 60; x++, station++) {
            stroke(cells, x, 20, 3, station);
        }
        for (int z = 0; z <= 19; z++, station++) {
            stroke(cells, 30, z, 2, station);
        }

        RiverPotentialSolver.Result result = RiverPotentialSolver.solve(cells.values());

        assertEquals(RiverPotentialSolver.State.FLOWING, result.state());
        RiverPotentialSolver.Flow tributary = result.flowAt(30, 10).orElseThrow();
        RiverPotentialSolver.Flow beforeJoin = result.flowAt(15, 20).orElseThrow();
        RiverPotentialSolver.Flow afterJoin = result.flowAt(45, 20).orElseThrow();
        assertTrue(tributary.z() > 0.5D, "tributary must flow south into the trunk");
        assertTrue(beforeJoin.x() > 0.5D, "trunk must keep flowing east before the join");
        assertTrue(afterJoin.x() > 0.5D, "trunk must keep flowing east after the join");
        assertStrictlyDownstream(result);
    }

    @Test
    void irregularLakeIsIdle() {
        Map<Long, RiverPotentialSolver.Cell> cells = new HashMap<>();
        for (int z = -14; z <= 14; z++) {
            for (int x = -14; x <= 14; x++) {
                double radius = 11.0D + 4.0D * Math.sin(3.0D * Math.atan2(z, x));
                if (((double) (x * x) + (double) (z * z)) <= radius * radius) {
                    put(cells, x, z, 64, 60);
                }
            }
        }

        RiverPotentialSolver.Result result = RiverPotentialSolver.solve(cells.values());

        assertEquals(RiverPotentialSolver.State.IDLE, result.state());
        assertTrue(result.flows().isEmpty());
    }

    @Test
    void flatTributaryStillJoinsAndFlows() {
        Map<Long, RiverPotentialSolver.Cell> cells = new HashMap<>();
        for (int x = 0; x <= 80; x++) {
            for (int z = -3; z <= 3; z++) {
                put(cells, x, z, 64, 90 - x / 10);
            }
        }
        // Flat along its own length (constant bed), but a genuine through-channel reaching a
        // real tip, not a closed pond - it must still resolve as a real branch, not idle. Kept
        // short relative to the trunk so it cannot become the trunk's own farthest-pair anchor.
        for (int z = 4; z <= 11; z++) {
            put(cells, 40, z, 64, 89);
        }

        RiverPotentialSolver.Result result = RiverPotentialSolver.solve(cells.values());

        assertEquals(RiverPotentialSolver.State.FLOWING, result.state());
        RiverPotentialSolver.Flow tributary = result.flowAt(40, 8).orElseThrow();
        assertTrue(tributary.z() < -0.5D, "flat tributary must still flow toward the main channel");
        RiverPotentialSolver.Flow beforeJoin = result.flowAt(20, 0).orElseThrow();
        RiverPotentialSolver.Flow afterJoin = result.flowAt(70, 0).orElseThrow();
        assertTrue(beforeJoin.x() > 0.5D, "trunk must keep one coherent downstream direction before the join");
        assertTrue(afterJoin.x() > 0.5D, "trunk must keep the same coherent downstream direction after the join");
        assertStrictlyDownstream(result);
    }

    @Test
    void vectorAgreesWithCardinalDirectionTowardTarget() {
        Map<Long, RiverPotentialSolver.Cell> cells = new HashMap<>();
        for (int x = 0; x <= 60; x++) {
            for (int z = -6; z <= 6; z++) {
                put(cells, x, z, 64, 90 - x / 10);
            }
        }

        RiverPotentialSolver.Result result = RiverPotentialSolver.solve(cells.values());

        assertEquals(RiverPotentialSolver.State.FLOWING, result.state());
        assertFalse(result.flows().isEmpty());
        for (Map.Entry<Long, RiverPotentialSolver.Flow> entry : result.flows().entrySet()) {
            RiverPotentialSolver.Flow flow = entry.getValue();
            int x = RiverPotentialSolver.x(entry.getKey());
            int z = RiverPotentialSolver.z(entry.getKey());
            int targetX = RiverPotentialSolver.x(flow.next());
            int targetZ = RiverPotentialSolver.z(flow.next());
            double dot = (flow.x() * (targetX - x)) + (flow.z() * (targetZ - z));
            assertTrue(dot > -1.0E-9D,
                    "rendered vector must not oppose the direction toward the selected target at " + x + "," + z);
        }
    }

    @Test
    void reverseTargetStrictlyIncreasesTheSharedPotential() {
        Map<Long, RiverPotentialSolver.Cell> cells = new HashMap<>();
        for (int x = 0; x <= 60; x++) {
            for (int z = -6; z <= 6; z++) {
                put(cells, x, z, 64, 90 - x / 10);
            }
        }

        RiverPotentialSolver.Result result = RiverPotentialSolver.solve(cells.values());

        assertEquals(RiverPotentialSolver.State.FLOWING, result.state());
        int checked = 0;
        for (Map.Entry<Long, RiverPotentialSolver.Flow> entry : result.flows().entrySet()) {
            RiverPotentialSolver.Flow flow = entry.getValue();
            if (flow.reverseNext() == Long.MIN_VALUE) {
                continue;
            }
            Double here = result.potentials().get(entry.getKey());
            Double reverseTarget = result.potentials().get(flow.reverseNext());
            assertNotNull(here);
            assertNotNull(reverseTarget);
            assertTrue(reverseTarget > here + 1.0E-7D, "the precomputed reverse target must strictly increase the shared potential");
            checked++;
        }
        assertTrue(checked > 0, "expected at least one cell with a precomputed reverse target");
    }

    @Test
    void reversingTwiceReturnsToTheOriginalCellOnAStraightReach() {
        Map<Long, RiverPotentialSolver.Cell> cells = new HashMap<>();
        for (int x = 0; x <= 80; x++) {
            for (int z = -8; z <= 8; z++) {
                put(cells, x, z, 64, 90 - x / 10);
            }
        }

        RiverPotentialSolver.Result result = RiverPotentialSolver.solve(cells.values());
        assertEquals(RiverPotentialSolver.State.FLOWING, result.state());

        for (int x = 20; x <= 60; x += 10) {
            RiverPotentialSolver.Flow here = result.flowAt(x, 0).orElseThrow();
            RiverPotentialSolver.Flow downstream = result.flows().get(here.next());
            assertNotNull(downstream, "downstream neighbor must itself have a resolved flow");
            assertEquals(RiverPotentialSolver.key(x, 0), downstream.reverseNext(),
                    "reversing from the downstream neighbor must land back exactly on the original cell");
        }
    }

    @Test
    void expandingTheWindowPreservesOrientationViaHysteresis() {
        Map<Long, RiverPotentialSolver.Cell> smallWindow = new HashMap<>();
        for (int x = 20; x <= 60; x++) {
            for (int z = -6; z <= 6; z++) {
                put(smallWindow, x, z, 64, 64);
            }
        }
        RiverPotentialSolver.Result initial = RiverPotentialSolver.solve(smallWindow.values());
        assertEquals(RiverPotentialSolver.State.FLOWING, initial.state());
        boolean initiallyEast = initial.flowAt(40, 0).orElseThrow().x() > 0.0D;

        Map<Long, RiverPotentialSolver.Cell> expandedWindow = new HashMap<>();
        for (int x = 0; x <= 100; x++) {
            for (int z = -6; z <= 6; z++) {
                put(expandedWindow, x, z, 64, 64);
            }
        }
        RiverPotentialSolver.Result expanded = RiverPotentialSolver.solve(
                expandedWindow.values(), 1.35D, initial.orientation());

        assertEquals(RiverPotentialSolver.State.FLOWING, expanded.state());
        boolean expandedEast = expanded.flowAt(40, 0).orElseThrow().x() > 0.0D;
        assertEquals(initiallyEast, expandedEast,
                "expanding the loaded window must not flip polarity for a cell the old window already resolved");
    }

    @Test
    void newlyLoadedLowerOutletOverridesAStalePriorOrientation() {
        Map<Long, RiverPotentialSolver.Cell> cells = new HashMap<>();
        for (int x = 0; x <= 80; x++) {
            for (int z = -3; z <= 3; z++) {
                put(cells, x, z, 64, 60 + x / 4);
            }
        }
        RiverPotentialSolver.Orientation staleEastward = new RiverPotentialSolver.Orientation(
                RiverPotentialSolver.key(0, 0), RiverPotentialSolver.key(80, 0));

        RiverPotentialSolver.Result result = RiverPotentialSolver.solve(
                cells.values(), 1.35D, staleEastward);

        assertEquals(RiverPotentialSolver.State.FLOWING, result.state());
        assertTrue(result.flowAt(40, 0).orElseThrow().x() < -0.7D,
                "the newly visible lower western outlet must correct stale eastward hysteresis");
        assertTrue(RiverPotentialSolver.x(result.orientation().downstream()) < 10);
        assertStrictlyDownstream(result);
    }

    @Test
    void nearestOwnerSnapsToClosestPresentCellWithinRange() {
        Map<Long, Long> owner = new HashMap<>();
        owner.put(RiverPotentialSolver.key(10, 0), RiverPotentialSolver.key(10, 0));
        owner.put(RiverPotentialSolver.key(20, 0), RiverPotentialSolver.key(20, 0));
        Set<Long> waterCells = owner.keySet();

        assertEquals(RiverPotentialSolver.key(10, 0),
                RiverPotentialSolver.nearestOwner(RiverPotentialSolver.key(10, 0), waterCells, owner),
                "an exact match must short-circuit without needing to scan for a substitute");

        assertEquals(RiverPotentialSolver.key(20, 0),
                RiverPotentialSolver.nearestOwner(RiverPotentialSolver.key(25, 0), waterCells, owner),
                "a missing exact key must snap to the nearest present cell's owner");

        assertNull(RiverPotentialSolver.nearestOwner(RiverPotentialSolver.key(200, 0), waterCells, owner),
                "a target far outside the snap radius must not adopt an unrelated body's anchor");
    }

    @Test
    void downstreamArmIsNotFlattenedWhenATributaryOutdistancesIt() {
        Map<Long, RiverPotentialSolver.Cell> cells = new HashMap<>();
        // Trunk: x=0 (upstream) to x=150 (the true downstream/outlet, still comfortably
        // longer than the channel width so it forms its own stable skeleton arm).
        for (int x = 0; x <= 150; x++) {
            for (int z = -3; z <= 3; z++) {
                put(cells, x, z, 64, 100 - x / 2);
            }
        }
        // Tributary joins near the downstream end (x=120) and runs far longer than either
        // trunk arm, so a pure graph-distance "diameter" search anchors on (upstream,
        // tributary tip) instead of (upstream, downstream) - leaving the true downstream
        // arm to be mistaken for a dangling branch and flattened to a constant potential.
        for (int z = 4; z <= 304; z++) {
            put(cells, 120, z, 64, 40 + (z - 4));
        }

        RiverPotentialSolver.Result result = RiverPotentialSolver.solve(cells.values());

        assertEquals(RiverPotentialSolver.State.FLOWING, result.state());
        RiverPotentialSolver.Flow downstreamArm = result.flowAt(140, 0).orElseThrow();
        assertTrue(downstreamArm.x() > 0.5D,
                "the true downstream arm must keep flowing even when a tributary is graph-farther from upstream");
        assertStrictlyDownstream(result);
    }

    @Test
    void shallowBankLobeDoesNotPullArrowsOffTheMainChannel() {
        Map<Long, RiverPotentialSolver.Cell> cells = new HashMap<>();
        for (int x = 0; x <= 60; x++) {
            for (int z = -8; z <= 8; z++) {
                put(cells, x, z, 64, 100 - x);
            }
        }
        // A shallow bank bulge, not a real tributary - shorter than the main channel's own
        // width, so thinning's spur into it must be pruned rather than treated as a branch.
        for (int x = 25; x <= 32; x++) {
            for (int z = 9; z <= 16; z++) {
                put(cells, x, z, 64, 85);
            }
        }

        RiverPotentialSolver.Result result = RiverPotentialSolver.solve(cells.values());

        assertEquals(RiverPotentialSolver.State.FLOWING, result.state());
        for (int z = 9; z <= 16; z++) {
            result.flowAt(28, z).ifPresent(flow -> assertTrue(flow.x() > 0.5D,
                    "a bank lobe must not pull the flow sideways off the main channel at z=" + 28));
        }
        assertTrue(result.flowAt(20, 0).orElseThrow().x() > 0.7D);
        assertTrue(result.flowAt(50, 0).orElseThrow().x() > 0.7D);
        assertStrictlyDownstream(result);
    }

    @Test
    void openBoundaryKeepsAFlatShortTributaryDistinctFromABankLobe() {
        Map<Long, RiverPotentialSolver.Cell> cells = new HashMap<>();
        for (int x = -30; x <= 30; x++) {
            for (int z = -1; z <= 1; z++) {
                put(cells, x, z, 64, 70 - x / 3);
            }
        }
        for (int z = -8; z <= -2; z++) {
            for (int x = -1; x <= 1; x++) {
                put(cells, x, z, 64, 70);
            }
        }

        RiverPotentialSolver.Result closed = RiverPotentialSolver.solve(cells.values());
        RiverPotentialSolver.Result open = RiverPotentialSolver.solve(
                cells.values(), 1.35D, null, Set.of(RiverPotentialSolver.key(0, -8)));

        assertEquals(RiverPotentialSolver.State.FLOWING, closed.state());
        assertEquals(RiverPotentialSolver.State.FLOWING, open.state());
        double closedBranchRise = closed.potentials().get(RiverPotentialSolver.key(0, -8))
                - closed.potentials().get(RiverPotentialSolver.key(0, 0));
        double openBranchRise = open.potentials().get(RiverPotentialSolver.key(0, -8))
                - open.potentials().get(RiverPotentialSolver.key(0, 0));
        assertTrue(openBranchRise > closedBranchRise + 0.02D,
                "an open tributary must retain a real upstream gradient instead of flattening into the bank");
    }

    @Test
    void fallingSurfaceOutranksAnOpposingShallowBed() {
        Map<Long, RiverPotentialSolver.Cell> cells = new HashMap<>();
        for (int x = 0; x <= 80; x++) {
            for (int z = -3; z <= 3; z++) {
                int surface = 66 - x / 40;
                int bed = 40 + x / 4;
                put(cells, x, z, surface, Math.min(surface - 1, bed));
            }
        }
        RiverPotentialSolver.Orientation staleWestward = new RiverPotentialSolver.Orientation(
                RiverPotentialSolver.key(80, 0), RiverPotentialSolver.key(0, 0));

        RiverPotentialSolver.Result result = RiverPotentialSolver.solve(
                cells.values(), 1.35D, staleWestward);

        assertEquals(RiverPotentialSolver.State.FLOWING, result.state());
        assertTrue(result.flowAt(40, 0).orElseThrow().x() > 0.7D,
                "lower water surface to the east must override stale polarity and a shallower eastern bed");
    }

    @Test
    void bedSlopeUsesChannelPathLengthInsteadOfMeanderChord() {
        Map<Long, RiverPotentialSolver.Cell> straightCells = new HashMap<>();
        Map<Long, Long> straightParent = new HashMap<>();
        Map<Long, RiverPotentialSolver.Cell> bentCells = new HashMap<>();
        Map<Long, Long> bentParent = new HashMap<>();
        long straightStart = RiverPotentialSolver.key(0, 0);
        long bentStart = RiverPotentialSolver.key(0, 0);
        for (int station = 0; station <= 16; station++) {
            int bed = 100 - station;
            long straight = RiverPotentialSolver.key(station, 0);
            int bentX = Math.min(8, station);
            int bentZ = Math.max(0, station - 8);
            long bent = RiverPotentialSolver.key(bentX, bentZ);
            straightCells.put(straight, new RiverPotentialSolver.Cell(station, 0, 120, bed));
            bentCells.put(bent, new RiverPotentialSolver.Cell(bentX, bentZ, 120, bed));
            if (station > 0) {
                straightParent.put(RiverPotentialSolver.key(station - 1, 0), straight);
                int previousX = Math.min(8, station - 1);
                int previousZ = Math.max(0, station - 9);
                bentParent.put(RiverPotentialSolver.key(previousX, previousZ), bent);
            }
        }

        assertEquals(
                RiverPotentialSolver.downstreamBedSlope(straightStart, straightParent, straightCells),
                RiverPotentialSolver.downstreamBedSlope(bentStart, bentParent, bentCells),
                1.0E-9D,
                "turning a channel must not inflate its physical grade");
    }

    private static void stroke(Map<Long, RiverPotentialSolver.Cell> cells, int x, int z, int radius, int station) {
        int bed = 100 - station / 8;
        for (int dz = -radius; dz <= radius; dz++) {
            for (int dx = -radius; dx <= radius; dx++) {
                putLower(cells, x + dx, z + dz, 64, bed);
            }
        }
    }

    private static void put(Map<Long, RiverPotentialSolver.Cell> cells, int x, int z, int surface, int bed) {
        cells.put(RiverPotentialSolver.key(x, z), new RiverPotentialSolver.Cell(x, z, surface, bed));
    }

    private static void putLower(Map<Long, RiverPotentialSolver.Cell> cells, int x, int z, int surface, int bed) {
        long key = RiverPotentialSolver.key(x, z);
        RiverPotentialSolver.Cell previous = cells.get(key);
        if (previous == null || bed < previous.bedY()) {
            cells.put(key, new RiverPotentialSolver.Cell(x, z, surface, bed));
        }
    }

    private static void assertStrictlyDownstream(RiverPotentialSolver.Result result) {
        for (Map.Entry<Long, RiverPotentialSolver.Flow> entry : result.flows().entrySet()) {
            Double here = result.potentials().get(entry.getKey());
            Double next = result.potentials().get(entry.getValue().next());
            assertNotNull(here);
            assertNotNull(next);
            assertTrue(next < here - 1.0E-7D, "every target must decrease one shared potential");
        }
    }
}
