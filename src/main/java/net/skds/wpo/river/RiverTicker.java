package net.skds.wpo.river;

import java.util.Optional;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
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

        int budget = Math.max(1, level.players().size() * RiverConfig.COMMON.columnChecksPerPlayer.get());
        long cursor = data.nextCursor(budget);
        int radius = RiverConfig.COMMON.sampleRadius.get();
        for (int i = 0; i < budget; i++) {
            Player player = level.players().get((int) Math.floorMod(cursor + i, level.players().size()));
            BlockPos sample = sampleColumn(player.blockPosition(), radius, cursor + i);
            processColumn(level, sample.getX(), sample.getZ(), data);
        }
    }

    private static void processColumn(ServerLevel level, int x, int z, RiverSavedData data) {
        BlockPos waterPos = findSurfaceWater(level, x, z);
        if (waterPos == null) {
            return;
        }

        ChunkPos chunkPos = new ChunkPos(waterPos);
        data.markVisited(chunkPos, level.getGameTime());

        int amount = WPOFluidAccess.getWaterAmount(level, waterPos);
        if (amount <= 0) {
            return;
        }

        FlowDirective flow = resolveFlow(level, waterPos, amount).orElse(null);
        if (flow == null) {
            return;
        }

        TerrariumBridge.TerrariumSample terrarium = TerrariumBridge.sample(level, waterPos).orElse(null);
        boolean explicitMappedRiver = "osm".equals(flow.source());
        if (!explicitMappedRiver && terrarium != null && terrarium.elevationMeters() <= 0) {
            return;
        }

        long chunkKey = chunkPos.toLong();
        addRunoffBudget(level, data, chunkKey, terrarium);
        feedVisibleRiver(level, data, chunkKey, waterPos);
        moveDownstream(level, data, waterPos, flow.target());
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

        if (!RiverConfig.COMMON.terrainFallbackDirections.get()) {
            return Optional.empty();
        }

        int currentHead = absoluteHead(waterPos, amount);
        Direction bestDirection = null;
        BlockPos bestTarget = null;
        int bestDrop = 0;
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

        if (bestDirection == null || bestTarget == null) {
            return Optional.empty();
        }
        FlowDirective flow = new FlowDirective(bestDirection, bestTarget, "terrain");
        return Optional.of(applyWaterBodyMagnetism(level, waterPos, amount, flow));
    }

    private static FlowDirective applyWaterBodyMagnetism(ServerLevel level, BlockPos source, int sourceAmount, FlowDirective original) {
        if (!RiverConfig.COMMON.waterBodyMagnetism.get()) {
            return original;
        }

        AttractionVector attraction = waterBodyAttractionVector(level, source);
        Direction attractedDirection = attraction.direction();
        if (attractedDirection == null || attraction.strength() < 12.0D) {
            return original;
        }

        BlockPos attractedTarget = downstreamTarget(level, source, attractedDirection, true);
        if (attractedTarget != null
                && (!WPOFluidAccess.isChunkLoaded(level, attractedTarget)
                || WPOFluidAccess.canWaterFlowBetween(level, source, attractedTarget))) {
            int headDrop = absoluteHead(source, sourceAmount) - approximateHead(level, attractedTarget);
            if (headDrop >= -RiverConfig.COMMON.magnetMaxUphillHead.get()) {
                return new FlowDirective(attractedDirection, attractedTarget, original.source() + "+magnet");
            }
        }

        Direction bestDirection = original.direction();
        BlockPos bestTarget = original.target();
        double bestScore = scoreMagnetCandidate(level, source, sourceAmount, original.direction(), original.direction(), bestTarget);

        for (Direction direction : Direction.Plane.HORIZONTAL) {
            BlockPos target = downstreamTarget(level, source, direction, true);
            if (target == null) {
                continue;
            }
            if (WPOFluidAccess.isChunkLoaded(level, target) && !WPOFluidAccess.canWaterFlowBetween(level, source, target)) {
                continue;
            }

            double score = scoreMagnetCandidate(level, source, sourceAmount, direction, original.direction(), target);
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
            BlockPos target
    ) {
        int sourceHead = absoluteHead(source, sourceAmount);
        int targetHead = approximateHead(level, target);
        int headDrop = sourceHead - targetHead;
        if (headDrop < -RiverConfig.COMMON.magnetMaxUphillHead.get()) {
            return -1_000_000.0D;
        }

        double continuity = directionDot(candidate, original) * 0.5D;
        double gravity = Math.max(-4.0D, Math.min(8.0D, headDrop));
        double attraction = waterBodyAttractionScore(level, source, candidate);
        return continuity + gravity + attraction;
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

    private static double waterBodyAttractionScore(ServerLevel level, BlockPos source, Direction candidate) {
        AttractionVector vector = waterBodyAttractionVector(level, source);
        if (vector.direction() == null || vector.strength() <= 0.0D) {
            return 0.0D;
        }
        double alignment = directionDot(candidate, vector.direction());
        return alignment > 0.0D ? Math.min(80.0D, vector.strength()) * alignment : 0.0D;
    }

    private static AttractionVector waterBodyAttractionVector(ServerLevel level, BlockPos source) {
        int radius = RiverConfig.COMMON.waterBodyMagnetRadius.get();
        int step = radius > 18 ? 2 : 1;
        double vx = 0.0D;
        double vz = 0.0D;
        double strength = 0.0D;

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
                double weight = localMass / distance;
                vx += (dx / distance) * weight;
                vz += (dz / distance) * weight;
                strength += weight;
            }
        }

        if (strength <= 0.0D || Math.abs(vx) + Math.abs(vz) < 0.001D) {
            return new AttractionVector(null, 0.0D);
        }
        Direction direction = Direction.getNearest((float) vx, 0.0F, (float) vz);
        return new AttractionVector(direction, strength);
    }

    private static int nearbyWaterAmount(ServerLevel level, BlockPos column, int sourceY) {
        if (!WPOFluidAccess.isChunkLoaded(level, column)) {
            return 0;
        }
        int minY = Math.max(level.getMinBuildHeight(), sourceY - 8);
        int maxY = Math.min(level.getMaxBuildHeight() - 1, sourceY + 4);
        for (int y = maxY; y >= minY; y--) {
            int amount = WPOFluidAccess.getWaterAmount(level, new BlockPos(column.getX(), y, column.getZ()));
            if (amount > 0) {
                return amount;
            }
        }
        return 0;
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

    private static void addRunoffBudget(
            ServerLevel level,
            RiverSavedData data,
            long chunkKey,
            TerrariumBridge.TerrariumSample terrarium
    ) {
        int rainfall = terrarium != null ? terrarium.rainfallMmPerYear() : Mth.floor(RiverConfig.COMMON.fallbackRainfallMmPerYear.get());
        if (rainfall <= 0) {
            return;
        }

        double horizontalMetersPerBlock = TerrariumBridge.horizontalMetersPerBlock(level);
        double verticalMetersPerBlock = RiverConfig.COMMON.verticalMetersPerBlock.get();
        double volumePerLevel = Math.max(0.000001D, horizontalMetersPerBlock * horizontalMetersPerBlock * verticalMetersPerBlock / WPOConfig.MAX_FLUID_LEVEL);
        double annualRunoffM3 = (rainfall / 1000.0D)
                * RiverConfig.COMMON.fallbackCatchmentAreaM2.get()
                * RiverConfig.COMMON.runoffCoefficient.get();
        double updatesPerHydrologyYear = Math.max(1.0D,
                RiverConfig.COMMON.hydrologyYearMinecraftDays.get() * 24000.0D / RiverConfig.COMMON.updateInterval.get());
        double levels = Math.min(64.0D, annualRunoffM3 / volumePerLevel / updatesPerHydrologyYear);
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

    private static void moveDownstream(ServerLevel level, RiverSavedData data, BlockPos source, BlockPos target) {
        int sourceAmount = WPOFluidAccess.getWaterAmount(level, source);
        int movable = Math.min(RiverConfig.COMMON.maxTransferLevels.get(), Math.max(0, sourceAmount - 1));
        if (movable <= 0) {
            return;
        }

        if (!WPOFluidAccess.isChunkLoaded(level, target)) {
            if (!RiverConfig.COMMON.drainAtUnloadedDownstreamEdge.get()) {
                return;
            }
            int before = WPOFluidAccess.getWaterAmount(level, source);
            int after = WPOFluidAccess.removeWater(level, source, movable);
            int removed = Math.max(0, before - after);
            if (removed > 0) {
                long downstreamChunk = ChunkPos.asLong(target.getX() >> 4, target.getZ() >> 4);
                data.addReservoirLevels(downstreamChunk, removed, RiverConfig.COMMON.maxReservoirLevelsPerChunk.get());
            }
            return;
        }

        int targetAmount = WPOFluidAccess.getWaterAmount(level, target);
        int capacity = WPOConfig.MAX_FLUID_LEVEL - targetAmount;
        int requested = Math.min(movable, capacity);
        if (requested <= 0) {
            return;
        }

        int beforeSource = WPOFluidAccess.getWaterAmount(level, source);
        int afterSource = WPOFluidAccess.removeWater(level, source, requested);
        int removed = Math.max(0, beforeSource - afterSource);
        if (removed <= 0) {
            return;
        }

        int beforeTarget = WPOFluidAccess.getWaterAmount(level, target);
        int afterTarget = WPOFluidAccess.addWater(level, target, removed);
        int accepted = Math.max(0, afterTarget - beforeTarget);
        if (accepted < removed) {
            WPOFluidAccess.addWater(level, source, removed - accepted);
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
        for (int y = Math.min(level.getMaxBuildHeight() - 1, top); y >= minY; y--) {
            BlockPos pos = new BlockPos(x, y, z);
            BlockState state = level.getBlockState(pos);
            if (state.getFluidState().getType().isSame(Fluids.WATER) && WPOFluidAccess.getWaterAmount(state.getFluidState()) > 0) {
                BlockState above = level.getBlockState(pos.above());
                if (!above.getFluidState().getType().isSame(Fluids.WATER)) {
                    return pos;
                }
            }
        }
        return null;
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

    private record AttractionVector(Direction direction, double strength) {
    }
}
