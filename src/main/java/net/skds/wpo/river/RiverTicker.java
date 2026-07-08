package net.skds.wpo.river;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
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
                + " flow=" + flow.map(f -> f.source() + " " + f.direction() + " -> " + format(f.target())).orElse("none")
                + " current=" + current.map(c -> c.source() + " " + c.direction() + " strength=" + String.format(java.util.Locale.ROOT, "%.2f", c.strength())).orElse("none")
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

        FlowDirective flow = resolveFlow(level, waterPos, amount).orElse(null);
        if (flow == null) {
            RiverCurrentField.clear(level, waterPos);
            return;
        }

        TerrariumBridge.TerrariumSample terrarium = allowHydrologyFeed ? TerrariumBridge.sample(level, waterPos).orElse(null) : null;
        boolean explicitMappedRiver = "osm".equals(flow.source());
        boolean generatedRiver = isGeneratedRiver(level, waterPos);
        if (shouldSkipFallbackRiver(generatedRiver, explicitMappedRiver)) {
            RiverCurrentField.clear(level, waterPos);
            return;
        }
        if (!explicitMappedRiver && terrarium != null && terrarium.elevationMeters() <= 0) {
            return;
        }

        long chunkKey = chunkPos.toLong();
        boolean riverColumn = generatedRiver || explicitMappedRiver || isInferredRiverFlow(flow);
        boolean infiniteSource = allowHydrologyFeed
                && riverColumn
                && RiverConfig.COMMON.infiniteRiverSources.get()
                && isSourceStart(level, waterPos, flow);
        if (allowHydrologyFeed) {
            addRunoffBudget(level, data, chunkKey, waterPos, terrarium);
            feedVisibleRiver(level, data, chunkKey, waterPos);
        }
        double currentStrength = currentStrength(level, data, chunkKey, waterPos, amount, flow);
        RiverCurrentField.publishColumn(level, waterPos, flow, currentStrength);
        maybeSpawnCurrentParticle(level, waterPos, flow, currentStrength);
        moveDownstream(level, data, waterPos, flow, riverColumn);
        if (allowHydrologyFeed) {
            maintainRiverMinimum(level, data, chunkKey, waterPos, riverColumn);
            feedInfiniteSource(level, waterPos, infiniteSource);
        }
    }

    private static Optional<FlowDirective> resolveFlow(ServerLevel level, BlockPos waterPos, int amount) {
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
                    FlowDirective flow = new FlowDirective(osm.get().direction(), target, "osm");
                    return Optional.of(applyWaterBodyMagnetism(level, waterPos, amount, flow));
                }
            }
        }

        if (!RiverConfig.COMMON.terrainFallbackDirections.get() && !RiverConfig.COMMON.flatOutletSolver.get()) {
            return Optional.empty();
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
            Optional<FlowDirective> flat = resolveFlatOutletFlow(level, waterPos, amount);
            if (flat.isPresent()) {
                return Optional.of(applyWaterBodyMagnetism(level, waterPos, amount, flat.get()));
            }
            Optional<FlowDirective> channel = resolveChannelAxisFlow(level, waterPos, amount);
            if (channel.isPresent()) {
                return Optional.of(applyWaterBodyMagnetism(level, waterPos, amount, channel.get()));
            }
            return resolveMagnetOnlyFlow(level, waterPos, amount);
        }
        FlowDirective flow = new FlowDirective(bestDirection, bestTarget, "terrain");
        return Optional.of(applyWaterBodyMagnetism(level, waterPos, amount, flow));
    }

    private static Optional<FlowDirective> resolveChannelAxisFlow(ServerLevel level, BlockPos source, int sourceAmount) {
        if (!RiverConfig.COMMON.channelAxisFallback.get()) {
            return Optional.empty();
        }

        int maxDistance = RiverConfig.COMMON.channelAxisScanDistance.get();
        AxisCandidate northSouth = axisCandidate(level, source, Direction.NORTH, Direction.SOUTH, maxDistance);
        AxisCandidate westEast = axisCandidate(level, source, Direction.WEST, Direction.EAST, maxDistance);
        AxisCandidate best = northSouth.score() >= westEast.score() ? northSouth : westEast;
        if (best.score() < 10) {
            return Optional.empty();
        }

        Direction downhill = chooseAxisDirection(level, source, sourceAmount, best.negative(), best.positive(), maxDistance);
        BlockPos target = downstreamTarget(level, source, downhill, true);
        if (target == null) {
            return Optional.empty();
        }
        return Optional.of(new FlowDirective(downhill, target, "channel"));
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

    private static Direction chooseAxisDirection(ServerLevel level, BlockPos source, int sourceAmount, Direction a, Direction b, int maxDistance) {
        double scoreA = channelOutletScore(level, source, sourceAmount, a, maxDistance);
        double scoreB = channelOutletScore(level, source, sourceAmount, b, maxDistance);
        return scoreA >= scoreB ? a : b;
    }

    private static double channelOutletScore(ServerLevel level, BlockPos source, int sourceAmount, Direction direction, int maxDistance) {
        BlockPos end = source;
        int run = 0;
        for (int i = 0; i < maxDistance; i++) {
            BlockPos nextColumn = end.relative(direction);
            BlockPos next = surfaceWaterNear(level, nextColumn.getX(), nextColumn.getZ(), source.getY(), 4);
            if (next == null) {
                break;
            }
            end = next;
            run++;
        }

        int headDrop = absoluteHead(source, sourceAmount) - approximateHead(level, end);
        int terrainDrop = level.getHeight(Heightmap.Types.WORLD_SURFACE, source.getX(), source.getZ())
                - level.getHeight(Heightmap.Types.WORLD_SURFACE, end.getX(), end.getZ());
        return (headDrop * 4.0D) + (terrainDrop * 2.0D) + run;
    }

    private static Optional<FlowDirective> resolveFlatOutletFlow(ServerLevel level, BlockPos source, int sourceAmount) {
        if (!RiverConfig.COMMON.flatOutletSolver.get()) {
            return Optional.empty();
        }

        int sourceHead = absoluteHead(source, sourceAmount);
        int maxCells = RiverConfig.COMMON.flatSolverMaxCells.get();
        int flatTolerance = RiverConfig.COMMON.flatHeadTolerance.get();
        int minOutletDrop = RiverConfig.COMMON.flatMinOutletDrop.get();

        ArrayDeque<FlatNode> queue = new ArrayDeque<>();
        Set<Long> visited = new HashSet<>();
        queue.add(new FlatNode(source, null, 0));
        visited.add(source.asLong());

        Direction bestFirstDirection = null;
        double bestScore = Double.NEGATIVE_INFINITY;

        while (!queue.isEmpty() && visited.size() <= maxCells) {
            FlatNode node = queue.removeFirst();
            int nodeAmount = WPOFluidAccess.getWaterAmount(level, node.pos());
            if (nodeAmount <= 0) {
                continue;
            }
            int nodeHead = absoluteHead(node.pos(), nodeAmount);

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
                Direction firstDirection = node.firstDirection() == null ? direction : node.firstDirection();
                if (drop >= minOutletDrop) {
                    double score = (drop * 12.0D)
                            + Math.max(0, node.pos().getY() - target.getY()) * 24.0D
                            - node.distance();
                    if (score > bestScore) {
                        bestScore = score;
                        bestFirstDirection = firstDirection;
                    }
                    continue;
                }

                if (Math.abs(targetHead - sourceHead) > flatTolerance || Math.abs(targetHead - nodeHead) > flatTolerance) {
                    continue;
                }

                long key = target.asLong();
                if (visited.add(key)) {
                    queue.addLast(new FlatNode(target.immutable(), firstDirection, node.distance() + 1));
                }
            }
        }

        if (bestFirstDirection == null) {
            return Optional.empty();
        }

        BlockPos target = downstreamTarget(level, source, bestFirstDirection, false);
        if (target == null) {
            return Optional.empty();
        }
        return Optional.of(new FlowDirective(bestFirstDirection, target, "flat"));
    }

    private static Optional<FlowDirective> resolveMagnetOnlyFlow(ServerLevel level, BlockPos source, int sourceAmount) {
        if (!RiverConfig.COMMON.waterBodyMagnetism.get()) {
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
                || flow.source().contains("channel");
    }

    private static boolean isGeneratedRiver(ServerLevel level, BlockPos pos) {
        return RiverConfig.COMMON.generatedRiverReplacement.get()
                && level.getBiome(pos).is(BiomeTags.IS_RIVER);
    }

    private static FlowDirective applyWaterBodyMagnetism(ServerLevel level, BlockPos source, int sourceAmount, FlowDirective original) {
        if (!RiverConfig.COMMON.waterBodyMagnetism.get()) {
            return original;
        }

        AttractionVector attraction = waterBodyAttractionVector(level, source);
        Direction attractedDirection = attraction.direction();
        if (attractedDirection == null || attraction.strength() < 6.0D) {
            return original;
        }

        Direction bestDirection = original.direction();
        BlockPos bestTarget = original.target();
        double bestScore = scoreMagnetCandidate(level, source, sourceAmount, original.direction(), original.direction(), bestTarget, attraction);

        for (Direction direction : Direction.Plane.HORIZONTAL) {
            BlockPos target = magneticTarget(level, source, direction);
            if (target == null) {
                continue;
            }

            double score = scoreMagnetCandidate(level, source, sourceAmount, direction, original.direction(), target, attraction);
            if (score > bestScore + 0.25D) {
                bestDirection = direction;
                bestTarget = target;
                bestScore = score;
            }
        }

        if (bestDirection == original.direction() && bestTarget.equals(original.target())) {
            return original;
        }
        return new FlowDirective(bestDirection, bestTarget, original.source() + "+magnet");
    }

    private static double scoreMagnetCandidate(
            ServerLevel level,
            BlockPos source,
            int sourceAmount,
            Direction candidate,
            Direction original,
            BlockPos target,
            AttractionVector attractionVector
    ) {
        int sourceHead = absoluteHead(source, sourceAmount);
        int targetHead = approximateHead(level, target);
        int headDrop = sourceHead - targetHead;
        if (headDrop < -RiverConfig.COMMON.magnetMaxUphillHead.get()) {
            return -1_000_000.0D;
        }

        double continuity = directionDot(candidate, original) * 4.0D;
        double gravity = Math.max(-4.0D, Math.min(10.0D, headDrop));
        gravity += Math.max(0, source.getY() - target.getY()) * 6.0D;
        double attraction = waterBodyAttractionScore(attractionVector, candidate);
        return continuity + gravity + attraction;
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

    private static double directionDot(Direction a, Direction b) {
        int ax = a.getNormal().getX();
        int az = a.getNormal().getZ();
        int bx = b.getNormal().getX();
        int bz = b.getNormal().getZ();
        return (ax * bx) + (az * bz);
    }

    private static double waterBodyAttractionScore(AttractionVector vector, Direction candidate) {
        if (vector.direction() == null || vector.strength() <= 0.0D) {
            return 0.0D;
        }
        double alignment = directionDot(candidate, vector.direction());
        return alignment > 0.0D ? Math.min(20.0D, vector.strength()) * 0.3D * alignment : 0.0D;
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

    private static void maybeSpawnCurrentParticle(ServerLevel level, BlockPos pos, FlowDirective flow, double strength) {
        if (!RiverConfig.COMMON.currentParticles.get() || strength < 0.2D || level.getRandom().nextInt(2) != 0) {
            return;
        }

        double speed = Math.min(0.08D, 0.025D + strength * 0.025D);
        double y = pos.getY() + Math.min(0.9D, 0.15D + WPOFluidAccess.getWaterAmount(level, pos) / (double) WPOConfig.MAX_FLUID_LEVEL * 0.7D);
        for (int i = 0; i < 3; i++) {
            double sideways = (i - 1) * 0.18D;
            double x = pos.getX() + 0.5D - flow.direction().getStepX() * 0.25D + flow.direction().getStepZ() * sideways;
            double z = pos.getZ() + 0.5D - flow.direction().getStepZ() * 0.25D - flow.direction().getStepX() * sideways;
            level.sendParticles(
                    ParticleTypes.BUBBLE,
                    x,
                    y,
                    z,
                    0,
                    flow.direction().getStepX(),
                    0.0D,
                    flow.direction().getStepZ(),
                    speed
            );
        }
        level.sendParticles(
                ParticleTypes.SPLASH,
                pos.getX() + 0.5D - flow.direction().getStepX() * 0.25D,
                pos.getY() + 1.0D,
                pos.getZ() + 0.5D - flow.direction().getStepZ() * 0.25D,
                1,
                flow.direction().getStepX() * 0.15D,
                0.0D,
                flow.direction().getStepZ() * 0.15D,
                speed
        );
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

    private static void moveDownstream(ServerLevel level, RiverSavedData data, BlockPos source, FlowDirective flow, boolean riverColumn) {
        BlockPos target = flow.target();
        int sourceAmount = WPOFluidAccess.getWaterAmount(level, source);
        int retainedLevels = retainedLevels(flow, riverColumn);
        int movable = Math.min(RiverConfig.COMMON.maxTransferLevels.get(), Math.max(0, sourceAmount - retainedLevels));
        if (movable <= 0) {
            return;
        }

        if (RiverConfig.COMMON.physicalWaterFlow.get() && riverColumn) {
            if (!moveOneStep(level, data, source, target, movable, retainedLevels)) {
                pumpFullRiverPath(level, data, source, flow, movable, retainedLevels);
            }
            return;
        }

        moveOneStep(level, data, source, target, movable, retainedLevels);
    }

    private static int retainedLevels(FlowDirective flow, boolean riverColumn) {
        if (!riverColumn || !RiverConfig.COMMON.physicalWaterFlow.get()) {
            return flow.source().contains("magnet") ? 0 : 1;
        }
        return Math.min(RiverConfig.COMMON.minimumRiverLevel.get(), RiverConfig.COMMON.targetRiverLevel.get());
    }

    private static boolean moveOneStep(
            ServerLevel level,
            RiverSavedData data,
            BlockPos source,
            BlockPos target,
            int movable,
            int retainedLevels
    ) {
        if (!WPOFluidAccess.isChunkLoaded(level, target)) {
            if (!RiverConfig.COMMON.drainAtUnloadedDownstreamEdge.get()) {
                return false;
            }
            int removed = removeWaterKeeping(level, source, movable, retainedLevels);
            if (removed > 0) {
                long downstreamChunk = ChunkPos.asLong(target.getX() >> 4, target.getZ() >> 4);
                data.addReservoirLevels(downstreamChunk, removed, RiverConfig.COMMON.maxReservoirLevelsPerChunk.get());
            }
            return removed > 0;
        }

        if (!WPOFluidAccess.canWaterFlowBetween(level, source, target)) {
            return false;
        }
        int targetAmount = WPOFluidAccess.getWaterAmount(level, target);
        int capacity = WPOConfig.MAX_FLUID_LEVEL - targetAmount;
        int requested = Math.min(movable, capacity);
        if (requested <= 0) {
            return false;
        }

        return transferWater(level, source, target, requested, retainedLevels) > 0;
    }

    private static boolean pumpFullRiverPath(
            ServerLevel level,
            RiverSavedData data,
            BlockPos source,
            FlowDirective firstFlow,
            int requested,
            int retainedLevels
    ) {
        List<BlockPos> path = new ArrayList<>();
        path.add(source.immutable());

        BlockPos current = source;
        FlowDirective flow = firstFlow;
        int maxDistance = RiverConfig.COMMON.physicalFlowChainDistance.get();
        for (int step = 0; step < maxDistance; step++) {
            BlockPos target = flow.target().immutable();
            if (path.contains(target)) {
                return false;
            }

            if (!WPOFluidAccess.isChunkLoaded(level, target)) {
                return drainTailToUnloaded(level, data, path, target, requested, retainedLevels);
            }
            if (!WPOFluidAccess.canWaterFlowBetween(level, current, target)) {
                return false;
            }

            path.add(target);
            int targetAmount = WPOFluidAccess.getWaterAmount(level, target);
            int capacity = WPOConfig.MAX_FLUID_LEVEL - targetAmount;
            if (capacity > 0) {
                return shiftPath(level, path, Math.min(requested, capacity), retainedLevels);
            }

            Optional<FlowDirective> nextFlow = resolveFlow(level, target, targetAmount);
            if (nextFlow.isEmpty()) {
                return drainTailToReservoir(level, data, path, requested, retainedLevels);
            }
            current = target;
            flow = nextFlow.get();
        }

        return drainTailToReservoir(level, data, path, requested, retainedLevels);
    }

    private static boolean drainTailToUnloaded(
            ServerLevel level,
            RiverSavedData data,
            List<BlockPos> path,
            BlockPos unloadedTarget,
            int requested,
            int retainedLevels
    ) {
        if (path.size() <= 1 || !RiverConfig.COMMON.drainAtUnloadedDownstreamEdge.get()) {
            return false;
        }

        BlockPos tail = path.get(path.size() - 1);
        int removed = removeWaterKeeping(level, tail, requested, RiverConfig.COMMON.minimumRiverLevel.get());
        if (removed <= 0) {
            return false;
        }

        long downstreamChunk = ChunkPos.asLong(unloadedTarget.getX() >> 4, unloadedTarget.getZ() >> 4);
        data.addReservoirLevels(downstreamChunk, removed, RiverConfig.COMMON.maxReservoirLevelsPerChunk.get());
        return shiftPath(level, path, removed, retainedLevels);
    }

    private static boolean drainTailToReservoir(
            ServerLevel level,
            RiverSavedData data,
            List<BlockPos> path,
            int requested,
            int retainedLevels
    ) {
        if (path.size() <= 2) {
            return false;
        }

        BlockPos tail = path.get(path.size() - 1);
        int removed = removeWaterKeeping(level, tail, requested, RiverConfig.COMMON.minimumRiverLevel.get());
        if (removed <= 0) {
            return false;
        }

        data.addReservoirLevels(new ChunkPos(tail).toLong(), removed, RiverConfig.COMMON.maxReservoirLevelsPerChunk.get());
        return shiftPath(level, path, removed, retainedLevels);
    }

    private static boolean shiftPath(ServerLevel level, List<BlockPos> path, int requested, int sourceRetainedLevels) {
        int shiftedFromSource = 0;
        int amount = requested;
        for (int i = path.size() - 2; i >= 0 && amount > 0; i--) {
            BlockPos from = path.get(i);
            BlockPos to = path.get(i + 1);
            int retained = i == 0 ? sourceRetainedLevels : RiverConfig.COMMON.minimumRiverLevel.get();
            amount = transferWater(level, from, to, amount, retained);
            if (i == 0) {
                shiftedFromSource = amount;
            }
        }
        return shiftedFromSource > 0;
    }

    private static int transferWater(ServerLevel level, BlockPos source, BlockPos target, int requested, int retainedLevels) {
        int targetCapacity = WPOConfig.MAX_FLUID_LEVEL - WPOFluidAccess.getWaterAmount(level, target);
        int removed = removeWaterKeeping(level, source, Math.min(requested, targetCapacity), retainedLevels);
        if (removed <= 0) {
            return 0;
        }

        int beforeTarget = WPOFluidAccess.getWaterAmount(level, target);
        int afterTarget = WPOFluidAccess.addWater(level, target, removed);
        int accepted = Math.max(0, afterTarget - beforeTarget);
        if (accepted < removed) {
            WPOFluidAccess.addWater(level, source, removed - accepted);
        }
        wakeWater(level, source);
        wakeWater(level, target);
        return accepted;
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

    private static BlockPos surfaceWaterNear(ServerLevel level, int x, int z, int centerY, int verticalRadius) {
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

    private record FlatNode(BlockPos pos, Direction firstDirection, int distance) {
    }

    private record AxisCandidate(Direction negative, Direction positive, int score, int negativeRun, int positiveRun) {
    }
}
