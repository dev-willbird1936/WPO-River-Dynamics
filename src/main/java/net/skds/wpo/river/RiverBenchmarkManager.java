package net.skds.wpo.river;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.RotatedPillarBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.skds.wpo.WPOConfig;
import net.skds.wpo.api.WPOFluidAccess;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public final class RiverBenchmarkManager {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final DateTimeFormatter FILE_STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");
    private static final Path OUTPUT_DIR = Paths.get(System.getProperty("user.dir"), "config", RiverDynamics.MOD_ID, "benchmarks");
    private static final int DURATION_TICKS = 800;
    private static final int SAMPLE_INTERVAL_TICKS = 20;
    private static final int CHAT_INTERVAL_TICKS = 200;
    private static final int CLEAR_X_RADIUS = 48;
    private static final int CLEAR_NEG_Z = 34;
    private static final int CLEAR_POS_Z = 42;
    private static final int CLEAR_HEIGHT = 18;
    private static final BlockState ARENA_STATE = Blocks.OAK_LOG.defaultBlockState()
        .setValue(RotatedPillarBlock.AXIS, Direction.Axis.Y);

    private static ActiveRun activeRun;
    private static Path lastOutputPath;

    private enum BenchmarkMode {
        STANDARD("standard", true, true),
        RIVER_ONLY("river_only", false, false);

        private final String id;
        private final boolean seedTestWater;
        private final boolean allowHydrologyFeed;

        BenchmarkMode(String id, boolean seedTestWater, boolean allowHydrologyFeed) {
            this.id = id;
            this.seedTestWater = seedTestWater;
            this.allowHydrologyFeed = allowHydrologyFeed;
        }
    }

    private RiverBenchmarkManager() {
    }

    public static String start(ServerPlayer player) {
        return start(player, BenchmarkMode.STANDARD);
    }

    public static String startRiverOnly(ServerPlayer player) {
        return start(player, BenchmarkMode.RIVER_ONLY);
    }

    private static String start(ServerPlayer player, BenchmarkMode mode) {
        ServerLevel level = player.serverLevel();
        if (RiverConfig.COMMON.overworldOnly.get() && !level.dimension().equals(Level.OVERWORLD)) {
            return "River benchmark requires the overworld while overworldOnly is enabled.";
        }
        if (activeRun != null) {
            activeRun.abort(level, "replaced", "Replaced by a new river benchmark run.");
            activeRun = null;
        }

        BlockPos anchor = player.blockPosition();
        int surfaceY = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, anchor.getX(), anchor.getZ());
        int floorY = Mth.clamp(Math.max(surfaceY + 8, level.getSeaLevel() + 12), level.getMinBuildHeight() + 6, level.getMaxBuildHeight() - CLEAR_HEIGHT - 4);
        activeRun = new ActiveRun(level.dimension(), anchor.immutable(), floorY, player.getUUID(), player.getScoreboardName(), mode);
        activeRun.start(level);
        return "Started river benchmark (" + mode.id + ") at " + activeRun.arena.origin + ". Output will be written to " + activeRun.outputPath + ".";
    }

    public static String status(ServerLevel level) {
        if (activeRun == null) {
            return lastOutputPath == null
                ? "No river benchmark is running."
                : "No river benchmark is running. Last output: " + lastOutputPath;
        }
        if (!activeRun.dimension.equals(level.dimension())) {
            return "River benchmark is running in another dimension.";
        }
        long elapsed = Math.max(0L, level.getGameTime() - activeRun.startedAtGameTime);
        return "Running river benchmark (" + activeRun.mode.id + ") for " + elapsed + "/" + DURATION_TICKS + " ticks at " + activeRun.arena.origin + ".";
    }

    public static String stop(ServerLevel level) {
        if (activeRun == null || !activeRun.dimension.equals(level.dimension())) {
            return "No river benchmark is running in this dimension.";
        }
        activeRun.abort(level, "stopped", "Stopped by command.");
        activeRun = null;
        return "Stopped the river benchmark.";
    }

    public static void tick(ServerLevel level) {
        if (activeRun == null || !activeRun.dimension.equals(level.dimension())) {
            return;
        }
        try {
            if (activeRun.tick(level)) {
                activeRun = null;
            }
        } catch (Exception e) {
            RiverDynamics.LOGGER.error("River benchmark runner crashed", e);
            activeRun.abort(level, "error", e.getMessage());
            activeRun = null;
        }
    }

    public static void onLevelUnload(ServerLevel level) {
        if (activeRun != null && activeRun.dimension.equals(level.dimension())) {
            activeRun = null;
        }
    }

    private static void sendBenchmarkChat(ServerLevel level, UUID playerId, String message, int percent) {
        ServerPlayer player = level.getServer().getPlayerList().getPlayer(playerId);
        Component component = Component.literal("[WPO River Bench " + percent + "%] " + message);
        if (player != null) {
            player.sendSystemMessage(component);
            return;
        }
        RiverDynamics.LOGGER.info("[WPO River Bench {}%] {}", percent, message);
    }

    private static Arena buildArena(ServerLevel level, BlockPos anchor, int floorY, BenchmarkMode mode) {
        BlockPos origin = new BlockPos(anchor.getX(), floorY, anchor.getZ());
        int minX = origin.getX() - CLEAR_X_RADIUS;
        int maxX = origin.getX() + CLEAR_X_RADIUS;
        int minZ = origin.getZ() - CLEAR_NEG_Z;
        int maxZ = origin.getZ() + CLEAR_POS_Z;
        clearArena(level, minX, maxX, minZ, maxZ, floorY);
        buildSlopedArenaFloor(level, origin, minX, maxX, minZ, maxZ);

        List<BlockPos> drivePositions = new ArrayList<>();
        List<TrackedGroup> groups = new ArrayList<>();
        List<Long> reservoirChunks = new ArrayList<>();

        List<BlockPos> mainRiver = new ArrayList<>();
        List<BlockPos> mainRiverDrive = new ArrayList<>();
        List<BlockPos> riverSourcePositions = new ArrayList<>();
        for (int x = -28; x <= 28; x++) {
            int y = riverSurfaceY(origin, x);
            int amount = mode == BenchmarkMode.RIVER_ONLY || (x != -14 && x != 10) ? WPOConfig.MAX_FLUID_LEVEL : 5;
            shapeRiverSlice(level, origin, x, y);
            for (int z = 0; z <= 2; z++) {
                BlockPos surface = new BlockPos(origin.getX() + x, y, origin.getZ() + z);
                placeRiverColumn(level, surface, amount);
                mainRiver.add(surface);
                mainRiverDrive.add(surface);
                if (x == -28) {
                    for (int depth = 0; depth <= 2; depth++) {
                        riverSourcePositions.add(surface.below(depth));
                    }
                }
            }
        }
        groups.add(new TrackedGroup("main_river", mainRiver));

        List<BlockPos> downhill = new ArrayList<>();
        buildRaisedPlatform(level, origin, -16, -12, -8, -1, -1);
        for (int z = -8; z <= -1; z++) {
            int dy = riverWallTopY(origin, -14) - origin.getY() + 1 + Math.max(0, (-z - 1) / 2);
            int amount = z <= -7 ? 8 : z <= -5 ? 7 : z <= -3 ? 6 : 5;
            BlockPos pos = origin.offset(-14, dy, z);
            if (mode.seedTestWater) {
                placeRunoffWater(level, pos, amount);
                drivePositions.add(pos);
            }
            downhill.add(pos);
        }
        groups.add(new TrackedGroup("downhill_feeder", downhill));

        List<BlockPos> bank = new ArrayList<>();
        buildRaisedPlatform(level, origin, 8, 12, -5, -1, -1);
        for (int z = -5; z <= -1; z++) {
            int dy = riverWallTopY(origin, 10) - origin.getY() + 1 + Math.max(0, (-z - 1) / 2);
            int amount = z <= -4 ? 7 : z == -3 ? 6 : z == -2 ? 5 : 4;
            BlockPos pos = origin.offset(10, dy, z);
            if (mode.seedTestWater) {
                placeRunoffWater(level, pos, amount);
                drivePositions.add(pos);
            }
            bank.add(pos);
        }
        groups.add(new TrackedGroup("bank_runoff", bank));

        List<BlockPos> reservoirRiver = new ArrayList<>();
        RiverSavedData data = mode.seedTestWater ? RiverSavedData.get(level) : null;
        buildRaisedPlatform(level, origin, 18, 24, 6, 10, Integer.MIN_VALUE);
        for (int x = 18; x <= 24; x++) {
            int amount = x == 18 ? 2 : 1;
            BlockPos pos = new BlockPos(origin.getX() + x, riverWallTopY(origin, x) + 1, origin.getZ() + 8);
            if (mode.seedTestWater) {
                placeWater(level, pos, amount);
                drivePositions.add(pos);
            }
            reservoirRiver.add(pos);
            long chunkKey = ChunkPos.asLong(pos.getX() >> 4, pos.getZ() >> 4);
            if (mode.seedTestWater && !reservoirChunks.contains(chunkKey)) {
                reservoirChunks.add(chunkKey);
            }
            if (data != null) {
                data.addReservoirLevels(chunkKey, 96.0D, RiverConfig.COMMON.maxReservoirLevelsPerChunk.get());
            }
        }
        groups.add(new TrackedGroup("reservoir_fed_river", reservoirRiver));

        List<BlockPos> flatControl = new ArrayList<>();
        buildRaisedPlatform(level, origin, -26, -22, 6, 10, Integer.MIN_VALUE);
        for (int x = -26; x <= -22; x++) {
            BlockPos pos = new BlockPos(origin.getX() + x, riverWallTopY(origin, x) + 1, origin.getZ() + 8);
            if (mode.seedTestWater) {
                placeWater(level, pos, 5);
                drivePositions.add(pos);
            }
            flatControl.add(pos);
        }
        groups.add(new TrackedGroup("flat_control", flatControl));
        buildComplexRiverFeatures(level, origin, mode, drivePositions, riverSourcePositions, groups, reservoirChunks);
        drivePositions.addAll(mainRiverDrive);

        return new Arena(origin, minX, maxX, minZ, maxZ, floorY, drivePositions, riverSourcePositions, groups, reservoirChunks);
    }

    private static void buildComplexRiverFeatures(
        ServerLevel level,
        BlockPos origin,
        BenchmarkMode mode,
        List<BlockPos> drivePositions,
        List<BlockPos> riverSourcePositions,
        List<TrackedGroup> groups,
        List<Long> reservoirChunks
    ) {
        List<BlockPos> northTributary = new ArrayList<>();
        for (int z = -26; z <= -1; z++) {
            int y = riverWallTopY(origin, -18) + 1 + Math.max(0, (-z - 18) / 4);
            BlockPos pos = origin.offset(-18, y - origin.getY(), z);
            buildBankedCell(level, pos, Direction.Axis.Z);
            if (mode.seedTestWater) {
                placeWater(level, pos, z <= -22 ? 8 : z <= -12 ? 6 : 4);
                drivePositions.add(pos);
                if (z <= -24) {
                    riverSourcePositions.add(pos);
                }
            }
            northTributary.add(pos);
        }
        groups.add(new TrackedGroup("north_tributary", northTributary));

        List<BlockPos> westCascade = new ArrayList<>();
        for (int step = 0; step <= 10; step++) {
            int x = -38 + step;
            int y = riverWallTopY(origin, -28) + 5 - (step / 2);
            BlockPos pos = new BlockPos(origin.getX() + x, y, origin.getZ() + 1);
            buildBankedCell(level, pos, Direction.Axis.X);
            if (mode.seedTestWater) {
                placeWater(level, pos, step <= 2 ? 8 : 5);
                drivePositions.add(pos);
                if (step <= 1) {
                    riverSourcePositions.add(pos);
                }
            }
            westCascade.add(pos);
        }
        groups.add(new TrackedGroup("stepped_headwater_cascade", westCascade));

        List<BlockPos> lake = new ArrayList<>();
        for (int x = -6; x <= 6; x++) {
            for (int z = 5; z <= 17; z++) {
                if ((x * x) + ((z - 11) * (z - 11)) > 52) {
                    continue;
                }
                int y = riverWallTopY(origin, x) + 1;
                BlockPos pos = new BlockPos(origin.getX() + x, y, origin.getZ() + z);
                level.setBlock(pos.below(), Blocks.GRAVEL.defaultBlockState(), 3);
                if (mode.seedTestWater) {
                    placeWater(level, pos, 6);
                    drivePositions.add(pos);
                }
                lake.add(pos);
            }
        }
        groups.add(new TrackedGroup("connected_lake_basin", lake));

        List<BlockPos> lakeInlet = new ArrayList<>();
        for (int z = 3; z <= 5; z++) {
            for (int x = -2; x <= 2; x++) {
                int y = riverWallTopY(origin, x) + 1;
                BlockPos pos = new BlockPos(origin.getX() + x, y, origin.getZ() + z);
                buildBankedCell(level, pos, Direction.Axis.Z);
                if (mode.seedTestWater) {
                    placeWater(level, pos, 6);
                    drivePositions.add(pos);
                }
                lakeInlet.add(pos);
            }
        }
        groups.add(new TrackedGroup("river_to_lake_inlet", lakeInlet));

        List<BlockPos> southDistributary = new ArrayList<>();
        for (int z = 3; z <= 28; z++) {
            int x = 22 + Math.max(0, (z - 8) / 6);
            int y = riverWallTopY(origin, 22) + 1 - Math.max(0, (z - 12) / 8);
            BlockPos pos = new BlockPos(origin.getX() + x, y, origin.getZ() + z);
            buildBankedCell(level, pos, Direction.Axis.Z);
            if (mode.seedTestWater) {
                placeWater(level, pos, z < 22 ? 5 : 3);
                drivePositions.add(pos);
            }
            southDistributary.add(pos);
        }
        groups.add(new TrackedGroup("south_distributary_outlet", southDistributary));

        List<BlockPos> reservoirChain = new ArrayList<>();
        for (int x = 30; x <= 42; x++) {
            int y = riverWallTopY(origin, 28) + 1;
            BlockPos pos = new BlockPos(origin.getX() + x, y, origin.getZ() - 12);
            buildBankedCell(level, pos, Direction.Axis.X);
            if (mode.seedTestWater) {
                placeWater(level, pos, x <= 32 ? 7 : 2);
                drivePositions.add(pos);
                long chunkKey = ChunkPos.asLong(pos.getX() >> 4, pos.getZ() >> 4);
                if (!reservoirChunks.contains(chunkKey)) {
                    reservoirChunks.add(chunkKey);
                }
                RiverSavedData.get(level).addReservoirLevels(chunkKey, x <= 32 ? 160.0D : 32.0D, RiverConfig.COMMON.maxReservoirLevelsPerChunk.get());
            }
            reservoirChain.add(pos);
        }
        groups.add(new TrackedGroup("remote_reservoir_chain", reservoirChain));
    }

    private static void buildBankedCell(ServerLevel level, BlockPos waterPos, Direction.Axis axis) {
        level.setBlock(waterPos.below(), Blocks.GRAVEL.defaultBlockState(), 3);
        if (axis == Direction.Axis.X) {
            level.setBlock(waterPos.north(), arenaState(), 3);
            level.setBlock(waterPos.south(), arenaState(), 3);
        } else {
            level.setBlock(waterPos.west(), arenaState(), 3);
            level.setBlock(waterPos.east(), arenaState(), 3);
        }
    }

    private static void clearArena(ServerLevel level, int minX, int maxX, int minZ, int maxZ, int floorY) {
        int minY = Math.max(level.getMinBuildHeight(), floorY - 4);
        int maxY = Math.min(level.getMaxBuildHeight() - 1, floorY + CLEAR_HEIGHT);
        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                for (int y = minY; y <= maxY; y++) {
                    BlockPos pos = new BlockPos(x, y, z);
                    WPOFluidAccess.setWaterAmount(level, pos, 0);
                    level.setBlock(pos, Blocks.AIR.defaultBlockState(), 3);
                }
            }
        }
    }

    private static int riverSurfaceY(BlockPos origin, int xOffset) {
        int clampedOffset = Mth.clamp(xOffset, -28, 28);
        int drop = Math.floorDiv(clampedOffset + 28, 7);
        return origin.getY() + 6 - drop;
    }

    private static int riverWallTopY(BlockPos origin, int xOffset) {
        return riverSurfaceY(origin, xOffset) + 1;
    }

    private static BlockState arenaState() {
        return ARENA_STATE;
    }

    private static void shapeRiverSlice(ServerLevel level, BlockPos origin, int xOffset, int surfaceY) {
        int worldX = origin.getX() + xOffset;
        for (int z = -1; z <= 3; z++) {
            for (int y = surfaceY - 3; y <= surfaceY + 1; y++) {
                if (z == -1 || z == 3 || y == surfaceY - 3) {
                    level.setBlock(new BlockPos(worldX, y, origin.getZ() + z), arenaState(), 3);
                }
            }
        }
    }

    private static void buildSlopedArenaFloor(ServerLevel level, BlockPos origin, int minX, int maxX, int minZ, int maxZ) {
        for (int x = minX; x <= maxX; x++) {
            int xOffset = x - origin.getX();
            int deckY = riverWallTopY(origin, xOffset);
            for (int z = minZ; z <= maxZ; z++) {
                int zOffset = z - origin.getZ();
                if (xOffset >= -28 && xOffset <= 28 && zOffset >= 0 && zOffset <= 2) {
                    continue;
                }
                for (int y = origin.getY() - 1; y <= deckY; y++) {
                    BlockPos pos = new BlockPos(x, y, z);
                    WPOFluidAccess.setWaterAmount(level, pos, 0);
                    level.setBlock(pos, arenaState(), 3);
                }
                if (x == minX || x == maxX || z == minZ || z == maxZ) {
                    BlockPos curb = new BlockPos(x, deckY + 1, z);
                    WPOFluidAccess.setWaterAmount(level, curb, 0);
                    level.setBlock(curb, arenaState(), 3);
                }
            }
        }
    }

    private static void buildRaisedPlatform(ServerLevel level, BlockPos origin, int minXOffset, int maxXOffset, int minZOffset, int maxZOffset, int openEdgeZOffset) {
        for (int x = minXOffset; x <= maxXOffset; x++) {
            int floorY = riverWallTopY(origin, x);
            int worldX = origin.getX() + x;
            for (int z = minZOffset; z <= maxZOffset; z++) {
                int worldZ = origin.getZ() + z;
                for (int y = origin.getY() - 1; y <= floorY; y++) {
                    BlockPos pos = new BlockPos(worldX, y, worldZ);
                    WPOFluidAccess.setWaterAmount(level, pos, 0);
                    level.setBlock(pos, arenaState(), 3);
                }
                boolean edge = x == minXOffset || x == maxXOffset || z == minZOffset || z == maxZOffset;
                if (edge && z != openEdgeZOffset) {
                    BlockPos curb = new BlockPos(worldX, floorY + 1, worldZ);
                    WPOFluidAccess.setWaterAmount(level, curb, 0);
                    level.setBlock(curb, arenaState(), 3);
                }
            }
        }
    }

    private static void placeRiverColumn(ServerLevel level, BlockPos surface, int topAmount) {
        level.getChunk(surface);
        for (int depth = 2; depth >= 0; depth--) {
            BlockPos waterPos = surface.below(depth);
            int amount = depth == 0 ? topAmount : WPOConfig.MAX_FLUID_LEVEL;
            level.setBlock(waterPos, Blocks.WATER.defaultBlockState(), 3);
            WPOFluidAccess.setWaterAmount(level, waterPos, Mth.clamp(amount, 0, WPOConfig.MAX_FLUID_LEVEL));
            WPOFluidAccess.wakeWater(level, waterPos);
        }
    }

    private static void placeRunoffWater(ServerLevel level, BlockPos pos, int amount) {
        placeWater(level, pos, amount);
        for (int side = -1; side <= 1; side += 2) {
            BlockPos curb = pos.offset(side, 0, 0);
            level.setBlock(curb.below(), arenaState(), 3);
            level.setBlock(curb, arenaState(), 3);
            WPOFluidAccess.setWaterAmount(level, curb, 0);
        }
    }

    private static void placeWater(ServerLevel level, BlockPos pos, int amount) {
        int levels = Mth.clamp(amount, 0, WPOConfig.MAX_FLUID_LEVEL);
        level.getChunk(pos);
        level.setBlock(pos.below(), arenaState(), 3);
        level.setBlock(pos, Blocks.WATER.defaultBlockState(), 3);
        WPOFluidAccess.setWaterAmount(level, pos, levels);
        WPOFluidAccess.wakeWater(level, pos);
    }

    private static int waterTotal(ServerLevel level, List<BlockPos> positions) {
        int total = 0;
        for (BlockPos pos : positions) {
            total += WPOFluidAccess.getWaterAmount(level, pos);
        }
        return total;
    }

    private static LinkedHashMap<String, Integer> captureTotals(ServerLevel level, List<TrackedGroup> groups) {
        LinkedHashMap<String, Integer> totals = new LinkedHashMap<>();
        for (TrackedGroup group : groups) {
            totals.put(group.name, waterTotal(level, group.positions));
        }
        return totals;
    }

    private static double reservoirTotal(ServerLevel level, Arena arena) {
        RiverSavedData data = RiverSavedData.get(level);
        double total = 0.0D;
        for (long chunkKey : arena.reservoirChunks) {
            total += data.getReservoirLevels(chunkKey);
        }
        return total;
    }

    private static JsonObject posJson(BlockPos pos) {
        JsonObject json = new JsonObject();
        json.addProperty("x", pos.getX());
        json.addProperty("y", pos.getY());
        json.addProperty("z", pos.getZ());
        return json;
    }

    private static JsonObject totalsJson(Map<String, Integer> totals) {
        JsonObject json = new JsonObject();
        for (Map.Entry<String, Integer> entry : totals.entrySet()) {
            json.addProperty(entry.getKey(), entry.getValue());
        }
        return json;
    }

    private static JsonArray groupsJson(Arena arena) {
        JsonArray array = new JsonArray();
        for (TrackedGroup group : arena.groups) {
            JsonObject json = new JsonObject();
            json.addProperty("name", group.name);
            JsonArray positions = new JsonArray();
            for (BlockPos pos : group.positions) {
                positions.add(posJson(pos));
            }
            json.add("positions", positions);
            array.add(json);
        }
        return array;
    }

    private static JsonObject configSnapshot() {
        JsonObject json = new JsonObject();
        json.addProperty("enabled", RiverConfig.COMMON.enabled.get());
        json.addProperty("terrariumIntegration", RiverConfig.COMMON.terrariumIntegration.get());
        json.addProperty("osmWaterwayDirections", RiverConfig.COMMON.osmWaterwayDirections.get());
        json.addProperty("terrainFallbackDirections", RiverConfig.COMMON.terrainFallbackDirections.get());
        json.addProperty("flatOutletSolver", RiverConfig.COMMON.flatOutletSolver.get());
        json.addProperty("waterBodyMagnetism", RiverConfig.COMMON.waterBodyMagnetism.get());
        json.addProperty("generatedRiverReplacement", RiverConfig.COMMON.generatedRiverReplacement.get());
        json.addProperty("requireRiverBiomeForFallback", RiverConfig.COMMON.requireRiverBiomeForFallback.get());
        json.addProperty("infiniteRiverSources", RiverConfig.COMMON.infiniteRiverSources.get());
        json.addProperty("physicalWaterFlow", RiverConfig.COMMON.physicalWaterFlow.get());
        json.addProperty("drainAtUnloadedDownstreamEdge", RiverConfig.COMMON.drainAtUnloadedDownstreamEdge.get());
        json.addProperty("actualRainRunoff", RiverConfig.COMMON.actualRainRunoff.get());
        json.addProperty("currentFlowVectors", RiverConfig.COMMON.currentFlowVectors.get());
        json.addProperty("entityCurrentForces", RiverConfig.COMMON.entityCurrentForces.get());
        json.addProperty("updateInterval", RiverConfig.COMMON.updateInterval.get());
        json.addProperty("surfaceSearchDepth", RiverConfig.COMMON.surfaceSearchDepth.get());
        json.addProperty("flatSolverMaxCells", RiverConfig.COMMON.flatSolverMaxCells.get());
        json.addProperty("flatHeadTolerance", RiverConfig.COMMON.flatHeadTolerance.get());
        json.addProperty("flatMinOutletDrop", RiverConfig.COMMON.flatMinOutletDrop.get());
        json.addProperty("waterBodyMagnetRadius", RiverConfig.COMMON.waterBodyMagnetRadius.get());
        json.addProperty("targetRiverLevel", RiverConfig.COMMON.targetRiverLevel.get());
        json.addProperty("minimumRiverLevel", RiverConfig.COMMON.minimumRiverLevel.get());
        json.addProperty("maxTransferLevels", RiverConfig.COMMON.maxTransferLevels.get());
        json.addProperty("sourceRefillLevels", RiverConfig.COMMON.sourceRefillLevels.get());
        json.addProperty("physicalFlowChainDistance", RiverConfig.COMMON.physicalFlowChainDistance.get());
        json.addProperty("minFallbackHeadDrop", RiverConfig.COMMON.minFallbackHeadDrop.get());
        json.addProperty("magnetMaxUphillHead", RiverConfig.COMMON.magnetMaxUphillHead.get());
        json.addProperty("maxReservoirLevelsPerChunk", RiverConfig.COMMON.maxReservoirLevelsPerChunk.get());
        json.addProperty("fallbackRainfallMmPerYear", RiverConfig.COMMON.fallbackRainfallMmPerYear.get());
        return json;
    }

    private record TrackedGroup(String name, List<BlockPos> positions) {
    }

    private record Arena(
        BlockPos origin,
        int minX,
        int maxX,
        int minZ,
        int maxZ,
        int floorY,
        List<BlockPos> drivePositions,
        List<BlockPos> riverSourcePositions,
        List<TrackedGroup> groups,
        List<Long> reservoirChunks
    ) {
    }

    private static final class ActiveRun {
        private final ResourceKey<Level> dimension;
        private final BlockPos anchor;
        private final int floorY;
        private final UUID playerId;
        private final String requestedBy;
        private final BenchmarkMode mode;
        private final SavedConfig savedConfig = new SavedConfig();
        private final JsonArray snapshots = new JsonArray();
        private final Path outputPath;

        private Arena arena;
        private long startedAtGameTime;
        private long lastSnapshotElapsed = Long.MIN_VALUE;
        private long nextChatProgressTick = CHAT_INTERVAL_TICKS;
        private LinkedHashMap<String, Integer> initialTotals = new LinkedHashMap<>();
        private double initialReservoirLevels;
        private boolean finished;

        private ActiveRun(ResourceKey<Level> dimension, BlockPos anchor, int floorY, UUID playerId, String requestedBy, BenchmarkMode mode) {
            this.dimension = dimension;
            this.anchor = anchor;
            this.floorY = floorY;
            this.playerId = playerId;
            this.requestedBy = requestedBy;
            this.mode = mode;
            this.outputPath = OUTPUT_DIR.resolve(FILE_STAMP.format(LocalDateTime.now()) + "-" + mode.id + "-river-benchmark.json");
        }

        private void start(ServerLevel level) {
            try {
                Files.createDirectories(OUTPUT_DIR);
            } catch (IOException e) {
                throw new IllegalStateException("Failed to create river benchmark output directory", e);
            }
            savedConfig.capture();
            configure();
            arena = buildArena(level, anchor, floorY, mode);
            startedAtGameTime = level.getGameTime();
            initialTotals = captureTotals(level, arena.groups);
            initialReservoirLevels = reservoirTotal(level, arena);
            sample(level, 0L, true);
            sendBenchmarkChat(level, playerId, "Started river benchmark arena (" + mode.id + ") at " + arena.origin + ".", 0);
        }

        private boolean tick(ServerLevel level) {
            if (finished) {
                return true;
            }
            long elapsed = Math.max(0L, level.getGameTime() - startedAtGameTime);
            drive(level, elapsed);
            sample(level, elapsed, elapsed % SAMPLE_INTERVAL_TICKS == 0L);
            maybeAnnounce(level, elapsed);
            if (elapsed < DURATION_TICKS) {
                return false;
            }
            finish(level, "completed", "River benchmark completed.");
            return true;
        }

        private void configure() {
            RiverConfig.COMMON.enabled.set(true);
            RiverConfig.COMMON.terrariumIntegration.set(false);
            RiverConfig.COMMON.osmWaterwayDirections.set(false);
            RiverConfig.COMMON.terrainFallbackDirections.set(true);
            RiverConfig.COMMON.flatOutletSolver.set(true);
            RiverConfig.COMMON.waterBodyMagnetism.set(true);
            RiverConfig.COMMON.generatedRiverReplacement.set(false);
            RiverConfig.COMMON.requireRiverBiomeForFallback.set(false);
            RiverConfig.COMMON.infiniteRiverSources.set(true);
            RiverConfig.COMMON.physicalWaterFlow.set(true);
            RiverConfig.COMMON.drainAtUnloadedDownstreamEdge.set(false);
            RiverConfig.COMMON.actualRainRunoff.set(false);
            RiverConfig.COMMON.entityCurrentForces.set(true);
            RiverConfig.COMMON.currentParticles.set(false);
            RiverConfig.COMMON.updateInterval.set(5);
            RiverConfig.COMMON.surfaceSearchDepth.set(16);
            RiverConfig.COMMON.flatSolverMaxCells.set(256);
            RiverConfig.COMMON.flatHeadTolerance.set(1);
            RiverConfig.COMMON.flatMinOutletDrop.set(1);
            RiverConfig.COMMON.waterBodyMagnetRadius.set(24);
            RiverConfig.COMMON.targetRiverLevel.set(mode.allowHydrologyFeed ? 7 : 1);
            RiverConfig.COMMON.minimumRiverLevel.set(mode.allowHydrologyFeed ? 4 : 1);
            RiverConfig.COMMON.maxTransferLevels.set(2);
            RiverConfig.COMMON.sourceRefillLevels.set(4);
            RiverConfig.COMMON.physicalFlowChainDistance.set(24);
            RiverConfig.COMMON.minFallbackHeadDrop.set(1);
            RiverConfig.COMMON.magnetMaxUphillHead.set(16);
            if (!mode.allowHydrologyFeed) {
                RiverConfig.COMMON.fallbackRainfallMmPerYear.set(0.0D);
            }
        }

        private void drive(ServerLevel level, long elapsed) {
            replenishRiverSource(level);
            int interval = Math.max(1, RiverConfig.COMMON.updateInterval.get());
            if (elapsed <= 0L || elapsed % interval != 0L) {
                return;
            }
            RiverSavedData data = RiverSavedData.get(level);
            Set<BlockPos> processed = new LinkedHashSet<>();
            for (BlockPos pos : arena.drivePositions) {
                processBenchmarkWater(level, data, pos, processed);
            }
            driveArenaSurfaceWater(level, data, processed);
        }

        private void replenishRiverSource(ServerLevel level) {
            for (BlockPos pos : arena.riverSourcePositions) {
                if (!WPOFluidAccess.isChunkLoaded(level, pos)) {
                    continue;
                }
                WPOFluidAccess.addWater(level, pos, WPOConfig.MAX_FLUID_LEVEL);
                WPOFluidAccess.wakeWater(level, pos);
            }
        }

        private void driveArenaSurfaceWater(ServerLevel level, RiverSavedData data, Set<BlockPos> processed) {
            int minY = Math.max(level.getMinBuildHeight(), arena.floorY - 4);
            int maxY = Math.min(level.getMaxBuildHeight() - 1, arena.floorY + CLEAR_HEIGHT);
            for (int x = arena.minX; x <= arena.maxX; x++) {
                for (int z = arena.minZ; z <= arena.maxZ; z++) {
                    for (int y = maxY; y >= minY; y--) {
                        BlockPos pos = new BlockPos(x, y, z);
                        if (WPOFluidAccess.getWaterAmount(level, pos) > 0) {
                            processBenchmarkWater(level, data, pos, processed);
                            break;
                        }
                    }
                }
            }
        }

        private void processBenchmarkWater(ServerLevel level, RiverSavedData data, BlockPos pos, Set<BlockPos> processed) {
            BlockPos immutablePos = pos.immutable();
            if (!processed.add(immutablePos) || WPOFluidAccess.getWaterAmount(level, immutablePos) <= 0) {
                return;
            }
            RiverTicker.processBenchmarkWater(level, immutablePos, data, mode.allowHydrologyFeed);
            WPOFluidAccess.wakeWater(level, immutablePos);
        }

        private void sample(ServerLevel level, long elapsed, boolean force) {
            if (!force && lastSnapshotElapsed != Long.MIN_VALUE && elapsed - lastSnapshotElapsed < SAMPLE_INTERVAL_TICKS) {
                return;
            }
            JsonObject snapshot = new JsonObject();
            snapshot.addProperty("elapsedTicks", elapsed);
            snapshot.add("totals", totalsJson(captureTotals(level, arena.groups)));
            snapshot.addProperty("reservoirLevels", reservoirTotal(level, arena));
            snapshots.add(snapshot);
            lastSnapshotElapsed = elapsed;
        }

        private void maybeAnnounce(ServerLevel level, long elapsed) {
            if (elapsed < nextChatProgressTick || elapsed >= DURATION_TICKS) {
                return;
            }
            while (nextChatProgressTick <= elapsed && nextChatProgressTick < DURATION_TICKS) {
                int percent = Mth.clamp((int) Math.round(nextChatProgressTick * 100.0D / DURATION_TICKS), 0, 100);
                sendBenchmarkChat(level, playerId, "River benchmark running for " + nextChatProgressTick + "/" + DURATION_TICKS + " ticks.", percent);
                nextChatProgressTick += CHAT_INTERVAL_TICKS;
            }
        }

        private void abort(ServerLevel level, String status, String message) {
            finish(level, status, message);
        }

        private void finish(ServerLevel level, String status, String message) {
            if (finished) {
                return;
            }
            finished = true;
            long elapsed = Math.max(0L, level.getGameTime() - startedAtGameTime);
            sample(level, elapsed, true);
            LinkedHashMap<String, Integer> finalTotals = captureTotals(level, arena.groups);
            double finalReservoirLevels = reservoirTotal(level, arena);
            JsonObject effectiveConfig = configSnapshot();
            savedConfig.restore();

            JsonObject root = new JsonObject();
            root.addProperty("suite", "river_flow_arena");
            root.addProperty("mode", mode.id);
            root.addProperty("requestedBy", requestedBy);
            root.addProperty("status", status);
            root.addProperty("message", message == null ? "" : message);
            root.addProperty("generatedAt", LocalDateTime.now().toString());
            root.addProperty("durationTicks", DURATION_TICKS);
            root.addProperty("elapsedTicks", elapsed);
            root.add("anchor", posJson(anchor));
            root.add("arenaOrigin", posJson(arena.origin));
            root.add("configuration", effectiveConfig);
            root.add("groups", groupsJson(arena));
            root.add("initialTotals", totalsJson(initialTotals));
            root.add("finalTotals", totalsJson(finalTotals));
            root.add("summary", summaryJson(finalTotals, finalReservoirLevels));
            root.add("snapshots", snapshots);

            try (BufferedWriter writer = Files.newBufferedWriter(outputPath, StandardCharsets.UTF_8)) {
                GSON.toJson(root, writer);
                lastOutputPath = outputPath;
                int percent = "completed".equals(status) ? 100 : Mth.clamp((int) Math.round(elapsed * 100.0D / DURATION_TICKS), 0, 100);
                sendBenchmarkChat(level, playerId, "River benchmark finished with status " + status + ". Output: " + outputPath, percent);
            } catch (IOException e) {
                RiverDynamics.LOGGER.error("Failed to write river benchmark output {}", outputPath, e);
            }
        }

        private JsonObject summaryJson(LinkedHashMap<String, Integer> finalTotals, double finalReservoirLevels) {
            JsonObject summary = new JsonObject();
            for (Map.Entry<String, Integer> entry : finalTotals.entrySet()) {
                int start = initialTotals.getOrDefault(entry.getKey(), 0);
                int end = entry.getValue();
                summary.addProperty(entry.getKey() + "Start", start);
                summary.addProperty(entry.getKey() + "End", end);
                summary.addProperty(entry.getKey() + "Delta", end - start);
            }
            summary.addProperty("reservoirLevelsStart", initialReservoirLevels);
            summary.addProperty("reservoirLevelsEnd", finalReservoirLevels);
            summary.addProperty("reservoirLevelsDelta", finalReservoirLevels - initialReservoirLevels);
            int downhillDelta = finalTotals.getOrDefault("downhill_feeder", 0) - initialTotals.getOrDefault("downhill_feeder", 0);
            int bankDelta = finalTotals.getOrDefault("bank_runoff", 0) - initialTotals.getOrDefault("bank_runoff", 0);
            int mainDelta = finalTotals.getOrDefault("main_river", 0) - initialTotals.getOrDefault("main_river", 0);
            int reservoirDelta = finalTotals.getOrDefault("reservoir_fed_river", 0) - initialTotals.getOrDefault("reservoir_fed_river", 0);
            summary.addProperty("downhillFedRiver", downhillDelta < 0 && mainDelta > 0);
            summary.addProperty("bankRunoffReachedRiver", bankDelta < 0 && mainDelta > 0);
            summary.addProperty("reservoirRaisedLowRiver", reservoirDelta > 0);
            return summary;
        }
    }

    private static final class SavedConfig {
        private boolean captured;
        private boolean enabled;
        private boolean terrariumIntegration;
        private boolean osmWaterwayDirections;
        private boolean terrainFallbackDirections;
        private boolean flatOutletSolver;
        private boolean waterBodyMagnetism;
        private boolean generatedRiverReplacement;
        private boolean requireRiverBiomeForFallback;
        private boolean infiniteRiverSources;
        private boolean physicalWaterFlow;
        private boolean drainAtUnloadedDownstreamEdge;
        private boolean actualRainRunoff;
        private boolean entityCurrentForces;
        private boolean currentParticles;
        private int updateInterval;
        private int surfaceSearchDepth;
        private int flatSolverMaxCells;
        private int flatHeadTolerance;
        private int flatMinOutletDrop;
        private int waterBodyMagnetRadius;
        private int targetRiverLevel;
        private int minimumRiverLevel;
        private int maxTransferLevels;
        private int sourceRefillLevels;
        private int physicalFlowChainDistance;
        private int minFallbackHeadDrop;
        private int magnetMaxUphillHead;
        private double fallbackRainfallMmPerYear;

        private void capture() {
            if (captured) {
                return;
            }
            captured = true;
            enabled = RiverConfig.COMMON.enabled.get();
            terrariumIntegration = RiverConfig.COMMON.terrariumIntegration.get();
            osmWaterwayDirections = RiverConfig.COMMON.osmWaterwayDirections.get();
            terrainFallbackDirections = RiverConfig.COMMON.terrainFallbackDirections.get();
            flatOutletSolver = RiverConfig.COMMON.flatOutletSolver.get();
            waterBodyMagnetism = RiverConfig.COMMON.waterBodyMagnetism.get();
            generatedRiverReplacement = RiverConfig.COMMON.generatedRiverReplacement.get();
            requireRiverBiomeForFallback = RiverConfig.COMMON.requireRiverBiomeForFallback.get();
            infiniteRiverSources = RiverConfig.COMMON.infiniteRiverSources.get();
            physicalWaterFlow = RiverConfig.COMMON.physicalWaterFlow.get();
            drainAtUnloadedDownstreamEdge = RiverConfig.COMMON.drainAtUnloadedDownstreamEdge.get();
            actualRainRunoff = RiverConfig.COMMON.actualRainRunoff.get();
            entityCurrentForces = RiverConfig.COMMON.entityCurrentForces.get();
            currentParticles = RiverConfig.COMMON.currentParticles.get();
            updateInterval = RiverConfig.COMMON.updateInterval.get();
            surfaceSearchDepth = RiverConfig.COMMON.surfaceSearchDepth.get();
            flatSolverMaxCells = RiverConfig.COMMON.flatSolverMaxCells.get();
            flatHeadTolerance = RiverConfig.COMMON.flatHeadTolerance.get();
            flatMinOutletDrop = RiverConfig.COMMON.flatMinOutletDrop.get();
            waterBodyMagnetRadius = RiverConfig.COMMON.waterBodyMagnetRadius.get();
            targetRiverLevel = RiverConfig.COMMON.targetRiverLevel.get();
            minimumRiverLevel = RiverConfig.COMMON.minimumRiverLevel.get();
            maxTransferLevels = RiverConfig.COMMON.maxTransferLevels.get();
            sourceRefillLevels = RiverConfig.COMMON.sourceRefillLevels.get();
            physicalFlowChainDistance = RiverConfig.COMMON.physicalFlowChainDistance.get();
            minFallbackHeadDrop = RiverConfig.COMMON.minFallbackHeadDrop.get();
            magnetMaxUphillHead = RiverConfig.COMMON.magnetMaxUphillHead.get();
            fallbackRainfallMmPerYear = RiverConfig.COMMON.fallbackRainfallMmPerYear.get();
        }

        private void restore() {
            if (!captured) {
                return;
            }
            RiverConfig.COMMON.enabled.set(enabled);
            RiverConfig.COMMON.terrariumIntegration.set(terrariumIntegration);
            RiverConfig.COMMON.osmWaterwayDirections.set(osmWaterwayDirections);
            RiverConfig.COMMON.terrainFallbackDirections.set(terrainFallbackDirections);
            RiverConfig.COMMON.flatOutletSolver.set(flatOutletSolver);
            RiverConfig.COMMON.waterBodyMagnetism.set(waterBodyMagnetism);
            RiverConfig.COMMON.generatedRiverReplacement.set(generatedRiverReplacement);
            RiverConfig.COMMON.requireRiverBiomeForFallback.set(requireRiverBiomeForFallback);
            RiverConfig.COMMON.infiniteRiverSources.set(infiniteRiverSources);
            RiverConfig.COMMON.physicalWaterFlow.set(physicalWaterFlow);
            RiverConfig.COMMON.drainAtUnloadedDownstreamEdge.set(drainAtUnloadedDownstreamEdge);
            RiverConfig.COMMON.actualRainRunoff.set(actualRainRunoff);
            RiverConfig.COMMON.entityCurrentForces.set(entityCurrentForces);
            RiverConfig.COMMON.currentParticles.set(currentParticles);
            RiverConfig.COMMON.updateInterval.set(updateInterval);
            RiverConfig.COMMON.surfaceSearchDepth.set(surfaceSearchDepth);
            RiverConfig.COMMON.flatSolverMaxCells.set(flatSolverMaxCells);
            RiverConfig.COMMON.flatHeadTolerance.set(flatHeadTolerance);
            RiverConfig.COMMON.flatMinOutletDrop.set(flatMinOutletDrop);
            RiverConfig.COMMON.waterBodyMagnetRadius.set(waterBodyMagnetRadius);
            RiverConfig.COMMON.targetRiverLevel.set(targetRiverLevel);
            RiverConfig.COMMON.minimumRiverLevel.set(minimumRiverLevel);
            RiverConfig.COMMON.maxTransferLevels.set(maxTransferLevels);
            RiverConfig.COMMON.sourceRefillLevels.set(sourceRefillLevels);
            RiverConfig.COMMON.physicalFlowChainDistance.set(physicalFlowChainDistance);
            RiverConfig.COMMON.minFallbackHeadDrop.set(minFallbackHeadDrop);
            RiverConfig.COMMON.magnetMaxUphillHead.set(magnetMaxUphillHead);
            RiverConfig.COMMON.fallbackRainfallMmPerYear.set(fallbackRainfallMmPerYear);
        }
    }
}
