package net.skds.wpo.river;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;

public final class RiverCurrentField {

    private static final Map<ResourceLocation, FieldState> SERVER_FIELDS = new ConcurrentHashMap<>();
    private static final Map<ResourceLocation, FieldState> CLIENT_FIELDS = new ConcurrentHashMap<>();
    private static volatile Consumer<Collection<BlockPos>> clientRefresh = ignored -> {
    };

    private RiverCurrentField() {
    }

    static void publishColumn(ServerLevel level, BlockPos surfacePos, FlowDirective flow, double strength) {
        if (flow.direction() == null || flow.target() == null || !(strength > 0.0D)) {
            clear(level, surfacePos);
            return;
        }
        double speed = resolvedSpeed(flow.speed(), strength);
        Current current = new Current(
                flow.direction(),
                strength,
                speed,
                level.getGameTime(),
                flow.source(),
                flow.target().immutable(),
                flow.vecX(),
                flow.vecZ());
        int bedHeight = level.getHeight(
                Heightmap.Types.OCEAN_FLOOR, surfacePos.getX(), surfacePos.getZ());
        putColumn(serverField(level, true), surfacePos, bedHeight, current);
        RiverCurrentSync.queueUpsert(level, surfacePos, current);
    }

    static void replaceTopologyComponent(
            ServerLevel level,
            Collection<BlockPos> touched,
            Map<BlockPos, TopologyColumn> columns
    ) {
        if (touched.isEmpty() && columns.isEmpty()) {
            return;
        }

        Set<Long> replacedKeys = new HashSet<>(touched.size() + columns.size());
        for (BlockPos pos : touched) {
            if (pos != null) {
                replacedKeys.add(columnKey(pos));
            }
        }

        Map<Long, PreparedTopologyColumn> prepared = new HashMap<>(columns.size());
        double minimum = RiverConfig.COMMON.minCurrentStrength.get();
        double maximum = RiverConfig.COMMON.maxCurrentStrength.get();
        long updatedAt = level.getGameTime();
        for (Map.Entry<BlockPos, TopologyColumn> entry : columns.entrySet()) {
            BlockPos pos = entry.getKey();
            TopologyColumn topology = entry.getValue();
            FlowDirective flow = topology == null ? null : topology.flow();
            if (pos == null || flow == null || flow.direction() == null || flow.target() == null) {
                continue;
            }
            long key = columnKey(pos);
            replacedKeys.add(key);
            double speed = Double.isFinite(flow.speed()) && flow.speed() > 0.0D
                    ? clampSpeed(flow.speed())
                    : RiverHydraulics.MIN_SPEED;
            double strength = RiverHydraulics.biasStrength(speed, minimum, maximum);
            Current current = new Current(
                    flow.direction(),
                    strength,
                    speed,
                    updatedAt,
                    flow.source(),
                    flow.target().immutable(),
                    flow.vecX(),
                    flow.vecZ());
            prepared.put(key, new PreparedTopologyColumn(
                    pos.immutable(),
                    topology.bedY(),
                    current));
        }

        ResourceLocation dimension = level.dimension().location();
        FieldState previous = SERVER_FIELDS.get(dimension);
        FieldState replacement = new FieldState();
        if (previous != null) {
            replacement.reversed = previous.reversed;
            for (Map.Entry<Long, SurfaceSample> entry : previous.columns.entrySet()) {
                if (!replacedKeys.contains(entry.getKey())) {
                    putSample(replacement, entry.getValue());
                }
            }
        }
        for (PreparedTopologyColumn column : prepared.values()) {
            putColumn(replacement, column.pos(), column.bedY(), column.current());
        }

        SERVER_FIELDS.put(dimension, replacement);

        for (long key : replacedKeys) {
            PreparedTopologyColumn column = prepared.get(key);
            if (column != null) {
                RiverCurrentSync.queueUpsert(level, column.pos(), column.current());
                continue;
            }
            SurfaceSample old = previous == null ? null : previous.columns.get(key);
            if (old != null) {
                RiverCurrentSync.queueRemoval(level, BlockPos.of(old.pos()));
            }
        }
    }

    static void clear(ServerLevel level, BlockPos pos) {
        FieldState field = serverField(level, false);
        if (field == null) {
            return;
        }
        SurfaceSample removed = removeColumn(field, pos);
        if (removed != null) {
            RiverCurrentSync.queueRemoval(level, BlockPos.of(removed.pos()));
        }
    }

    static void sweep(ServerLevel level, long maxAgeTicks) {
        FieldState field = serverField(level, false);
        if (field == null || field.columns.isEmpty()) {
            return;
        }
        long cutoff = level.getGameTime() - Math.max(20L, maxAgeTicks);
        for (SurfaceSample sample : field.surfaceField.removeStale(cutoff)) {
            BlockPos pos = BlockPos.of(sample.pos());
            field.columns.remove(columnKey(pos), sample);
            RiverCurrentSync.queueRemoval(level, pos);
        }
        field.columns.entrySet().removeIf(entry -> entry.getValue().current().updatedAt() < cutoff);
    }

    static void clear(ServerLevel level) {
        SERVER_FIELDS.remove(level.dimension().location());
        RiverCurrentSync.discard(level);
        RiverTopologyField.clear(level);
    }

    static void clearAndSync(ServerLevel level) {
        clear(level);
        RiverCurrentSync.broadcastReset(level);
    }

    // Reuses the current cache as a resolved-flow cache: same freshness window, same
    // eviction/sweep lifecycle, avoids re-running the expensive resolver in RiverTicker
    // when a column was already resolved recently.
    static Optional<FlowDirective> cachedFlow(Level level, BlockPos pos) {
        return currentAt(level, pos).map(c -> new FlowDirective(
                c.direction(), c.target(), c.source(), c.vecX(), c.vecZ(), c.speed()));
    }

    static boolean toggleReversed(ServerLevel level) {
        FieldState field = serverField(level, true);
        boolean reversed = !field.reversed;
        field.reversed = reversed;
        RiverCurrentSync.broadcastState(level);
        return reversed;
    }

    static boolean isReversed(Level level) {
        FieldState field = field(level, false);
        return field != null && field.reversed;
    }

    static Optional<Current> currentAt(Level level, BlockPos pos) {
        FieldState field = field(level, false);
        if (field == null || field.columns.isEmpty()) {
            return Optional.empty();
        }
        return Optional.ofNullable(lookup(field, pos, cutoff(level)));
    }

    // Strength remains local so an unsampled body does not inherit a distant river current.
    // Direction prefers the local connected-channel tangent; cached bed/raw consensus remains
    // the sparse-sample fallback.
    static Optional<Flow> smoothedFlowAt(Level level, BlockPos pos) {
        FieldState field = field(level, false);
        if (field == null || field.columns.isEmpty()) {
            return Optional.empty();
        }

        long cutoff = cutoff(level);
        double localStrength = 0.0D;
        double localStrengthWeight = 0.0D;
        double localSpeed = 0.0D;
        double localSpeedWeight = 0.0D;
        double referenceX = 0.0D;
        double referenceZ = 0.0D;

        Current center = lookup(field, pos, cutoff);
        if (center != null) {
            localStrength += center.strength() * 2.0D;
            localStrengthWeight += 2.0D;
            localSpeed += center.speed() * 2.0D;
            localSpeedWeight += 2.0D;
            referenceX = center.vecX() * 2.0D;
            referenceZ = center.vecZ() * 2.0D;
        }
        for (Direction dir : Direction.Plane.HORIZONTAL) {
            Current c = lookup(field, pos.relative(dir), cutoff);
            if (c != null) {
                localStrength += c.strength();
                localStrengthWeight += 1.0D;
                localSpeed += c.speed();
                localSpeedWeight += 1.0D;
                referenceX += c.vecX();
                referenceZ += c.vecZ();
            }
        }
        if (localStrengthWeight <= 0.0D) {
            return Optional.empty();
        }
        localStrength /= localStrengthWeight;
        if (localStrength < 0.05D) {
            return Optional.empty();
        }

        double referenceLength = Math.hypot(referenceX, referenceZ);
        FlowConsensus.Axis axis = downstreamAxis(field, pos, center, cutoff).orElse(null);
        if (axis == null && referenceLength > 1.0E-6D) {
            // FlowBias can query this path from WPO worker threads. Neighbor-only gaps must
            // preserve the signed current directly; asking the channel tangent to pick a sign
            // can reverse a flat west/north reach and may read world height off-thread.
            if (center != null
                    && (!(level instanceof ServerLevel server) || server.getServer().isSameThread())) {
                axis = field.surfaceField.axisAt(level, pos, referenceX, referenceZ).orElse(null);
            }
            if (axis == null) {
                axis = FlowConsensus.signedReferenceAxis(referenceX, referenceZ).orElse(null);
            }
        }
        if (axis != null && isReversed(level)) {
            axis = new FlowConsensus.Axis(-axis.x(), -axis.z());
        }
        double speed = localSpeedWeight > 0.0D
                ? clampSpeed(localSpeed / localSpeedWeight)
                : RiverHydraulics.speedFromBiasStrength(
                        localStrength,
                        RiverConfig.COMMON.minCurrentStrength.get(),
                        RiverConfig.COMMON.maxCurrentStrength.get());
        return axis == null
                ? Optional.empty()
                : Optional.of(new Flow(axis.x(), axis.z(), localStrength, speed));
    }

    /**
     * Follow the already-resolved downstream links for a few hops. This keeps one reach
     * coherent while still allowing the vector to rotate at a real bend or confluence.
     */
    private static Optional<FlowConsensus.Axis> downstreamAxis(
            FieldState field,
            BlockPos origin,
            Current center,
            long cutoff
    ) {
        if (center == null) {
            return Optional.empty();
        }

        double vectorX = 0.0D;
        double vectorZ = 0.0D;
        double weightTotal = 0.0D;
        Current current = center;
        BlockPos position = origin;
        Set<Long> visited = new HashSet<>();
        visited.add(position.asLong());

        for (int hop = 0; hop < 5 && current != null; hop++) {
            double length = Math.sqrt((current.vecX() * current.vecX()) + (current.vecZ() * current.vecZ()));
            if (length > 1.0E-6D && current.updatedAt() >= cutoff) {
                double weight = 1.0D / (hop + 1.0D);
                vectorX += (current.vecX() / length) * weight;
                vectorZ += (current.vecZ() / length) * weight;
                weightTotal += weight;
            }

            BlockPos target = current.target();
            if (target == null || !visited.add(target.asLong())) {
                break;
            }
            position = target;
            current = lookup(field, target, cutoff);
        }

        if (weightTotal <= 0.0D) {
            return Optional.empty();
        }
        double length = Math.sqrt((vectorX * vectorX) + (vectorZ * vectorZ));
        if (length < 1.0E-6D) {
            return Optional.empty();
        }
        return Optional.of(new FlowConsensus.Axis(vectorX / length, vectorZ / length));
    }

    static void visitFresh(ServerLevel level, BlockPos center, int radius, java.util.function.BiConsumer<BlockPos, Current> visitor) {
        FieldState field = serverField(level, false);
        if (field != null) {
            field.surfaceField.visitFresh(center, radius, cutoff(level), visitor);
        }
    }

    static List<ColumnSnapshot> snapshotChunk(ServerLevel level, ChunkPos chunkPos) {
        FieldState field = serverField(level, false);
        if (field == null) {
            return List.of();
        }
        return field.surfaceField.freshInChunk(chunkPos, cutoff(level)).stream()
                .map(sample -> new ColumnSnapshot(BlockPos.of(sample.pos()), sample.current()))
                .toList();
    }

    static void applyClientSync(Level level, RiverCurrentSyncPayload payload) {
        if (!level.isClientSide()) {
            return;
        }

        Set<BlockPos> dirty = new LinkedHashSet<>();
        FieldState field;
        if (payload.reset()) {
            FieldState removed = CLIENT_FIELDS.remove(level.dimension().location());
            if (removed != null) {
                dirty.addAll(removed.surfaceField.positions());
            }
        }
        field = clientField(level, true);
        if (field.reversed != payload.reversed()) {
            field.reversed = payload.reversed();
            dirty.addAll(field.surfaceField.positions());
        }

        for (RiverCurrentSyncPayload.ChunkUpdate update : payload.chunks()) {
            if (!level.hasChunk(update.chunkX(), update.chunkZ())) {
                continue;
            }
            ChunkPos chunkPos = new ChunkPos(update.chunkX(), update.chunkZ());
            if (update.replace()) {
                for (SurfaceSample removed : removeChunk(field, chunkPos)) {
                    dirty.add(BlockPos.of(removed.pos()));
                }
            }
            for (BlockPos pos : update.removals()) {
                if (!belongsTo(pos, chunkPos)) {
                    continue;
                }
                SurfaceSample removed = removeColumn(field, pos);
                if (removed != null) {
                    dirty.add(BlockPos.of(removed.pos()));
                }
            }
            for (RiverCurrentSyncPayload.Column column : update.upserts()) {
                if (!valid(column, chunkPos)) {
                    continue;
                }
                Current current = new Current(
                        column.direction(),
                        column.strength(),
                        clampSpeed(column.speed()),
                        level.getGameTime(),
                        "network",
                        column.target().immutable(),
                        column.vecX(),
                        column.vecZ());
                int bedHeight = level.getHeight(
                        Heightmap.Types.OCEAN_FLOOR, column.pos().getX(), column.pos().getZ());
                SurfaceSample previous = putColumn(field, column.pos(), bedHeight, current);
                if (previous == null || materiallyChanged(previous.current(), current)) {
                    dirty.add(column.pos().immutable());
                }
            }
        }
        refreshClient(dirty);
    }

    static void clearClientChunk(Level level, ChunkPos chunkPos) {
        if (!level.isClientSide()) {
            return;
        }
        FieldState field = clientField(level, false);
        if (field == null) {
            return;
        }
        List<SurfaceSample> removed = removeChunk(field, chunkPos);
        refreshClient(removed.stream().map(sample -> BlockPos.of(sample.pos())).toList());
    }

    static void clearClient(Level level) {
        if (level.isClientSide()) {
            CLIENT_FIELDS.remove(level.dimension().location());
        }
    }

    static void setClientRefresh(Consumer<Collection<BlockPos>> refresh) {
        clientRefresh = Objects.requireNonNull(refresh);
    }

    static long columnKey(BlockPos pos) {
        return columnKey(pos.getX(), pos.getZ());
    }

    private static long columnKey(int x, int z) {
        return ChunkPos.asLong(x, z);
    }

    private static FieldState field(Level level, boolean create) {
        return level.isClientSide()
                ? clientField(level, create)
                : create
                        ? SERVER_FIELDS.computeIfAbsent(
                                level.dimension().location(),
                                ignored -> new FieldState())
                        : SERVER_FIELDS.get(level.dimension().location());
    }

    private static FieldState serverField(ServerLevel level, boolean create) {
        return create
                ? SERVER_FIELDS.computeIfAbsent(level.dimension().location(), ignored -> new FieldState())
                : SERVER_FIELDS.get(level.dimension().location());
    }

    private static FieldState clientField(Level level, boolean create) {
        return create
                ? CLIENT_FIELDS.computeIfAbsent(level.dimension().location(), ignored -> new FieldState())
                : CLIENT_FIELDS.get(level.dimension().location());
    }

    private static SurfaceSample putColumn(
            FieldState field,
            BlockPos surfacePos,
            int bedHeight,
            Current current
    ) {
        BlockPos stable = surfacePos.immutable();
        SurfaceSample sample = new SurfaceSample(
                stable.asLong(),
                Math.min(stable.getY(), bedHeight),
                current);
        SurfaceSample previous = field.columns.put(columnKey(stable), sample);
        field.surfaceField.put(stable, sample);
        return previous;
    }

    private static void putSample(FieldState field, SurfaceSample sample) {
        BlockPos pos = BlockPos.of(sample.pos());
        field.columns.put(columnKey(pos), sample);
        field.surfaceField.put(pos, sample);
    }

    private static SurfaceSample removeColumn(FieldState field, BlockPos pos) {
        SurfaceSample removed = field.columns.remove(columnKey(pos));
        field.surfaceField.remove(pos);
        return removed;
    }

    private static List<SurfaceSample> removeChunk(FieldState field, ChunkPos chunkPos) {
        List<SurfaceSample> removed = field.surfaceField.removeChunk(chunkPos);
        for (SurfaceSample sample : removed) {
            field.columns.remove(columnKey(BlockPos.of(sample.pos())), sample);
        }
        return removed;
    }

    private static double resolvedSpeed(double flowSpeed, double strength) {
        if (Double.isFinite(flowSpeed) && flowSpeed > 0.0D) {
            return clampSpeed(flowSpeed);
        }
        return RiverHydraulics.speedFromBiasStrength(
                strength,
                RiverConfig.COMMON.minCurrentStrength.get(),
                RiverConfig.COMMON.maxCurrentStrength.get());
    }

    private static double clampSpeed(double speed) {
        return Math.max(RiverHydraulics.MIN_SPEED, Math.min(RiverHydraulics.MAX_SPEED, speed));
    }

    private static boolean belongsTo(BlockPos pos, ChunkPos chunkPos) {
        return (pos.getX() >> 4) == chunkPos.x && (pos.getZ() >> 4) == chunkPos.z;
    }

    private static boolean valid(RiverCurrentSyncPayload.Column column, ChunkPos chunkPos) {
        return belongsTo(column.pos(), chunkPos)
                && column.direction().getAxis().isHorizontal()
                && Double.isFinite(column.vecX())
                && Double.isFinite(column.vecZ())
                && Float.isFinite(column.strength())
                && column.strength() > 0.0F
                && Float.isFinite(column.speed())
                && column.speed() > 0.0F;
    }

    private static boolean materiallyChanged(Current previous, Current current) {
        return previous.direction() != current.direction()
                || !previous.target().equals(current.target())
                || Math.abs(previous.vecX() - current.vecX()) > 1.0E-4D
                || Math.abs(previous.vecZ() - current.vecZ()) > 1.0E-4D
                || Math.abs(previous.strength() - current.strength()) > 1.0E-3D
                || Math.abs(previous.speed() - current.speed()) > 1.0E-4D;
    }

    private static void refreshClient(Collection<BlockPos> positions) {
        if (!positions.isEmpty()) {
            clientRefresh.accept(List.copyOf(positions));
        }
    }

    private static long cutoff(Level level) {
        return level.getGameTime() - Math.max(20L, RiverConfig.COMMON.currentMaxAgeTicks.get());
    }

    private static Current lookup(FieldState field, BlockPos pos, long cutoff) {
        SurfaceSample sample = field.columns.get(columnKey(pos));
        if (sample == null || sample.current().updatedAt() < cutoff) {
            return null;
        }
        int depth = Math.max(1, RiverConfig.COMMON.currentColumnDepth.get());
        BlockPos surface = BlockPos.of(sample.pos());
        int minimumY = Math.max(sample.bedHeight(), surface.getY() - depth + 1);
        return pos.getY() >= minimumY - 1 && pos.getY() <= surface.getY() + 1
                ? sample.current()
                : null;
    }

    record Flow(double x, double z, double strength, double speed) {
        Flow(double x, double z, double strength) {
            this(
                    x,
                    z,
                    strength,
                    RiverHydraulics.speedFromBiasStrength(
                            strength,
                            RiverConfig.COMMON.minCurrentStrength.get(),
                            RiverConfig.COMMON.maxCurrentStrength.get()));
        }

        Direction direction() {
            if (Math.abs(x) >= Math.abs(z)) {
                return x >= 0.0D ? Direction.EAST : Direction.WEST;
            }
            return z >= 0.0D ? Direction.SOUTH : Direction.NORTH;
        }

        Vec3 vector(double multiplier) {
            if (!(strength > 0.0D) || !(multiplier > 0.0D)) {
                return Vec3.ZERO;
            }
            return new Vec3(x * strength * multiplier, 0.0D, z * strength * multiplier);
        }
    }

    record Current(Direction direction, double strength, double speed, long updatedAt, String source, BlockPos target,
                   double vecX, double vecZ) {
        Vec3 vector(double multiplier) {
            if (!(strength > 0.0D) || !(multiplier > 0.0D)) {
                return Vec3.ZERO;
            }
            return new Vec3(
                    vecX * strength * multiplier,
                    0.0D,
                    vecZ * strength * multiplier
            );
        }
    }

    record ColumnSnapshot(BlockPos pos, Current current) {
    }

    record TopologyColumn(FlowDirective flow, int bedY) {
    }

    private record PreparedTopologyColumn(BlockPos pos, int bedY, Current current) {
    }

    private static final class FieldState {
        private final Map<Long, SurfaceSample> columns = new ConcurrentHashMap<>();
        private final SurfaceField surfaceField = new SurfaceField();
        private volatile boolean reversed;
    }

    private static final class SurfaceField {
        private static final int TILE_SHIFT = 4;
        // Keep tangent neighborhoods shorter than one visible bend. The wider reach is
        // only evidence recovery for sparse sampling; it must not become the normal resolver.
        private static final int LOCAL_RADIUS_BLOCKS = 10;
        // A newly loaded chunk may expose only one stamped surface column. Resolve
        // that sample directly; larger neighborhoods still take the bend/bed path.
        private static final int LOCAL_MIN_SAMPLES = 1;
        private static final int FALLBACK_RADIUS_BLOCKS = 20;
        private static final int FALLBACK_MIN_SAMPLES = 1;

        private final Map<Long, Bucket> buckets = new HashMap<>();

        synchronized void put(BlockPos pos, SurfaceSample sample) {
            buckets.computeIfAbsent(bucketKey(pos.getX(), pos.getZ()), ignored -> new Bucket())
                    .put(pos, sample);
        }

        synchronized void remove(BlockPos pos) {
            long bucketKey = bucketKey(pos.getX(), pos.getZ());
            Bucket bucket = buckets.get(bucketKey);
            if (bucket != null && bucket.remove(columnKey(pos)) && bucket.isEmpty()) {
                buckets.remove(bucketKey);
            }
        }

        synchronized List<SurfaceSample> removeChunk(ChunkPos chunkPos) {
            Bucket bucket = buckets.remove(chunkPos.toLong());
            return bucket == null ? List.of() : List.copyOf(bucket.samples.values());
        }

        synchronized List<SurfaceSample> removeStale(long cutoff) {
            List<SurfaceSample> removed = new ArrayList<>();
            buckets.values().removeIf(bucket -> {
                bucket.samples.values().removeIf(sample -> {
                    if (sample.current().updatedAt() >= cutoff) {
                        return false;
                    }
                    removed.add(sample);
                    return true;
                });
                return bucket.isEmpty();
            });
            return removed;
        }

        synchronized List<SurfaceSample> freshInChunk(ChunkPos chunkPos, long cutoff) {
            Bucket bucket = buckets.get(chunkPos.toLong());
            if (bucket == null) {
                return List.of();
            }
            return bucket.samples.values().stream()
                    .filter(sample -> sample.current().updatedAt() >= cutoff)
                    .toList();
        }

        synchronized List<BlockPos> positions() {
            return buckets.values().stream()
                    .flatMap(bucket -> bucket.samples.values().stream())
                    .map(sample -> BlockPos.of(sample.pos()))
                    .toList();
        }

        synchronized Optional<FlowConsensus.Axis> axisAt(
                Level level,
                BlockPos pos,
                double referenceX,
                double referenceZ
        ) {
            if (level instanceof ServerLevel server) {
                List<ChannelTangent.Sample> samples = nearbyChannelSamples(pos, cutoff(level));
                if (!samples.isEmpty()) {
                    double bed = server.getHeight(Heightmap.Types.OCEAN_FLOOR, pos.getX(), pos.getZ());
                    Optional<ChannelTangent.Axis> channel = ChannelTangent.resolve(
                            pos.getX(), pos.getZ(), bed,
                            referenceX,
                            referenceZ,
                            samples);
                    if (channel.isPresent()) {
                        ChannelTangent.Axis axis = channel.get();
                        return Optional.of(new FlowConsensus.Axis(axis.x(), axis.z()));
                    }
                }
            }
            Optional<FlowConsensus.Axis> local = axisAt(pos, LOCAL_RADIUS_BLOCKS, LOCAL_MIN_SAMPLES);
            if (local.isPresent()) {
                return local;
            }
            return axisAt(pos, FALLBACK_RADIUS_BLOCKS, FALLBACK_MIN_SAMPLES);
        }

        private List<ChannelTangent.Sample> nearbyChannelSamples(BlockPos center, long cutoff) {
            int radius = LOCAL_RADIUS_BLOCKS + 2;
            int tileRadius = (radius + ((1 << TILE_SHIFT) - 1)) >> TILE_SHIFT;
            int tileX = center.getX() >> TILE_SHIFT;
            int tileZ = center.getZ() >> TILE_SHIFT;
            List<ChannelTangent.Sample> samples = new ArrayList<>();
            int radiusSquared = radius * radius;
            for (int dx = -tileRadius; dx <= tileRadius; dx++) {
                for (int dz = -tileRadius; dz <= tileRadius; dz++) {
                    Bucket bucket = buckets.get(bucketKeyFromTile(tileX + dx, tileZ + dz));
                    if (bucket == null) {
                        continue;
                    }
                    for (SurfaceSample sample : bucket.samples.values()) {
                        if (sample.current().updatedAt() < cutoff) {
                            continue;
                        }
                        BlockPos samplePos = BlockPos.of(sample.pos());
                        int offsetX = samplePos.getX() - center.getX();
                        int offsetZ = samplePos.getZ() - center.getZ();
                        if ((offsetX * offsetX) + (offsetZ * offsetZ) <= radiusSquared) {
                            samples.add(new ChannelTangent.Sample(
                                    samplePos.getX(), samplePos.getZ(), sample.bedHeight()));
                        }
                    }
                }
            }
            return samples;
        }

        private Optional<FlowConsensus.Axis> axisAt(BlockPos pos, int radiusBlocks, long minimumSamples) {
            int tileRadius = (radiusBlocks + ((1 << TILE_SHIFT) - 1)) >> TILE_SHIFT;
            int tileX = pos.getX() >> TILE_SHIFT;
            int tileZ = pos.getZ() >> TILE_SHIFT;
            FlowConsensus.Neighborhood local = FlowConsensus.neighborhood(
                    pos.getX(), pos.getZ(), radiusBlocks);
            for (int dx = -tileRadius; dx <= tileRadius; dx++) {
                for (int dz = -tileRadius; dz <= tileRadius; dz++) {
                    Bucket bucket = buckets.get(bucketKeyFromTile(tileX + dx, tileZ + dz));
                    if (bucket != null) {
                        for (SurfaceSample sample : bucket.samples.values()) {
                            BlockPos samplePos = BlockPos.of(sample.pos());
                            Current current = sample.current();
                            local.add(samplePos.getX(), samplePos.getZ(), sample.bedHeight(),
                                    current.vecX(), current.vecZ(), current.strength());
                        }
                    }
                }
            }
            return local.resolve(minimumSamples);
        }

        synchronized void visitFresh(
                BlockPos center,
                int radius,
                long cutoff,
                java.util.function.BiConsumer<BlockPos, Current> visitor
        ) {
            int radiusSq = radius * radius;
            for (Bucket bucket : buckets.values()) {
                for (SurfaceSample sample : bucket.samples.values()) {
                    Current current = sample.current();
                    if (current.updatedAt() < cutoff) {
                        continue;
                    }
                    BlockPos pos = BlockPos.of(sample.pos());
                    int dx = pos.getX() - center.getX();
                    int dz = pos.getZ() - center.getZ();
                    if ((dx * dx) + (dz * dz) <= radiusSq) {
                        visitor.accept(pos, current);
                    }
                }
            }
        }

        private static long bucketKey(int blockX, int blockZ) {
            return bucketKeyFromTile(blockX >> TILE_SHIFT, blockZ >> TILE_SHIFT);
        }

        private static long bucketKeyFromTile(int tileX, int tileZ) {
            return ChunkPos.asLong(tileX, tileZ);
        }
    }

    private static final class Bucket {
        private final Map<Long, SurfaceSample> samples = new HashMap<>();

        void put(BlockPos pos, SurfaceSample sample) {
            samples.put(columnKey(pos), sample);
        }

        boolean remove(long columnKey) {
            if (samples.remove(columnKey) == null) {
                return false;
            }
            return true;
        }

        boolean isEmpty() {
            return samples.isEmpty();
        }

    }

    private record SurfaceSample(long pos, int bedHeight, Current current) {
    }
}
