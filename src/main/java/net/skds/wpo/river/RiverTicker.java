package net.skds.wpo.river;

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BiomeTags;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.material.Fluids;
import net.skds.wpo.WPOConfig;
import net.skds.wpo.api.WPOFluidAccess;

public final class RiverTicker {

    private static final OsmWaterwayProvider OSM = new OsmWaterwayProvider();

    private RiverTicker() {
    }

    public static void tick(ServerLevel level) {
        if (!RiverConfig.COMMON.enabled.get()) {
            return;
        }
        int interval = RiverConfig.COMMON.updateInterval.get();
        if ((level.getGameTime() % interval) != 0L) {
            return;
        }
        if (level.players().isEmpty()) {
            return;
        }

        RiverSavedData data = RiverSavedData.get(level);
        if ((level.getGameTime() % 24000L) == 0L) {
            data.sweepStale(level.getGameTime());
        }
        if ((level.getGameTime() % 200L) == 0L) {
            RiverCurrentField.sweep(level, RiverConfig.COMMON.currentMaxAgeTicks.get());
            RiverTopologyField.sweep(level);
        }

        int budget = Math.max(1, level.players().size() * RiverConfig.COMMON.columnChecksPerPlayer.get());
        long cursor = data.nextCursor(budget);
        int radius = RiverConfig.COMMON.sampleRadius.get();
        int players = Math.max(1, level.players().size());
        int perPlayer = Math.max(1, budget / players);
        int nearBudget = Math.min(96, Math.max(24, perPlayer / 2));

        int processed = 0;
        for (Player player : level.players()) {
            for (int i = 0; i < nearBudget && processed < budget; i++, processed++) {
                BlockPos sample = sampleColumn(player.blockPosition(), 18, cursor + processed);
                processColumn(level, sample.getX(), sample.getZ(), data);
            }
        }

        for (int i = processed; i < budget; i++) {
            Player player = level.players().get((int) Math.floorMod(cursor + i, players));
            BlockPos sample = sampleColumn(player.blockPosition(), radius, cursor + i);
            processColumn(level, sample.getX(), sample.getZ(), data);
        }
    }

    private static void processColumn(ServerLevel level, int x, int z, RiverSavedData data) {
        BlockPos waterPos = findSurfaceWater(level, x, z);
        if (waterPos == null) {
            return;
        }
        processWaterColumn(level, waterPos, data, true);
    }

    static void processBenchmarkWater(ServerLevel level, BlockPos waterPos, RiverSavedData data) {
        processBenchmarkWater(level, waterPos, data, true);
    }

    static void processBenchmarkWater(ServerLevel level, BlockPos waterPos, RiverSavedData data, boolean allowHydrologyFeed) {
        if (waterPos.getY() < level.getMinBuildHeight() || waterPos.getY() >= level.getMaxBuildHeight()) {
            return;
        }
        if (!level.hasChunkAt(waterPos)) {
            return;
        }
        processWaterColumn(level, waterPos, data, allowHydrologyFeed);
    }

    static String probe(ServerPlayer player) {
        ServerLevel level = player.serverLevel();
        BlockPos origin = player.blockPosition();
        BlockPos waterPos = nearestSurfaceWater(level, origin, 18);
        if (waterPos == null) {
            return "river_probe: no surface water found within 18 blocks of " + format(origin);
        }

        RiverSavedData data = RiverSavedData.get(level);
        int before = WPOFluidAccess.getWaterAmount(level, waterPos);
        processWaterColumn(level, waterPos, data, true);
        int after = WPOFluidAccess.getWaterAmount(level, waterPos);
        Optional<FlowDirective> flow = after > 0 ? resolveFlow(level, waterPos, after) : Optional.empty();
        Optional<RiverCurrentField.Current> current = RiverCurrentField.currentAt(level, waterPos);
        String biome = level.getBiome(waterPos).unwrapKey()
                .map(key -> key.location().toString())
                .orElse(level.getBiome(waterPos).toString());

        return "river_probe: pos=" + format(waterPos)
                + " biome=" + biome
                + " amount " + before + "->" + after
                + " fluid=" + level.getFluidState(waterPos).getType()
                + " flow=" + flow.map(f -> f.source() + " " + f.direction() + " -> " + format(f.target())
                        + " speed=" + String.format(java.util.Locale.ROOT, "%.3f", f.speed())).orElse("none")
                + " current=" + current.map(c -> c.source() + " " + c.direction()
                        + " strength=" + String.format(java.util.Locale.ROOT, "%.2f", c.strength())
                        + " speed=" + String.format(java.util.Locale.ROOT, "%.3f", c.speed())).orElse("none")
                + " requireRiverBiome=" + RiverConfig.COMMON.requireRiverBiomeForFallback.get()
                + " channelAxis=" + RiverConfig.COMMON.channelAxisFallback.get();
    }

    private static void processWaterColumn(ServerLevel level, BlockPos waterPos, RiverSavedData data, boolean allowHydrologyFeed) {
        ChunkPos chunkPos = new ChunkPos(waterPos);
        data.markVisited(chunkPos, level.getGameTime());

        int amount = WPOFluidAccess.getWaterAmount(level, waterPos);
        if (amount <= 0) {
            RiverCurrentField.clear(level, waterPos);
            return;
        }

        FlowDirective canonicalFlow = resolveFlow(level, waterPos, amount).orElse(null);
        if (canonicalFlow == null) {
            RiverCurrentField.clear(level, waterPos);
            return;
        }

        TerrariumBridge.TerrariumSample terrarium = allowHydrologyFeed ? TerrariumBridge.sample(level, waterPos).orElse(null) : null;
        boolean explicitMappedRiver = "osm".equals(canonicalFlow.source());
        boolean generatedRiver = isGeneratedRiver(level, waterPos);
        if (shouldSkipFallbackRiver(generatedRiver, explicitMappedRiver)) {
            RiverCurrentField.clear(level, waterPos);
            return;
        }
        if (!explicitMappedRiver && terrarium != null && terrarium.elevationMeters() <= 0) {
            return;
        }

        long chunkKey = chunkPos.toLong();
        boolean riverColumn = generatedRiver || explicitMappedRiver || isInferredRiverFlow(canonicalFlow);
        boolean infiniteSource = allowHydrologyFeed
                && riverColumn
                && RiverConfig.COMMON.infiniteRiverSources.get()
                && isSourceStart(level, waterPos, canonicalFlow);
        if (allowHydrologyFeed) {
            addRunoffBudget(level, data, chunkKey, waterPos, terrarium);
            feedVisibleRiver(level, data, chunkKey, waterPos);
        }
        double currentStrength = currentStrength(level, data, chunkKey, waterPos, amount, canonicalFlow);
        RiverCurrentField.publishColumn(level, waterPos, canonicalFlow, currentStrength);
        FlowDirective flow = orientedFlow(level, waterPos, canonicalFlow);
        RiverCurrentField.smoothedFlowAt(level, waterPos)
                .ifPresent(smoothed -> maybeSpawnCurrentParticle(level, waterPos, smoothed, currentStrength));
        moveDownstream(level, data, waterPos, flow, riverColumn);
        if (allowHydrologyFeed) {
            maintainRiverMinimum(level, data, chunkKey, waterPos, riverColumn);
            feedInfiniteSource(level, waterPos, infiniteSource);
        }
    }

    private static FlowDirective orientedFlow(ServerLevel level, BlockPos source, FlowDirective canonical) {
        if (!RiverCurrentField.isReversed(level)) {
            return canonical;
        }
        double vx = -canonical.vecX();
        double vz = -canonical.vecZ();
        Direction direction = Math.abs(vx) >= Math.abs(vz)
                ? (vx >= 0.0D ? Direction.EAST : Direction.WEST)
                : (vz >= 0.0D ? Direction.SOUTH : Direction.NORTH);
        BlockPos target = downstreamTarget(level, source, direction, true);
        if (target == null) {
            target = source.relative(direction);
        }
        return new FlowDirective(direction, target, canonical.source(), vx, vz, canonical.speed());
    }

    private static Optional<FlowDirective> resolveFlow(ServerLevel level, BlockPos waterPos, int amount) {
        if (RiverConfig.COMMON.flatOutletSolver.get() && level.getBiome(waterPos).is(BiomeTags.IS_OCEAN)) {
            // Oceans/large seas have no meaningful "downstream" - skip the expensive
            // flat/channel/magnet fallback chain entirely rather than let it search one.
            return Optional.empty();
        }

        Optional<TerrariumBridge.ProjectionContext> projection = TerrariumBridge.projection(level);
        if (projection.isPresent()) {
            Optional<OsmWaterwayProvider.FlowVector> osm = OSM.findDirection(
                    level,
                    waterPos.getX() + 0.5D,
                    waterPos.getZ() + 0.5D,
                    projection.get()
            );
            if (osm.isPresent()) {
                BlockPos target = downstreamTarget(level, waterPos, osm.get().direction(), true);
                if (target != null) {
                    return Optional.of(new FlowDirective(osm.get().direction(), target, "osm"));
                }
            }
        }

        RiverTopologyField.Decision topology = RiverTopologyField.resolve(level, waterPos);
        if (topology.resolved()) {
            return Optional.ofNullable(topology.flow());
        }

        Optional<FlowDirective> cached = RiverCurrentField.cachedFlow(level, waterPos);
        if (cached.isPresent()) {
            return cached;
        }

        if (!RiverConfig.COMMON.terrainFallbackDirections.get() && !RiverConfig.COMMON.flatOutletSolver.get()) {
            return Optional.empty();
        }

        // Resolve a channel-shaped reach before comparing one-step terrain drops. A small local
        // head fluctuation is common in a wide reach; letting it win first makes neighboring
        // columns choose unrelated bearings and defeats bend/branch continuity.
        if (RiverConfig.COMMON.channelAxisFallback.get()) {
            Optional<FlowDirective> channel = resolveChannelAxisFlow(level, waterPos, amount);
            if (channel.isPresent()) {
                return channel;
            }
        }

        // For bends and tributaries that are not cardinally channel-shaped, resolve the
        // connected surface route. Its carried first hop preserves local turns without letting
        // one noisy adjacent column choose a different terrain direction.
        if (RiverConfig.COMMON.flatOutletSolver.get()) {
            Optional<FlowDirective> flat = resolveFlatOutletFlow(level, waterPos, amount);
            if (flat.isPresent()) {
                return flat;
            }
        }

        int currentHead = absoluteHead(waterPos, amount);
        Direction bestDirection = null;
        BlockPos bestTarget = null;
        int bestDrop = 0;
        if (RiverConfig.COMMON.terrainFallbackDirections.get()) {
            for (Direction direction : Direction.Plane.HORIZONTAL) {
                BlockPos target = downstreamTarget(level, waterPos, direction, false);
                if (target == null || !WPOFluidAccess.isChunkLoaded(level, target)) {
                    continue;
                }
                if (!WPOFluidAccess.canWaterFlowBetween(level, waterPos, target)) {
                    continue;
                }
                int targetAmount = WPOFluidAccess.getWaterAmount(level, target);
                if (targetAmount <= 0) {
                    continue;
                }
                int drop = currentHead - absoluteHead(target, targetAmount);
                if (drop >= RiverConfig.COMMON.minFallbackHeadDrop.get() && drop > bestDrop) {
                    bestDirection = direction;
                    bestTarget = target;
                    bestDrop = drop;
                }
            }
        }

        if (bestDirection == null || bestTarget == null) {
            Optional<FlowDirective> bed = resolveBedSlopeFlow(level, waterPos);
            if (bed.isPresent()) {
                return bed;
            }
            return resolveMagnetOnlyFlow(level, waterPos, amount);
        }
        return Optional.of(new FlowDirective(bestDirection, bestTarget, "terrain"));
    }

    private static Optional<FlowDirective> resolveChannelAxisFlow(ServerLevel level, BlockPos source, int sourceAmount) {
        if (!RiverConfig.COMMON.channelAxisFallback.get()) {
            return Optional.empty();
        }

        int maxDistance = RiverConfig.COMMON.channelAxisScanDistance.get();
        AxisCandidate northSouth = axisCandidate(level, source, Direction.NORTH, Direction.SOUTH, maxDistance);
        AxisCandidate westEast = axisCandidate(level, source, Direction.WEST, Direction.EAST, maxDistance);
        AxisCandidate primary = northSouth.score() >= westEast.score() ? northSouth : westEast;
        AxisCandidate secondary = primary == northSouth ? westEast : northSouth;
        if (primary.score() < 10) {
            return Optional.empty();
        }
        // A round/wide pond scores both axes similarly (long, roughly equal water-run in every
        // direction) - without this it "qualifies" as channel-shaped and the score deadband's
        // constant tie-break commits every column to the same arbitrary default (west), which
        // looks like a real current but isn't. A real channel's cross-axis run is short relative
        // to its length, so this only excludes bodies that were never channel-shaped to begin with.
        if (primary.score() < secondary.score() * 2) {
            return Optional.empty();
        }

        Direction downhill = axisDirection(level, source, sourceAmount, primary, maxDistance);
        BlockPos target = downstreamTarget(level, source, downhill, true);
        if (target == null) {
            return Optional.empty();
        }

        // On an angled channel both axes are viable; blend them (weighted by relative score)
        // into the published vector so the rendered/push direction follows the channel's true
        // diagonal. The cardinal alone keeps steering transport and consensus votes.
        double vx = downhill.getStepX();
        double vz = downhill.getStepZ();
        if (secondary.score() >= 10) {
            Direction across = axisDirection(level, source, sourceAmount, secondary, maxDistance);
            double weight = Math.min(1.0D, secondary.score() / (double) primary.score());
            vx += across.getStepX() * weight;
            vz += across.getStepZ() * weight;
            double len = Math.sqrt(vx * vx + vz * vz);
            vx /= len;
            vz /= len;
        }
        return Optional.of(new FlowDirective(downhill, target, "channel", vx, vz));
    }

    // Committed neighbors along the axis outrank re-deriving orientation from water levels:
    // on a flat reach the level-based score is slosh noise, and independent per-column
    // coin-flips are exactly what produced opposing patches inside one river.
    private static Direction axisDirection(ServerLevel level, BlockPos source, int sourceAmount, AxisCandidate axis, int maxDistance) {
        int votesNegative = 0;
        int votesPositive = 0;
        for (Direction walk : new Direction[] { axis.negative(), axis.positive() }) {
            BlockPos pos = source;
            for (int i = 0; i < 8; i++) {
                pos = pos.relative(walk);
                Direction committed = RiverCurrentField.currentAt(level, pos)
                        .map(RiverCurrentField.Current::direction)
                        .orElse(null);
                if (committed == axis.negative()) {
                    votesNegative++;
                } else if (committed == axis.positive()) {
                    votesPositive++;
                }
            }
        }
        if (votesNegative != votesPositive) {
            return votesNegative > votesPositive ? axis.negative() : axis.positive();
        }
        return chooseAxisDirection(level, source, sourceAmount, axis.negative(), axis.positive(), maxDistance);
    }

    private static AxisCandidate axisCandidate(ServerLevel level, BlockPos source, Direction negative, Direction positive, int maxDistance) {
        int neg = waterRunLength(level, source, negative, maxDistance);
        int pos = waterRunLength(level, source, positive, maxDistance);
        int total = neg + pos;
        int balancePenalty = Math.abs(neg - pos) / 4;
        return new AxisCandidate(negative, positive, total - balancePenalty, neg, pos);
    }

    private static int waterRunLength(ServerLevel level, BlockPos source, Direction direction, int maxDistance) {
        int length = 0;
        BlockPos current = source;
        for (int i = 0; i < maxDistance; i++) {
            BlockPos nextColumn = current.relative(direction);
            BlockPos next = surfaceWaterNear(level, nextColumn.getX(), nextColumn.getZ(), source.getY(), 4);
            if (next == null) {
                break;
            }
            length++;
            current = next;
        }
        return length;
    }

    // a is always the axis' negative direction (NORTH/WEST) so the tied-case pick is a
    // global constant: independent columns seeding the same flat reach agree by
    // construction instead of coin-flipping on residual level noise.
    private static Direction chooseAxisDirection(ServerLevel level, BlockPos source, int sourceAmount, Direction a, Direction b, int maxDistance) {
        double scoreA = channelOutletScore(level, source, sourceAmount, a, maxDistance);
        double scoreB = channelOutletScore(level, source, sourceAmount, b, maxDistance);
        // Feed/slosh transients move run-end heads a few levels (x4 weight); inside that
        // band the comparison is noise. Real signal - a block of slope over the run - is
        // 32+ points and clears the band easily.
        if (Math.abs(scoreA - scoreB) <= 16.0D) {
            return a;
        }
        return scoreA >= scoreB ? a : b;
    }

    private static double channelOutletScore(ServerLevel level, BlockPos source, int sourceAmount, Direction direction, int maxDistance) {
        BlockPos end = source;
        int run = 0;
        BlockPos[] tail = new BlockPos[3];
        for (int i = 0; i < maxDistance; i++) {
            BlockPos nextColumn = end.relative(direction);
            BlockPos next = surfaceWaterNear(level, nextColumn.getX(), nextColumn.getZ(), source.getY(), 4);
            if (next == null) {
                break;
            }
            end = next;
            tail[run % 3] = next;
            run++;
        }

        // Median head of the last few run columns instead of the single end cell: one fed
        // or sloshing column at the end otherwise swings the whole direction comparison.
        int headDrop = absoluteHead(source, sourceAmount) - medianTailHead(level, source, tail, run);
        int terrainDrop = level.getHeight(Heightmap.Types.WORLD_SURFACE, source.getX(), source.getZ())
                - level.getHeight(Heightmap.Types.WORLD_SURFACE, end.getX(), end.getZ());
        return (headDrop * 4.0D) + (terrainDrop * 2.0D) + run;
    }

    private static int medianTailHead(ServerLevel level, BlockPos source, BlockPos[] tail, int run) {
        int count = Math.min(3, run);
        if (count == 0) {
            return approximateHead(level, source);
        }
        int[] heads = new int[count];
        for (int i = 0; i < count; i++) {
            heads[i] = approximateHead(level, tail[i]);
        }
        Arrays.sort(heads);
        return heads[count / 2];
    }

    private static Optional<FlowDirective> resolveFlatOutletFlow(ServerLevel level, BlockPos source, int sourceAmount) {
        if (!RiverConfig.COMMON.flatOutletSolver.get()) {
            return Optional.empty();
        }

        // Validate that the source has at least one connected water hop before paying for the
        // component search. The BFS below carries the first hop with every node; returning that
        // path-local hop is what makes a bend turn and lets tributaries converge, instead of
        // making every cell point at the outlet's straight-line bearing.
        int sourceHopCount = 0;
        for (Direction direction : Direction.Plane.HORIZONTAL) {
            BlockPos target = downstreamTarget(level, source, direction, false);
            if (target == null || !WPOFluidAccess.isChunkLoaded(level, target)
                    || !WPOFluidAccess.canWaterFlowBetween(level, source, target)
                    || WPOFluidAccess.getWaterAmount(level, target) <= 0) {
                continue;
            }
            sourceHopCount++;
        }
        if (sourceHopCount == 0) {
            return Optional.empty();
        }

        int sourceHead = absoluteHead(source, sourceAmount);
        int maxCells = RiverConfig.COMMON.flatSolverMaxCells.get();
        int flatTolerance = RiverConfig.COMMON.flatHeadTolerance.get();
        int minOutletDrop = RiverConfig.COMMON.flatMinOutletDrop.get();
        int minOutletBedDrop = RiverConfig.COMMON.flatMinOutletBedDrop.get();

        ArrayDeque<FlatNode> queue = new ArrayDeque<>();
        Set<Long> visited = new HashSet<>();
        queue.add(new FlatNode(source, 0, null));
        visited.add(source.asLong());

        BlockPos bestOutletPos = null;
        Direction bestFirstDirection = null;
        double bestScore = Double.NEGATIVE_INFINITY;

        while (!queue.isEmpty() && visited.size() <= maxCells) {
            FlatNode node = queue.removeFirst();
            int nodeAmount = WPOFluidAccess.getWaterAmount(level, node.pos());
            if (nodeAmount <= 0) {
                continue;
            }
            int nodeHead = absoluteHead(node.pos(), nodeAmount);
            int nodeBed = level.getHeight(Heightmap.Types.OCEAN_FLOOR, node.pos().getX(), node.pos().getZ());

            for (Direction direction : Direction.Plane.HORIZONTAL) {
                BlockPos target = downstreamTarget(level, node.pos(), direction, false);
                if (target == null || !WPOFluidAccess.isChunkLoaded(level, target)) {
                    continue;
                }
                if (!WPOFluidAccess.canWaterFlowBetween(level, node.pos(), target)) {
                    continue;
                }

                int targetAmount = WPOFluidAccess.getWaterAmount(level, target);
                if (targetAmount <= 0) {
                    continue;
                }

                int targetHead = absoluteHead(target, targetAmount);
                int drop = nodeHead - targetHead;
                // A wide pool's own slosh can fake a WPO head drop within a few columns without
                // the ground actually descending - requiring the streambed itself to have
                // stepped down keeps mid-pool columns from latching onto that as a fake exit.
                int targetBed = level.getHeight(Heightmap.Types.OCEAN_FLOOR, target.getX(), target.getZ());
                int bedDrop = nodeBed - targetBed;
                if (drop >= minOutletDrop && bedDrop >= minOutletBedDrop) {
                    Direction firstDirection = node.firstDirection() != null
                            ? node.firstDirection()
                            : direction;
                    // Pick one outlet for the whole connected component. Scoring by this
                    // source's transient head made opposite sides of a broad reach choose
                    // different exits; lowest bed is stable, while the carried first hop
                    // still preserves every local bend and tributary path.
                    double score = (-targetBed * 1000.0D)
                            + (target.getY() * 0.1D)
                            - (node.distance() * 0.01D)
                            - (target.getX() * 0.000001D)
                            - (target.getZ() * 0.000000001D);
                    if (score > bestScore) {
                        bestScore = score;
                        bestOutletPos = target;
                        bestFirstDirection = firstDirection;
                    }
                    continue;
                }

                if (Math.abs(targetHead - sourceHead) > flatTolerance || Math.abs(targetHead - nodeHead) > flatTolerance) {
                    continue;
                }

                long key = target.asLong();
                if (visited.add(key)) {
                    Direction firstDirection = node.firstDirection() != null
                            ? node.firstDirection()
                            : direction;
                    queue.addLast(new FlatNode(target.immutable(), node.distance() + 1, firstDirection));
                }
            }
        }

        if (bestOutletPos == null) {
            return Optional.empty();
        }

        if (bestFirstDirection == null) {
            return Optional.empty();
        }
        BlockPos bestTarget = downstreamTarget(level, source, bestFirstDirection, false);
        if (bestTarget == null) {
            return Optional.empty();
        }
        return Optional.of(new FlowDirective(bestFirstDirection, bestTarget, "flat"));
    }

    // A wide, slow river's water surface commonly reads as one flat WPO head for many blocks -
    // the fluid self-levels faster than the sim publishes new levels - even though the generated
    // streambed keeps descending underneath. Reading the terrain heightmap directly gives those
    // reaches a real direction instead of falling through to magnetism (wrong for a river) or
    // nothing at all. Static terrain has no per-tick slosh, so unlike the head-drop tiers above
    // this needs no noise-floor threshold beyond "actually downhill".
    private static Optional<FlowDirective> resolveBedSlopeFlow(ServerLevel level, BlockPos source) {
        if (!RiverConfig.COMMON.bedSlopeFallback.get()) {
            return Optional.empty();
        }

        int sourceBed = level.getHeight(Heightmap.Types.OCEAN_FLOOR, source.getX(), source.getZ());
        Direction bestDirection = null;
        BlockPos bestTarget = null;
        int bestDrop = 0;
        for (Direction direction : Direction.Plane.HORIZONTAL) {
            BlockPos target = downstreamTarget(level, source, direction, false);
            if (target == null || !WPOFluidAccess.isChunkLoaded(level, target)) {
                continue;
            }
            if (!WPOFluidAccess.canWaterFlowBetween(level, source, target)) {
                continue;
            }
            if (WPOFluidAccess.getWaterAmount(level, target) <= 0) {
                continue;
            }
            int targetBed = level.getHeight(Heightmap.Types.OCEAN_FLOOR, target.getX(), target.getZ());
            int drop = sourceBed - targetBed;
            if (drop > bestDrop) {
                bestDirection = direction;
                bestTarget = target;
                bestDrop = drop;
            }
        }
        if (bestDirection == null) {
            return Optional.empty();
        }
        return Optional.of(new FlowDirective(bestDirection, bestTarget, "bed"));
    }

    private static Optional<FlowDirective> resolveMagnetOnlyFlow(ServerLevel level, BlockPos source, int sourceAmount) {
        if (!RiverConfig.COMMON.waterBodyMagnetism.get()) {
            return Optional.empty();
        }
        // Self-attraction: deep inside a large body of water the mass scan measures that body's
        // own bulk and returns an arbitrary lobe of it, not a real target. The river biome tag
        // isn't a reliable signal for this - this mod's own wider carved channels routinely
        // generate outside the vanilla river biome band - so check the water itself: a
        // neighborhood this saturated isn't an isolated puddle reaching for a real nearby body,
        // it's already inside one. 160 is 80% of localWaterMass's 200 ceiling (25 columns x
        // MAX_FLUID_LEVEL 8).
        if (localWaterMass(level, source, source.getY()) >= 160) {
            return Optional.empty();
        }

        AttractionVector attraction = waterBodyAttractionVector(level, source);
        Direction direction = attraction.direction();
        if (direction == null || attraction.strength() < 6.0D) {
            return Optional.empty();
        }

        BlockPos target = magneticTarget(level, source, direction);
        if (target == null) {
            return Optional.empty();
        }

        int headDrop = absoluteHead(source, sourceAmount) - approximateHead(level, target);
        if (headDrop < -RiverConfig.COMMON.magnetMaxUphillHead.get()) {
            return Optional.empty();
        }
        return Optional.of(new FlowDirective(direction, target, "magnet"));
    }

    private static boolean shouldSkipFallbackRiver(boolean generatedRiver, boolean explicitMappedRiver) {
        return RiverConfig.COMMON.generatedRiverReplacement.get()
                && RiverConfig.COMMON.requireRiverBiomeForFallback.get()
                && !explicitMappedRiver
                && !generatedRiver;
    }

    private static boolean isInferredRiverFlow(FlowDirective flow) {
        return flow.source().contains("terrain")
                || flow.source().contains("flat")
                || flow.source().contains("channel")
                || flow.source().contains("topology");
    }

    private static boolean isGeneratedRiver(ServerLevel level, BlockPos pos) {
        return RiverConfig.COMMON.generatedRiverReplacement.get()
                && level.getBiome(pos).is(BiomeTags.IS_RIVER);
    }

    private static BlockPos magneticTarget(ServerLevel level, BlockPos source, Direction direction) {
        BlockPos direct = downstreamTarget(level, source, direction, true);
        if (direct != null && canUseMagnetTarget(level, source, direct)) {
            return direct;
        }

        BlockPos column = source.relative(direction);
        int maxDrop = Math.min(4, Math.max(1, RiverConfig.COMMON.magnetMaxUphillHead.get()));
        for (int dy = 1; dy <= maxDrop; dy++) {
            BlockPos candidate = column.below(dy);
            if (candidate.getY() < level.getMinBuildHeight()) {
                break;
            }
            if (canUseMagnetTarget(level, source, candidate)) {
                return candidate;
            }
        }
        return null;
    }

    private static boolean canUseMagnetTarget(ServerLevel level, BlockPos source, BlockPos target) {
        return !WPOFluidAccess.isChunkLoaded(level, target) || WPOFluidAccess.canWaterFlowBetween(level, source, target);
    }

    private static int approximateHead(ServerLevel level, BlockPos pos) {
        if (!WPOFluidAccess.isChunkLoaded(level, pos)) {
            return absoluteHead(pos, 0);
        }
        int amount = WPOFluidAccess.getWaterAmount(level, pos);
        return absoluteHead(pos, amount);
    }

    private static AttractionVector waterBodyAttractionVector(ServerLevel level, BlockPos source) {
        int radius = RiverConfig.COMMON.waterBodyMagnetRadius.get();
        int step = radius > 18 ? 2 : 1;
        int bestDx = 0;
        int bestDz = 0;
        double bestScore = 0.0D;
        double bestDistance = Double.MAX_VALUE;

        for (int dz = -radius; dz <= radius; dz += step) {
            for (int dx = -radius; dx <= radius; dx += step) {
                int distSq = (dx * dx) + (dz * dz);
                if (distSq <= 4 || distSq > radius * radius) {
                    continue;
                }

                int amount = nearbyWaterAmount(level, source.offset(dx, 0, dz), source.getY());
                if (amount < WPOConfig.MAX_FLUID_LEVEL) {
                    continue;
                }

                int localMass = localWaterMass(level, source.offset(dx, 0, dz), source.getY());
                if (localMass < 24) {
                    continue;
                }

                double distance = Math.sqrt(distSq);
                double score = localMass / distance;
                if (score > bestScore || (Math.abs(score - bestScore) < 0.001D && distance < bestDistance)) {
                    bestDx = dx;
                    bestDz = dz;
                    bestScore = score;
                    bestDistance = distance;
                }
            }
        }

        if (bestScore <= 0.0D) {
            return new AttractionVector(null, 0.0D);
        }
        Direction direction = Direction.getNearest((float) bestDx, 0.0F, (float) bestDz);
        return new AttractionVector(direction, bestScore);
    }

    private static int nearbyWaterAmount(ServerLevel level, BlockPos column, int sourceY) {
        if (!WPOFluidAccess.isChunkLoaded(level, column)) {
            return 0;
        }
        int minY = Math.max(level.getMinBuildHeight(), sourceY - 8);
        int maxY = Math.min(level.getMaxBuildHeight() - 1, sourceY + 4);
        int bestAmount = 0;
        for (int y = maxY; y >= minY; y--) {
            int amount = WPOFluidAccess.getWaterAmount(level, new BlockPos(column.getX(), y, column.getZ()));
            if (amount > bestAmount) {
                bestAmount = amount;
                if (bestAmount >= WPOConfig.MAX_FLUID_LEVEL) {
                    return bestAmount;
                }
            }
        }
        return bestAmount;
    }

    private static int localWaterMass(ServerLevel level, BlockPos center, int sourceY) {
        int mass = 0;
        for (int dz = -2; dz <= 2; dz++) {
            for (int dx = -2; dx <= 2; dx++) {
                mass += nearbyWaterAmount(level, center.offset(dx, 0, dz), sourceY);
            }
        }
        return mass;
    }

    private static double currentStrength(
            ServerLevel level,
            RiverSavedData data,
            long chunkKey,
            BlockPos source,
            int sourceAmount,
            FlowDirective flow
    ) {
        double base = flow.source().contains("flat")
                ? RiverConfig.COMMON.flatCurrentStrength.get()
                : flow.source().contains("osm") ? 0.45D : flow.source().contains("channel") ? 0.42D : flow.source().contains("terrain") ? 0.32D : 0.22D;

        if (Double.isFinite(flow.speed())) {
            base = RiverHydraulics.biasStrength(
                    flow.speed(),
                    RiverConfig.COMMON.minCurrentStrength.get(),
                    RiverConfig.COMMON.maxCurrentStrength.get());
        }

        int headDrop = absoluteHead(source, sourceAmount) - approximateHead(level, flow.target());
        double slopeBoost = Math.max(0.0D, Math.min(0.45D, headDrop * 0.04D));
        double depthBoost = Math.max(0.0D, Math.min(0.15D, sourceAmount / (double) WPOConfig.MAX_FLUID_LEVEL * 0.15D));
        double dischargeBoost = Math.max(0.0D, Math.min(0.35D, data.getReservoirLevels(chunkKey) / 256.0D));
        double rainBoost = level.isRainingAt(source.above()) ? RiverConfig.COMMON.rainCurrentBoost.get() : 0.0D;

        return Mth.clamp(
                base + slopeBoost + depthBoost + dischargeBoost + rainBoost,
                RiverConfig.COMMON.minCurrentStrength.get(),
                RiverConfig.COMMON.maxCurrentStrength.get()
        );
    }

    private static void maybeSpawnCurrentParticle(
            ServerLevel level,
            BlockPos pos,
            RiverCurrentField.Flow flow,
            double strength
    ) {
        if (!RiverConfig.COMMON.currentParticles.get() || strength < 0.2D) {
            return;
        }

        double physicalSpeed = flow.speed();
        double normalizedSpeed = Mth.clamp(
                (physicalSpeed - RiverHydraulics.MIN_SPEED)
                        / (RiverHydraulics.MAX_SPEED - RiverHydraulics.MIN_SPEED),
                0.0D, 1.0D);
        int chance = Math.max(3, 10 - Mth.floor(normalizedSpeed * 7.0D));
        if (level.getRandom().nextInt(chance) != 0) {
            return;
        }
        double length = Math.hypot(flow.x(), flow.z());
        if (length < 1.0E-6D) {
            return;
        }
        double vx = flow.x() / length;
        double vz = flow.z() / length;
        double particleSpeed = 0.02D + normalizedSpeed * 0.07D;
        double y = pos.getY() + Math.min(0.9D, 0.15D + WPOFluidAccess.getWaterAmount(level, pos) / (double) WPOConfig.MAX_FLUID_LEVEL * 0.7D);
        double sideways = (level.getRandom().nextDouble() - 0.5D) * 0.5D;
        double x = pos.getX() + 0.5D - vx * 0.25D - vz * sideways;
        double z = pos.getZ() + 0.5D - vz * 0.25D + vx * sideways;
        level.sendParticles(
                ParticleTypes.BUBBLE,
                x,
                y,
                z,
                0,
                vx,
                0.0D,
                vz,
                particleSpeed
        );
        if (normalizedSpeed > 0.65D && level.getRandom().nextInt(4) == 0) {
            level.sendParticles(
                    ParticleTypes.SPLASH,
                    x,
                    pos.getY() + 1.0D,
                    z,
                    0,
                    vx,
                    0.04D,
                    vz,
                    particleSpeed
            );
        }
    }

    private static void feedInfiniteSource(ServerLevel level, BlockPos pos, boolean infiniteSource) {
        if (!infiniteSource) {
            return;
        }

        int current = WPOFluidAccess.getWaterAmount(level, pos);
        int target = RiverConfig.COMMON.targetRiverLevel.get();
        if (current >= target) {
            return;
        }

        WPOFluidAccess.addWater(level, pos, Math.min(RiverConfig.COMMON.sourceRefillLevels.get(), target - current));
    }

    private static boolean isSourceStart(ServerLevel level, BlockPos pos, FlowDirective downstreamFlow) {
        Direction downstream = downstreamFlow.direction();
        for (Direction direction : Direction.Plane.HORIZONTAL) {
            BlockPos neighbor = downstreamTarget(level, pos, direction, false);
            if (neighbor == null || neighbor.equals(downstreamFlow.target())) {
                continue;
            }

            int amount = WPOFluidAccess.getWaterAmount(level, neighbor);
            if (amount <= 0) {
                continue;
            }

            Optional<FlowDirective> neighborFlow = resolveFlow(level, neighbor, amount);
            if (neighborFlow.isPresent() && neighborFlow.get().target().equals(pos)) {
                return false;
            }

            if (direction == downstream.getOpposite() && sameOrHigherWaterHead(pos, neighbor, amount)) {
                return false;
            }
        }
        return true;
    }

    private static boolean sameOrHigherWaterHead(BlockPos source, BlockPos neighbor, int neighborAmount) {
        return absoluteHead(neighbor, neighborAmount) >= absoluteHead(source, 0);
    }

    private static void addRunoffBudget(
            ServerLevel level,
            RiverSavedData data,
            long chunkKey,
            BlockPos pos,
            TerrariumBridge.TerrariumSample terrarium
    ) {
        int rainfall = terrarium != null ? terrarium.rainfallMmPerYear() : Mth.floor(RiverConfig.COMMON.fallbackRainfallMmPerYear.get());
        double horizontalMetersPerBlock = TerrariumBridge.horizontalMetersPerBlock(level);
        double verticalMetersPerBlock = RiverConfig.COMMON.verticalMetersPerBlock.get();
        double volumePerLevel = Math.max(0.000001D, horizontalMetersPerBlock * horizontalMetersPerBlock * verticalMetersPerBlock / WPOConfig.MAX_FLUID_LEVEL);

        double catchmentArea = RiverConfig.COMMON.fallbackCatchmentAreaM2.get();
        double runoffCoefficient = RiverConfig.COMMON.runoffCoefficient.get();
        double annualRunoffM3 = Math.max(0, rainfall) / 1000.0D * catchmentArea * runoffCoefficient;
        double updatesPerHydrologyYear = Math.max(1.0D,
                RiverConfig.COMMON.hydrologyYearMinecraftDays.get() * 24000.0D / RiverConfig.COMMON.updateInterval.get());

        double levels = annualRunoffM3 / volumePerLevel / updatesPerHydrologyYear;
        if (RiverConfig.COMMON.actualRainRunoff.get() && level.isRainingAt(pos.above())) {
            double updatesPerMinecraftDay = Math.max(1.0D, 24000.0D / RiverConfig.COMMON.updateInterval.get());
            double stormRunoffM3 = RiverConfig.COMMON.stormRainfallMmPerMinecraftDay.get() / 1000.0D
                    * catchmentArea
                    * runoffCoefficient
                    / updatesPerMinecraftDay;
            levels += stormRunoffM3 / volumePerLevel;
        }

        levels = Math.min(64.0D, levels);
        if (!(levels > 0.0D)) {
            return;
        }
        data.addReservoirLevels(chunkKey, levels, RiverConfig.COMMON.maxReservoirLevelsPerChunk.get());
    }

    private static void feedVisibleRiver(ServerLevel level, RiverSavedData data, long chunkKey, BlockPos pos) {
        int current = WPOFluidAccess.getWaterAmount(level, pos);
        int target = RiverConfig.COMMON.targetRiverLevel.get();
        if (current >= target) {
            return;
        }
        int request = data.drainWholeLevels(chunkKey, Math.min(RiverConfig.COMMON.maxTransferLevels.get(), target - current));
        if (request <= 0) {
            return;
        }

        int before = WPOFluidAccess.getWaterAmount(level, pos);
        int after = WPOFluidAccess.addWater(level, pos, request);
        int accepted = Math.max(0, after - before);
        if (accepted < request) {
            data.addReservoirLevels(chunkKey, request - accepted, RiverConfig.COMMON.maxReservoirLevelsPerChunk.get());
        }
    }

    private static void maintainRiverMinimum(ServerLevel level, RiverSavedData data, long chunkKey, BlockPos pos, boolean riverColumn) {
        if (!RiverConfig.COMMON.physicalWaterFlow.get() || !riverColumn) {
            return;
        }

        int minimum = Math.min(RiverConfig.COMMON.minimumRiverLevel.get(), RiverConfig.COMMON.targetRiverLevel.get());
        int current = WPOFluidAccess.getWaterAmount(level, pos);
        if (current >= minimum) {
            return;
        }

        int request = data.drainWholeLevels(chunkKey, minimum - current);
        if (request > 0) {
            WPOFluidAccess.addWater(level, pos, request);
        }
    }

    // WPO's own fluid engine now moves water between loaded cells on its own (FFluidDefault/
    // FFluidEQ consult FlowBias for the current direction - see RiverDynamics' registration).
    // This is only responsible for the case WPO can't handle: draining into virtual reservoir
    // storage when the downstream cell is in a chunk that isn't loaded to simulate.
    private static void moveDownstream(ServerLevel level, RiverSavedData data, BlockPos source, FlowDirective flow, boolean riverColumn) {
        if (!riverColumn || !RiverConfig.COMMON.drainAtUnloadedDownstreamEdge.get()) {
            return;
        }
        BlockPos target = flow.target();
        if (WPOFluidAccess.isChunkLoaded(level, target)) {
            return;
        }

        int sourceAmount = WPOFluidAccess.getWaterAmount(level, source);
        int retainedLevels = retainedLevels(flow, riverColumn);
        int movable = Math.min(RiverConfig.COMMON.maxTransferLevels.get(), Math.max(0, sourceAmount - retainedLevels));
        if (movable <= 0) {
            return;
        }

        int removed = removeWaterKeeping(level, source, movable, retainedLevels);
        if (removed > 0) {
            long downstreamChunk = ChunkPos.asLong(target.getX() >> 4, target.getZ() >> 4);
            data.addReservoirLevels(downstreamChunk, removed, RiverConfig.COMMON.maxReservoirLevelsPerChunk.get());
        }
    }

    private static int retainedLevels(FlowDirective flow, boolean riverColumn) {
        if (!riverColumn || !RiverConfig.COMMON.physicalWaterFlow.get()) {
            return flow.source().contains("magnet") ? 0 : 1;
        }
        return Math.min(RiverConfig.COMMON.minimumRiverLevel.get(), RiverConfig.COMMON.targetRiverLevel.get());
    }

    private static int removeWaterKeeping(ServerLevel level, BlockPos pos, int requested, int retainedLevels) {
        int current = WPOFluidAccess.getWaterAmount(level, pos);
        int removable = Math.min(Math.max(0, requested), Math.max(0, current - retainedLevels));
        if (removable <= 0) {
            return 0;
        }

        int after = WPOFluidAccess.removeWater(level, pos, removable);
        int removed = Math.max(0, current - after);
        if (removed > 0) {
            wakeWater(level, pos);
        }
        return removed;
    }

    private static void wakeWater(ServerLevel level, BlockPos pos) {
        if (WPOFluidAccess.isChunkLoaded(level, pos)) {
            WPOFluidAccess.wakeWater(level, pos);
        }
    }

    private static BlockPos downstreamTarget(ServerLevel level, BlockPos source, Direction direction, boolean allowDryTarget) {
        BlockPos column = source.relative(direction);
        if (!WPOFluidAccess.isChunkLoaded(level, column)) {
            return column;
        }

        BlockPos surfaceWater = findSurfaceWater(level, column.getX(), column.getZ());
        if (surfaceWater != null && Math.abs(surfaceWater.getY() - source.getY()) <= 8) {
            return surfaceWater;
        }

        for (int dy = -4; dy <= 4; dy++) {
            BlockPos candidate = column.offset(0, dy, 0);
            if (candidate.getY() < level.getMinBuildHeight() || candidate.getY() >= level.getMaxBuildHeight()) {
                continue;
            }
            if (WPOFluidAccess.getWaterAmount(level, candidate) > 0) {
                return candidate;
            }
        }

        return allowDryTarget ? column : null;
    }

    private static BlockPos findSurfaceWater(ServerLevel level, int x, int z) {
        BlockPos chunkCheck = new BlockPos(x, level.getMinBuildHeight(), z);
        if (!level.hasChunkAt(chunkCheck)) {
            return null;
        }

        int top = level.getHeight(Heightmap.Types.WORLD_SURFACE, x, z);
        int minY = Math.max(level.getMinBuildHeight(), top - RiverConfig.COMMON.surfaceSearchDepth.get());
        return surfaceWaterInRange(level, x, z, Math.min(level.getMaxBuildHeight() - 1, top), minY);
    }

    private static BlockPos nearestSurfaceWater(ServerLevel level, BlockPos origin, int radius) {
        BlockPos best = findSurfaceWater(level, origin.getX(), origin.getZ());
        if (best != null && Math.abs(best.getY() - origin.getY()) <= RiverConfig.COMMON.surfaceSearchDepth.get()) {
            return best;
        }

        for (int r = 1; r <= radius; r++) {
            for (int dz = -r; dz <= r; dz++) {
                for (int dx = -r; dx <= r; dx++) {
                    if (Math.abs(dx) != r && Math.abs(dz) != r) {
                        continue;
                    }
                    BlockPos candidate = surfaceWaterNear(level, origin.getX() + dx, origin.getZ() + dz, origin.getY(), RiverConfig.COMMON.surfaceSearchDepth.get());
                    if (candidate != null) {
                        return candidate;
                    }
                }
            }
        }
        return null;
    }

    static BlockPos surfaceWaterNear(ServerLevel level, int x, int z, int centerY, int verticalRadius) {
        if (!level.hasChunkAt(new BlockPos(x, level.getMinBuildHeight(), z))) {
            return null;
        }
        int maxY = Math.min(level.getMaxBuildHeight() - 1, centerY + verticalRadius);
        int minY = Math.max(level.getMinBuildHeight(), centerY - verticalRadius);
        return surfaceWaterInRange(level, x, z, maxY, minY);
    }

    private static BlockPos surfaceWaterInRange(ServerLevel level, int x, int z, int maxY, int minY) {
        for (int y = maxY; y >= minY; y--) {
            BlockPos pos = new BlockPos(x, y, z);
            if (!isSurfaceWater(level, pos)) {
                continue;
            }
            return pos;
        }
        return null;
    }

    private static boolean isSurfaceWater(ServerLevel level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        if (!state.getFluidState().getType().isSame(Fluids.WATER)) {
            return false;
        }
        BlockState above = level.getBlockState(pos.above());
        return !above.getFluidState().getType().isSame(Fluids.WATER);
    }

    private static int absoluteHead(BlockPos pos, int amount) {
        return (pos.getY() * WPOConfig.MAX_FLUID_LEVEL) + amount;
    }

    private static BlockPos sampleColumn(BlockPos center, int radius, long cursor) {
        int width = (radius * 2) + 1;
        long mixed = mix(cursor);
        int offsetX = (int) Math.floorMod(mixed, width) - radius;
        int offsetZ = (int) Math.floorMod(mixed >>> 32, width) - radius;
        return center.offset(offsetX, 0, offsetZ);
    }

    private static long mix(long value) {
        long mixed = value + 0x9E3779B97F4A7C15L;
        mixed ^= mixed >>> 30;
        mixed *= 0xBF58476D1CE4E5B9L;
        mixed ^= mixed >>> 27;
        mixed *= 0x94D049BB133111EBL;
        mixed ^= mixed >>> 31;
        return mixed;
    }

    private static String format(BlockPos pos) {
        return pos.getX() + "," + pos.getY() + "," + pos.getZ();
    }

    private record AttractionVector(Direction direction, double strength) {
    }

    private record FlatNode(BlockPos pos, int distance, Direction firstDirection) {
    }

    private record AxisCandidate(Direction negative, Direction positive, int score, int negativeRun, int positiveRun) {
    }
}
