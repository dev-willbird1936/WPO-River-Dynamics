package net.skds.wpo.river;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BiomeTags;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.Heightmap;
import net.skds.wpo.api.WPOFluidAccess;

/** Caches one topology solve for every cell in a connected surface-water component. */
final class RiverTopologyField {

    private static final int DISCOVERY_CELLS_PER_TICK = 256;
    private static final int DISCOVERY_RADIUS = 63;
    private static final int MAX_COMPONENT_CELLS = 16_384;
    private static final int MAX_QUEUED_SEEDS = 1_024;
    private static final int RIVER_BIOME_EXPANSION = 32;
    private static final long CACHE_TICKS = 1_200L;
    private static final long SWEEP_GRACE_TICKS = CACHE_TICKS * 10L;
    private static final Map<ResourceLocation, DimensionCache> DIMENSIONS = new HashMap<>();
    private static SolverRuntime runtime;

    private RiverTopologyField() {
    }

    private static Thread newSolverThread(Runnable task) {
        Thread thread = new Thread(task, "wpo-river-topology-solver");
        thread.setDaemon(true);
        return thread;
    }

    private static synchronized SolverRuntime runtime() {
        if (runtime == null) {
            runtime = new SolverRuntime();
        }
        return runtime;
    }

    /**
     * Never blocks. Answers from whatever is already cached (fresh or stale/"last-good"),
     * and kicks off an async re-solve if the cell is missing, expired, and not already
     * in flight. A brand-new cell with nothing cached yet returns unavailable until its
     * first solve completes and gets published on a later tick.
     */
    static Decision resolve(ServerLevel level, BlockPos source) {
        DimensionCache cache = DIMENSIONS.computeIfAbsent(level.dimension().location(), ignored -> new DimensionCache());
        CachedCell cached = cache.cells.get(source.asLong());
        boolean fresh = cached != null && cached.expiresAt >= level.getGameTime();
        if (!fresh) {
            cache.request(source, level.getGameTime());
        }
        return cached == null ? Decision.unavailable() : decision(level, source, cached, cache);
    }

    /** Advances bounded server-thread discovery and publishes background solves. */
    static void tick(ServerLevel level) {
        SolverRuntime active = runtime();
        SolveCompletion completion;
        while ((completion = active.completed.poll()) != null) {
            ResourceLocation dimensionId = completion.failure() == null
                    ? completion.outcome().dimension()
                    : completion.job().dimension();
            ResourceKey<Level> dimension = ResourceKey.create(Registries.DIMENSION, dimensionId);
            ServerLevel targetLevel = level.getServer().getLevel(dimension);
            if (targetLevel == null) {
                continue;
            }
            if (completion.failure() != null) {
                fail(targetLevel, completion.job(), completion.failure());
            } else {
                publish(targetLevel, completion.outcome());
            }
        }

        DimensionCache cache = DIMENSIONS.computeIfAbsent(level.dimension().location(), ignored -> new DimensionCache());
        if (cache.discovery == null && !cache.solving) {
            startNextDiscovery(level, cache);
        }
        if (cache.discovery != null) {
            advanceDiscovery(level, cache, DISCOVERY_CELLS_PER_TICK);
        }
    }

    static void sweep(ServerLevel level) {
        DimensionCache cache = DIMENSIONS.get(level.dimension().location());
        if (cache == null) {
            return;
        }
        long cutoff = level.getGameTime() - SWEEP_GRACE_TICKS;
        cache.cells.entrySet().removeIf(entry -> entry.getValue().expiresAt < cutoff);
        Set<Long> liveComponents = new HashSet<>();
        for (CachedCell cell : cache.cells.values()) {
            liveComponents.add(cell.componentId);
        }
        cache.orientations.keySet().retainAll(liveComponents);
        cache.waitingComponents.values().removeIf(components -> {
            components.retainAll(liveComponents);
            return components.isEmpty();
        });
        cache.componentEpoch.keySet().retainAll(liveComponents);
        cache.componentCells.keySet().retainAll(liveComponents);
        cache.componentWaitingChunks.keySet().retainAll(liveComponents);
        cache.retryAfter.entrySet().removeIf(entry -> entry.getValue() <= level.getGameTime());
    }

    static void onChunkLoad(ServerLevel level, ChunkPos chunkPos) {
        DimensionCache cache = DIMENSIONS.get(level.dimension().location());
        if (cache == null) {
            return;
        }
        Set<Long> components = cache.waitingComponents.remove(chunkPos.toLong());
        if (components == null || components.isEmpty()) {
            return;
        }
        long expiredAt = level.getGameTime() - 1L;
        for (long componentId : components) {
            cache.componentEpoch.merge(componentId, 1L, Long::sum);
            BlockPos seed = null;
            for (long key : cache.componentCells.getOrDefault(componentId, Set.of())) {
                CachedCell cell = cache.cells.get(key);
                if (cell == null || cell.componentId() != componentId) {
                    continue;
                }
                cache.cells.put(key, new CachedCell(
                        cell.componentId(), cell.state(), cell.vectorX(), cell.vectorZ(),
                        cell.potential(), cell.speed(), cell.next(), cell.reverseNext(), expiredAt));
                BlockPos pos = BlockPos.of(key);
                if (seed == null && WPOFluidAccess.isChunkLoaded(level, pos)
                        && WPOFluidAccess.getWaterAmount(level, pos) > 0) {
                    seed = pos;
                }
            }
            for (long waitingChunk : cache.componentWaitingChunks.getOrDefault(componentId, Set.of())) {
                Set<Long> waiting = cache.waitingComponents.get(waitingChunk);
                if (waiting != null) {
                    waiting.remove(componentId);
                    if (waiting.isEmpty()) {
                        cache.waitingComponents.remove(waitingChunk);
                    }
                }
            }
            cache.componentWaitingChunks.remove(componentId);
            if (seed != null) {
                cache.request(seed, level.getGameTime());
            }
        }
    }

    static void clear(ServerLevel level) {
        DIMENSIONS.remove(level.dimension().location());
    }

    static synchronized void shutdown() {
        SolverRuntime active = runtime;
        runtime = null;
        DIMENSIONS.clear();
        if (active != null) {
            active.executor.shutdownNow();
            active.completed.clear();
        }
    }

    private static void startNextDiscovery(ServerLevel level, DimensionCache cache) {
        if (cache.solveRetryAfter > level.getGameTime()) {
            return;
        }
        for (int checked = 0; checked < 64 && !cache.requests.isEmpty(); checked++) {
            BlockPos seed = cache.requests.removeFirst();
            cache.queued.remove(seed.asLong());
            CachedCell existing = cache.cells.get(seed.asLong());
            if (existing != null && existing.expiresAt >= level.getGameTime()) {
                continue;
            }
            if (!WPOFluidAccess.isChunkLoaded(level, seed)
                    || WPOFluidAccess.getWaterAmount(level, seed) <= 0) {
                continue;
            }
            if (level.getBiome(seed).is(BiomeTags.IS_OCEAN)) {
                long componentId = cache.nextComponentId++;
                cache.cells.put(seed.asLong(), new CachedCell(
                        componentId, RiverPotentialSolver.State.IDLE,
                        0.0D, 0.0D, 0.0D, 0.0D, null, null,
                        level.getGameTime() + CACHE_TICKS));
                cache.componentCells.put(componentId, new HashSet<>(Set.of(seed.asLong())));
                RiverCurrentField.clear(level, seed);
                continue;
            }
            Discovery discovery = new Discovery(seed);
            addDiscoveryCell(level, discovery, seed, true);
            cache.discovery = discovery;
            return;
        }
    }

    private static void advanceDiscovery(ServerLevel level, DimensionCache cache, int budget) {
        Discovery discovery = cache.discovery;
        int examined = 0;
        while (examined < budget && !discovery.frontier.isEmpty() && !discovery.hitLimit) {
            BlockPos cell = discovery.frontier.removeFirst();
            examined++;
            for (Direction direction : Direction.Plane.HORIZONTAL) {
                int x = cell.getX() + direction.getStepX();
                int z = cell.getZ() + direction.getStepZ();
                BlockPos chunkCheck = new BlockPos(x, cell.getY(), z);
                if (!WPOFluidAccess.isChunkLoaded(level, chunkCheck)) {
                    discovery.addOpenEdge(cell, chunkCheck, direction, true);
                    continue;
                }
                BlockPos neighbor = RiverTicker.surfaceWaterNear(level, x, z, cell.getY(), 4);
                if (neighbor == null || Math.abs(neighbor.getY() - cell.getY()) > 4
                        || WPOFluidAccess.getWaterAmount(level, neighbor) <= 0
                        || !WPOFluidAccess.canWaterFlowBetween(level, cell, neighbor)) {
                    continue;
                }
                if (Math.max(
                        Math.abs(neighbor.getX() - discovery.seed.getX()),
                        Math.abs(neighbor.getZ() - discovery.seed.getZ())) > DISCOVERY_RADIUS) {
                    discovery.addOpenEdge(cell, neighbor, direction, false);
                    continue;
                }
                if (discovery.cells.containsKey(RiverPotentialSolver.key(neighbor.getX(), neighbor.getZ()))) {
                    continue;
                }
                if (discovery.cells.size() >= MAX_COMPONENT_CELLS) {
                    discovery.hitLimit = true;
                    break;
                }
                boolean ocean = level.getBiome(neighbor).is(BiomeTags.IS_OCEAN);
                addDiscoveryCell(level, discovery, neighbor, !ocean);
                if (ocean) {
                    discovery.terminalColumns.add(RiverPotentialSolver.key(neighbor.getX(), neighbor.getZ()));
                }
            }
        }
        if (discovery.frontier.isEmpty() || discovery.hitLimit) {
            finishDiscovery(level, cache, discovery);
        }
    }

    private static void addDiscoveryCell(ServerLevel level, Discovery discovery, BlockPos pos, boolean traverse) {
        long columnKey = RiverPotentialSolver.key(pos.getX(), pos.getZ());
        if (discovery.cells.putIfAbsent(columnKey, new RiverPotentialSolver.Cell(
                pos.getX(), pos.getZ(), pos.getY(),
                level.getHeight(Heightmap.Types.OCEAN_FLOOR, pos.getX(), pos.getZ()))) != null) {
            return;
        }
        BlockPos stable = pos.immutable();
        discovery.positionsByColumn.put(columnKey, stable);
        discovery.touchedCellKeys.add(stable.asLong());
        if (level.getBiome(pos).is(BiomeTags.IS_RIVER)) {
            discovery.riverSeeds.add(columnKey);
        }
        if (traverse) {
            discovery.frontier.addLast(stable);
        }
    }

    private static void finishDiscovery(ServerLevel level, DimensionCache cache, Discovery discovery) {
        cache.discovery = null;
        if (discovery.cells.size() < 2) {
            if (discovery.openEdges.isEmpty()) {
                cacheIdle(level, cache, discovery.touchedCellKeys);
            } else {
                long componentId = cacheUnresolved(level, cache, discovery.touchedCellKeys);
                registerWaitingEdges(level, cache, componentId,
                        discovery.openEdges, discovery.cells.keySet());
            }
            return;
        }
        if (discovery.hitLimit) {
            long componentId = cacheUnresolved(level, cache, discovery.touchedCellKeys);
            registerWaitingEdges(level, cache, componentId,
                    discovery.openEdges, discovery.cells.keySet());
            return;
        }

        Map<Long, Long> priorComponentByColumn = new HashMap<>();
        Set<Long> replacedComponents = new HashSet<>();
        for (Map.Entry<Long, BlockPos> entry : discovery.positionsByColumn.entrySet()) {
            CachedCell previous = cache.cells.get(entry.getValue().asLong());
            if (previous != null) {
                priorComponentByColumn.put(entry.getKey(), previous.componentId);
                replacedComponents.add(previous.componentId);
            }
        }
        Map<Long, RiverPotentialSolver.Orientation> priorOrientations = new HashMap<>();
        Map<Long, Long> priorEpochs = new HashMap<>();
        for (long componentId : replacedComponents) {
            RiverPotentialSolver.Orientation orientation = cache.orientations.get(componentId);
            if (orientation != null) {
                priorOrientations.put(componentId, orientation);
            }
            priorEpochs.put(componentId, cache.componentEpoch.getOrDefault(componentId, 0L));
        }

        PendingSolve job = new PendingSolve(
                level.dimension().location(), cache,
                Map.copyOf(discovery.cells), Map.copyOf(discovery.positionsByColumn), Set.copyOf(discovery.riverSeeds),
                Map.copyOf(priorComponentByColumn), Map.copyOf(priorOrientations), Map.copyOf(priorEpochs),
                Set.copyOf(discovery.touchedCellKeys), Set.copyOf(discovery.terminalColumns),
                freezeEdges(discovery.openEdges), discovery.seed);
        cache.pending.addAll(discovery.touchedCellKeys);
        cache.solving = true;
        SolverRuntime active = runtime();
        try {
            active.executor.execute(() -> {
                try {
                    active.completed.add(new SolveCompletion(job, solveOffThread(job), null));
                } catch (Throwable failure) {
                    active.completed.add(new SolveCompletion(job, null, failure));
                }
            });
        } catch (RejectedExecutionException failure) {
            fail(level, job, failure);
        }
    }

    private static Map<Long, List<OpenEdge>> freezeEdges(Map<Long, List<OpenEdge>> edges) {
        Map<Long, List<OpenEdge>> frozen = new HashMap<>();
        for (Map.Entry<Long, List<OpenEdge>> entry : edges.entrySet()) {
            frozen.put(entry.getKey(), List.copyOf(entry.getValue()));
        }
        return Map.copyOf(frozen);
    }

    private static void registerWaitingEdges(
            ServerLevel level,
            DimensionCache cache,
            long componentId,
            Map<Long, List<OpenEdge>> openEdges,
            Iterable<Long> columns
    ) {
        for (long columnKey : columns) {
            for (OpenEdge edge : openEdges.getOrDefault(columnKey, List.of())) {
                if (!edge.unloaded() || WPOFluidAccess.isChunkLoaded(level, edge.target())) {
                    continue;
                }
                long chunkKey = ChunkPos.asLong(edge.target().getX() >> 4, edge.target().getZ() >> 4);
                cache.waitingComponents.computeIfAbsent(chunkKey, ignored -> new HashSet<>())
                        .add(componentId);
                cache.componentWaitingChunks.computeIfAbsent(componentId, ignored -> new HashSet<>())
                        .add(chunkKey);
            }
        }
    }

    private static void registerComponentCells(
            DimensionCache cache,
            long componentId,
            Iterable<Long> columnKeys,
            Map<Long, BlockPos> positionsByColumn
    ) {
        Set<Long> cells = cache.componentCells.computeIfAbsent(componentId, ignored -> new HashSet<>());
        for (long columnKey : columnKeys) {
            BlockPos pos = positionsByColumn.get(columnKey);
            if (pos != null) {
                cells.add(pos.asLong());
            }
        }
    }

    /** Replaces degenerate/emergency snapshots with confirmed idle state and clears stale current. */
    private static void cacheIdle(ServerLevel level, DimensionCache cache, Set<Long> keys) {
        long expiresAt = level.getGameTime() + CACHE_TICKS;
        long componentId = cache.nextComponentId++;
        Set<Long> componentCells = cache.componentCells.computeIfAbsent(componentId, ignored -> new HashSet<>());
        for (long key : keys) {
            cache.cells.put(key, new CachedCell(
                    componentId, RiverPotentialSolver.State.IDLE,
                    0.0D, 0.0D, 0.0D, 0.0D, null, null, expiresAt));
            RiverCurrentField.clear(level, BlockPos.of(key));
            componentCells.add(key);
        }
    }

    private static long cacheUnresolved(ServerLevel level, DimensionCache cache, Set<Long> keys) {
        long expiresAt = level.getGameTime() + CACHE_TICKS;
        long componentId = cache.nextComponentId++;
        Set<Long> componentCells = cache.componentCells.computeIfAbsent(componentId, ignored -> new HashSet<>());
        for (long key : keys) {
            cache.cells.put(key, new CachedCell(
                    componentId, RiverPotentialSolver.State.UNAVAILABLE,
                    0.0D, 0.0D, 0.0D, 0.0D, null, null, expiresAt));
            RiverCurrentField.clear(level, BlockPos.of(key));
            componentCells.add(key);
        }
        return componentId;
    }

    private static void fail(ServerLevel level, PendingSolve job, Throwable failure) {
        if (DIMENSIONS.get(job.dimension()) != job.cache()) {
            return;
        }
        job.cache().solving = false;
        job.cache().pending.removeAll(job.touchedCellKeys());
        job.cache().solveRetryAfter = level.getGameTime() + CACHE_TICKS;
        job.cache().backoff(job.touchedCellKeys(), level.getGameTime() + CACHE_TICKS);
        RiverDynamics.LOGGER.error("River topology solve failed for {} at {}", job.dimension(), job.seed(), failure);
    }

    /** Off server thread: pure value-type inputs in, pure value-type outcome out. */
    private static SolveOutcome solveOffThread(PendingSolve job) {
        Set<Long> solveMask = job.riverSeeds().isEmpty()
                ? new HashSet<>(job.cells().keySet())
                : expandFromSeeds(job.cells().keySet(), job.riverSeeds(), RIVER_BIOME_EXPANSION);

        List<RegionOutcome> flowingRegions = new ArrayList<>();
        List<IdleMemory> rememberedIdleRegions = new ArrayList<>();
        Set<Long> unresolvedColumns = new HashSet<>();
        for (Set<Long> region : connectedRegions(solveMask)) {
            List<RiverPotentialSolver.Cell> regionCells = new ArrayList<>(region.size());
            boolean riverHint = false;
            for (long columnKey : region) {
                regionCells.add(job.cells().get(columnKey));
                riverHint |= job.riverSeeds().contains(columnKey);
            }

            // Preserve whichever prior anchor pair this region overlaps most, so a cache
            // rebuild (expiry, or expansion/merge touching this region) cannot flip polarity
            // on its own - only a genuine topology change (new endpoints entirely) can.
            RiverPotentialSolver.Orientation preferredOrientation = RiverPotentialSolver.previousOrientationFor(
                    region, job.priorComponentByColumn(), job.priorOrientations());
            Set<Long> openColumns = new HashSet<>(job.openEdges().keySet());
            openColumns.retainAll(region);

            RiverPotentialSolver.Result result = RiverPotentialSolver.solve(
                    regionCells, riverHint ? 1.35D : 4.0D, preferredOrientation, openColumns);
            if (result.state() == RiverPotentialSolver.State.IDLE) {
                if (!openColumns.isEmpty()
                        && (region.size() < 8 || riverHint || preferredOrientation != null)) {
                    for (long columnKey : region) {
                        if (!job.terminalColumns().contains(columnKey)) {
                            unresolvedColumns.add(columnKey);
                        }
                    }
                    continue;
                }
                // Extent churn in the bounded flood-fill can dip a body's diameter/width ratio
                // below the idle threshold for one cycle with no real change in the world. If it
                // still carries a prior orientation, keep that memory alive under a real
                // componentId (see publish()) instead of letting it fall out of priorOrientations
                // entirely - otherwise the next cycle that crosses back over the threshold
                // cold-starts orientation from scratch and can pick the opposite direction.
                if (preferredOrientation != null) {
                    rememberedIdleRegions.add(new IdleMemory(new ArrayList<>(region), preferredOrientation));
                }
                continue;
            }
            Map<Long, BlockPos> externalTargets = new HashMap<>();
            OpenEdge outletEdge = selectOutletEdge(result, job.openEdges());
            if (outletEdge != null) {
                long from = outletEdge.fromColumn();
                RiverPotentialSolver.Flow nearest = nearestFlow(result, from);
                RiverPotentialSolver.Cell cell = job.cells().get(from);
                double speed = nearest == null
                        ? RiverHydraulics.speed(
                                2.0D,
                                cell == null ? 1.0D : Math.max(1.0D, cell.surfaceY() - cell.bedY() + 1.0D),
                                0.0D)
                        : nearest.speed();
                Map<Long, RiverPotentialSolver.Flow> flows = new HashMap<>(result.flows());
                flows.put(from, new RiverPotentialSolver.Flow(
                        outletEdge.direction().getStepX(),
                        outletEdge.direction().getStepZ(),
                        RiverPotentialSolver.key(outletEdge.target().getX(), outletEdge.target().getZ()),
                        Long.MIN_VALUE,
                        result.potentials().getOrDefault(from, 0.0D),
                        speed));
                result = RiverPotentialSolver.Result.flowing(
                        flows, result.potentials(), result.orientation(), result.openOutlets());
                externalTargets.put(from, outletEdge.target());
            }
            flowingRegions.add(new RegionOutcome(new ArrayList<>(region), result, Map.copyOf(externalTargets)));
        }

        return new SolveOutcome(
                job.dimension(), job.cache(),
                job.cells(), job.positionsByColumn(),
                flowingRegions, rememberedIdleRegions, job.cells().keySet(),
                job.touchedCellKeys(), job.terminalColumns(), unresolvedColumns,
                job.openEdges(), job.seed(), job.priorEpochs());
    }

    /** Server-thread-only: applies a completed solve atomically, replacing the old data it supersedes. */
    private static void publish(ServerLevel level, SolveOutcome outcome) {
        if (DIMENSIONS.get(outcome.dimension()) != outcome.cache()) {
            return; // Dimension was cleared/reloaded since this job was submitted - drop it.
        }
        DimensionCache cache = outcome.cache();
        boolean loadedBoundaryChanged = outcome.openEdges().values().stream()
                .flatMap(List::stream)
                .anyMatch(edge -> edge.unloaded() && WPOFluidAccess.isChunkLoaded(level, edge.target()));
        boolean priorComponentInvalidated = outcome.priorEpochs().entrySet().stream()
                .anyMatch(entry -> cache.componentEpoch.getOrDefault(entry.getKey(), 0L)
                        .longValue() != entry.getValue().longValue());
        if (priorComponentInvalidated || loadedBoundaryChanged) {
            cache.solving = false;
            cache.pending.removeAll(outcome.touchedCellKeys());
            long expiredAt = level.getGameTime() - 1L;
            for (long key : outcome.touchedCellKeys()) {
                CachedCell cell = cache.cells.get(key);
                if (cell != null) {
                    cache.cells.put(key, new CachedCell(
                            cell.componentId(), cell.state(), cell.vectorX(), cell.vectorZ(),
                            cell.potential(), cell.speed(), cell.next(), cell.reverseNext(), expiredAt));
                }
            }
            cache.request(outcome.seed(), level.getGameTime());
            return;
        }
        cache.solveRetryAfter = 0L;
        long expiresAt = level.getGameTime() + CACHE_TICKS;
        // Replace only the columns this solve actually re-discovered. A smaller-extent solve
        // (a different source column, a narrower bounded flood-fill) must not erase cached
        // state - and hysteresis memory - for the part of a replaced component it didn't
        // re-cover; that erasure is what let orientation flip over time on large bodies
        // sampled from many different source columns. Orientations for components fully
        // subsumed here become unreferenced and get swept by sweep()'s liveness pass.

        Map<BlockPos, RiverCurrentField.TopologyColumn> publishedFlows = new HashMap<>();
        Map<Long, Long> rememberedIdleComponent = new HashMap<>();
        for (IdleMemory idleRegion : outcome.rememberedIdleRegions()) {
            long componentId = cache.nextComponentId++;
            cache.orientations.put(componentId, idleRegion.orientation());
            registerComponentCells(cache, componentId, idleRegion.columnKeys(), outcome.positionsByColumn());
            registerWaitingEdges(level, cache, componentId, outcome.openEdges(), idleRegion.columnKeys());
            for (long columnKey : idleRegion.columnKeys()) {
                rememberedIdleComponent.put(columnKey, componentId);
            }
        }

        long idleComponentId = cache.nextComponentId++;
        for (long columnKey : outcome.allDiscoveredColumnKeys()) {
            BlockPos pos = outcome.positionsByColumn().get(columnKey);
            long componentId = rememberedIdleComponent.getOrDefault(columnKey, idleComponentId);
            cache.cells.put(pos.asLong(), new CachedCell(
                    componentId, RiverPotentialSolver.State.IDLE,
                    0.0D, 0.0D, 0.0D, 0.0D, null, null, expiresAt));
        }
        if (!outcome.unresolvedColumns().isEmpty()) {
            long unresolvedComponentId = cache.nextComponentId++;
            registerComponentCells(cache, unresolvedComponentId,
                    outcome.unresolvedColumns(), outcome.positionsByColumn());
            registerWaitingEdges(level, cache, unresolvedComponentId,
                    outcome.openEdges(), outcome.unresolvedColumns());
            for (long columnKey : outcome.unresolvedColumns()) {
                if (outcome.terminalColumns().contains(columnKey)) {
                    continue;
                }
                BlockPos pos = outcome.positionsByColumn().get(columnKey);
                cache.cells.put(pos.asLong(), new CachedCell(
                        unresolvedComponentId, RiverPotentialSolver.State.UNAVAILABLE,
                        0.0D, 0.0D, 0.0D, 0.0D, null, null, expiresAt));
            }
        }

        for (RegionOutcome region : outcome.flowingRegions()) {
            long componentId = cache.nextComponentId++;
            cache.orientations.put(componentId, region.result().orientation());
            registerComponentCells(cache, componentId, region.columnKeys(), outcome.positionsByColumn());
            registerWaitingEdges(level, cache, componentId, outcome.openEdges(), region.columnKeys());
            for (long columnKey : region.columnKeys()) {
                if (outcome.terminalColumns().contains(columnKey)) {
                    continue;
                }
                BlockPos pos = outcome.positionsByColumn().get(columnKey);
                RiverPotentialSolver.Flow flow = region.result().flows().get(columnKey);
                double potential = region.result().potentials().getOrDefault(columnKey, 0.0D);
                BlockPos next = flow == null ? null : outcome.positionsByColumn().get(flow.next());
                if (next == null) {
                    next = region.externalTargets().get(columnKey);
                }
                BlockPos reverseNext = flow == null || flow.reverseNext() == Long.MIN_VALUE
                        ? null : outcome.positionsByColumn().get(flow.reverseNext());
                cache.cells.put(pos.asLong(), new CachedCell(
                        componentId,
                        region.result().state(),
                        flow == null ? 0.0D : flow.x(),
                        flow == null ? 0.0D : flow.z(),
                        potential,
                        flow == null ? 0.0D : flow.speed(),
                        next == null ? null : next.immutable(),
                        reverseNext == null ? null : reverseNext.immutable(),
                        expiresAt));
                if (flow != null && next != null && WPOFluidAccess.isChunkLoaded(level, pos)) {
                    RiverPotentialSolver.Cell sampled = outcome.cells().get(columnKey);
                    int bedY = sampled == null ? pos.getY() - 1 : sampled.bedY();
                    publishedFlows.put(pos.immutable(), new RiverCurrentField.TopologyColumn(
                            new FlowDirective(
                                    cardinal(flow.x(), flow.z()), next.immutable(), "topology",
                                    flow.x(), flow.z(), flow.speed()),
                            bedY));
                }
            }
        }
        List<Long> idleColumns = new ArrayList<>();
        for (long columnKey : outcome.allDiscoveredColumnKeys()) {
            BlockPos pos = outcome.positionsByColumn().get(columnKey);
            CachedCell cell = cache.cells.get(pos.asLong());
            if (cell != null && cell.componentId() == idleComponentId) {
                idleColumns.add(columnKey);
            }
        }
        registerComponentCells(cache, idleComponentId, idleColumns, outcome.positionsByColumn());
        registerWaitingEdges(level, cache, idleComponentId, outcome.openEdges(), idleColumns);
        List<BlockPos> touchedPositions = new ArrayList<>(outcome.allDiscoveredColumnKeys().size());
        for (long columnKey : outcome.allDiscoveredColumnKeys()) {
            BlockPos pos = outcome.positionsByColumn().get(columnKey);
            touchedPositions.add(pos);
        }
        RiverCurrentField.replaceTopologyComponent(level, touchedPositions, publishedFlows);
        cache.pending.removeAll(outcome.touchedCellKeys());
        for (long key : outcome.touchedCellKeys()) {
            cache.retryAfter.remove(key);
        }
        cache.solving = false;
        cache.discardFreshRequests(level.getGameTime());
    }

    private static OpenEdge selectOutletEdge(
            RiverPotentialSolver.Result result,
            Map<Long, List<OpenEdge>> openEdges
    ) {
        OpenEdge best = null;
        double bestAlignment = -Double.MAX_VALUE;
        for (long outletColumn : result.openOutlets()) {
            RiverPotentialSolver.Flow nearby = nearestFlow(result, outletColumn);
            if (nearby == null) {
                continue;
            }
            for (OpenEdge edge : openEdges.getOrDefault(outletColumn, List.of())) {
                double alignment = (nearby.x() * edge.direction().getStepX())
                        + (nearby.z() * edge.direction().getStepZ());
                if (best == null || alignment > bestAlignment + 1.0E-7D
                        || (Math.abs(alignment - bestAlignment) <= 1.0E-7D
                                && edge.target().asLong() < best.target().asLong())) {
                    best = edge;
                    bestAlignment = alignment;
                }
            }
        }
        return bestAlignment > 0.5D ? best : null;
    }

    private static RiverPotentialSolver.Flow nearestFlow(RiverPotentialSolver.Result result, long column) {
        RiverPotentialSolver.Flow exact = result.flows().get(column);
        if (exact != null) {
            return exact;
        }
        int x = RiverPotentialSolver.x(column);
        int z = RiverPotentialSolver.z(column);
        RiverPotentialSolver.Flow best = null;
        int bestDistance = Integer.MAX_VALUE;
        long bestKey = Long.MAX_VALUE;
        for (Map.Entry<Long, RiverPotentialSolver.Flow> entry : result.flows().entrySet()) {
            int distance = Math.abs(RiverPotentialSolver.x(entry.getKey()) - x)
                    + Math.abs(RiverPotentialSolver.z(entry.getKey()) - z);
            if (distance < bestDistance || (distance == bestDistance && entry.getKey() < bestKey)) {
                best = entry.getValue();
                bestDistance = distance;
                bestKey = entry.getKey();
            }
        }
        return best;
    }

    private static Set<Long> expandFromSeeds(Set<Long> cells, Set<Long> seeds, int radius) {
        Set<Long> expanded = new HashSet<>();
        Map<Long, Integer> distance = new HashMap<>();
        ArrayDeque<Long> queue = new ArrayDeque<>();
        for (long seed : seeds) {
            if (cells.contains(seed) && distance.putIfAbsent(seed, 0) == null) {
                expanded.add(seed);
                queue.addLast(seed);
            }
        }
        while (!queue.isEmpty()) {
            long cell = queue.removeFirst();
            int d = distance.get(cell);
            if (d >= radius) {
                continue;
            }
            int x = RiverPotentialSolver.x(cell);
            int z = RiverPotentialSolver.z(cell);
            for (Direction direction : Direction.Plane.HORIZONTAL) {
                long neighbor = RiverPotentialSolver.key(
                        x + direction.getStepX(), z + direction.getStepZ());
                if (cells.contains(neighbor) && distance.putIfAbsent(neighbor, d + 1) == null) {
                    expanded.add(neighbor);
                    queue.addLast(neighbor);
                }
            }
        }
        return expanded;
    }

    private static List<Set<Long>> connectedRegions(Set<Long> mask) {
        List<Set<Long>> regions = new ArrayList<>();
        Set<Long> unseen = new HashSet<>(mask);
        while (!unseen.isEmpty()) {
            long start = unseen.iterator().next();
            Set<Long> region = new HashSet<>();
            ArrayDeque<Long> queue = new ArrayDeque<>();
            unseen.remove(start);
            queue.add(start);
            while (!queue.isEmpty()) {
                long cell = queue.removeFirst();
                region.add(cell);
                int x = RiverPotentialSolver.x(cell);
                int z = RiverPotentialSolver.z(cell);
                for (Direction direction : Direction.Plane.HORIZONTAL) {
                    long neighbor = RiverPotentialSolver.key(
                            x + direction.getStepX(), z + direction.getStepZ());
                    if (unseen.remove(neighbor)) {
                        queue.addLast(neighbor);
                    }
                }
            }
            regions.add(region);
        }
        return regions;
    }

    private static Decision decision(ServerLevel level, BlockPos source, CachedCell cell, DimensionCache cache) {
        if (cell.state == RiverPotentialSolver.State.UNAVAILABLE) {
            return Decision.unavailable();
        }
        if (cell.state != RiverPotentialSolver.State.FLOWING) {
            return Decision.idle();
        }

        double vx = cell.vectorX;
        double vz = cell.vectorZ;
        BlockPos target = cell.next;

        if ((Math.abs(vx) + Math.abs(vz)) < 1.0E-7D) {
            double[] gradient = potentialGradient(source, cell, cache, false);
            vx = gradient[0];
            vz = gradient[1];
        }
        if (target == null) {
            target = potentialTarget(source, cell, cache, false);
        }
        if (target == null || (Math.abs(vx) + Math.abs(vz)) < 1.0E-7D) {
            return Decision.idle();
        }

        Direction direction = cardinal(vx, vz);
        return Decision.flowing(new FlowDirective(direction, target, "topology", vx, vz, cell.speed));
    }

    private static BlockPos potentialTarget(BlockPos source, CachedCell cell, DimensionCache cache, boolean increasing) {
        BlockPos best = null;
        double bestDelta = 1.0E-7D;
        for (Direction direction : Direction.Plane.HORIZONTAL) {
            for (int dy = -4; dy <= 4; dy++) {
                BlockPos candidate = source.relative(direction).offset(0, dy, 0);
                CachedCell neighbor = cache.cells.get(candidate.asLong());
                if (neighbor == null || neighbor.componentId != cell.componentId) {
                    continue;
                }
                double delta = increasing
                        ? neighbor.potential - cell.potential
                        : cell.potential - neighbor.potential;
                if (delta > bestDelta) {
                    bestDelta = delta;
                    best = candidate;
                }
            }
        }
        return best;
    }

    private static double[] potentialGradient(BlockPos source, CachedCell cell, DimensionCache cache, boolean increasing) {
        double vx = 0.0D;
        double vz = 0.0D;
        for (Direction direction : Direction.Plane.HORIZONTAL) {
            for (int dy = -4; dy <= 4; dy++) {
                BlockPos candidate = source.relative(direction).offset(0, dy, 0);
                CachedCell neighbor = cache.cells.get(candidate.asLong());
                if (neighbor == null || neighbor.componentId != cell.componentId) {
                    continue;
                }
                double delta = increasing
                        ? neighbor.potential - cell.potential
                        : cell.potential - neighbor.potential;
                if (delta > 1.0E-7D) {
                    vx += direction.getStepX() * delta;
                    vz += direction.getStepZ() * delta;
                }
                break;
            }
        }
        double length = Math.sqrt((vx * vx) + (vz * vz));
        return length < 1.0E-7D ? new double[] { 0.0D, 0.0D } : new double[] { vx / length, vz / length };
    }

    private static Direction cardinal(double x, double z) {
        if (Math.abs(x) >= Math.abs(z)) {
            return x >= 0.0D ? Direction.EAST : Direction.WEST;
        }
        return z >= 0.0D ? Direction.SOUTH : Direction.NORTH;
    }

    record Decision(boolean resolved, FlowDirective flow) {
        static Decision flowing(FlowDirective flow) {
            return new Decision(true, flow);
        }

        static Decision idle() {
            return new Decision(true, null);
        }

        static Decision unavailable() {
            return new Decision(false, null);
        }
    }

    private static final class DimensionCache {
        private final Map<Long, CachedCell> cells = new HashMap<>();
        private final Map<Long, RiverPotentialSolver.Orientation> orientations = new HashMap<>();
        private final Set<Long> pending = new HashSet<>();
        private final ArrayDeque<BlockPos> requests = new ArrayDeque<>();
        private final Set<Long> queued = new HashSet<>();
        private final Map<Long, Long> retryAfter = new HashMap<>();
        private final Map<Long, Set<Long>> waitingComponents = new HashMap<>();
        private final Map<Long, Set<Long>> componentCells = new HashMap<>();
        private final Map<Long, Set<Long>> componentWaitingChunks = new HashMap<>();
        private final Map<Long, Long> componentEpoch = new HashMap<>();
        private Discovery discovery;
        private boolean solving;
        private long solveRetryAfter;
        private long nextComponentId = 1L;

        private void request(BlockPos pos, long now) {
            Long retry = retryAfter.get(pos.asLong());
            if (retry != null) {
                if (retry > now) {
                    return;
                }
                retryAfter.remove(pos.asLong());
            }
            if ((discovery != null && discovery.cells.containsKey(RiverPotentialSolver.key(pos.getX(), pos.getZ())))
                    || queued.size() >= MAX_QUEUED_SEEDS
                    || pending.contains(pos.asLong())
                    || !queued.add(pos.asLong())) {
                return;
            }
            requests.addLast(pos.immutable());
        }

        private void discardFreshRequests(long now) {
            requests.removeIf(pos -> {
                CachedCell cached = cells.get(pos.asLong());
                if (cached == null || cached.expiresAt < now) {
                    return false;
                }
                queued.remove(pos.asLong());
                return true;
            });
        }

        private void backoff(Set<Long> keys, long until) {
            for (long key : keys) {
                retryAfter.put(key, until);
            }
            requests.removeIf(pos -> {
                if (!keys.contains(pos.asLong())) {
                    return false;
                }
                queued.remove(pos.asLong());
                return true;
            });
        }
    }

    private static final class Discovery {
        private final BlockPos seed;
        private final Map<Long, RiverPotentialSolver.Cell> cells = new HashMap<>();
        private final Map<Long, BlockPos> positionsByColumn = new HashMap<>();
        private final Set<Long> riverSeeds = new HashSet<>();
        private final Set<Long> touchedCellKeys = new HashSet<>();
        private final Set<Long> terminalColumns = new HashSet<>();
        private final ArrayDeque<BlockPos> frontier = new ArrayDeque<>();
        private final Map<Long, List<OpenEdge>> openEdges = new HashMap<>();
        private boolean hitLimit;

        private Discovery(BlockPos seed) {
            this.seed = seed.immutable();
        }

        private void addOpenEdge(BlockPos from, BlockPos target, Direction direction, boolean unloaded) {
            long fromColumn = RiverPotentialSolver.key(from.getX(), from.getZ());
            OpenEdge edge = new OpenEdge(fromColumn, target.immutable(), direction, unloaded);
            List<OpenEdge> edges = openEdges.computeIfAbsent(fromColumn, ignored -> new ArrayList<>());
            if (!edges.contains(edge)) {
                edges.add(edge);
            }
        }
    }

    private record CachedCell(
            long componentId,
            RiverPotentialSolver.State state,
            double vectorX,
            double vectorZ,
            double potential,
            double speed,
            BlockPos next,
            BlockPos reverseNext,
            long expiresAt
    ) {
    }

    private record PendingSolve(
            ResourceLocation dimension,
            DimensionCache cache,
            Map<Long, RiverPotentialSolver.Cell> cells,
            Map<Long, BlockPos> positionsByColumn,
            Set<Long> riverSeeds,
            Map<Long, Long> priorComponentByColumn,
            Map<Long, RiverPotentialSolver.Orientation> priorOrientations,
            Map<Long, Long> priorEpochs,
            Set<Long> touchedCellKeys,
            Set<Long> terminalColumns,
            Map<Long, List<OpenEdge>> openEdges,
            BlockPos seed
    ) {
    }

    private static final class SolverRuntime {
        private final ExecutorService executor = Executors.newSingleThreadExecutor(RiverTopologyField::newSolverThread);
        private final ConcurrentLinkedQueue<SolveCompletion> completed = new ConcurrentLinkedQueue<>();
    }

    private record SolveCompletion(PendingSolve job, SolveOutcome outcome, Throwable failure) {
    }

    private record RegionOutcome(
            List<Long> columnKeys,
            RiverPotentialSolver.Result result,
            Map<Long, BlockPos> externalTargets
    ) {
    }

    private record OpenEdge(long fromColumn, BlockPos target, Direction direction, boolean unloaded) {
    }

    // A region that resolved IDLE this cycle but still carried a prior orientation forward -
    // e.g. its diameter/width ratio dipped below the threshold from ordinary flood-fill extent
    // churn, not a real topology change. Recording it here keeps that orientation registered
    // under a real componentId through the idle cycle, so a later re-solve of the same body
    // does not cold-start its direction the moment it crosses back over the threshold.
    private record IdleMemory(List<Long> columnKeys, RiverPotentialSolver.Orientation orientation) {
    }

    private record SolveOutcome(
            ResourceLocation dimension,
            DimensionCache cache,
            Map<Long, RiverPotentialSolver.Cell> cells,
            Map<Long, BlockPos> positionsByColumn,
            List<RegionOutcome> flowingRegions,
            List<IdleMemory> rememberedIdleRegions,
            Set<Long> allDiscoveredColumnKeys,
            Set<Long> touchedCellKeys,
            Set<Long> terminalColumns,
            Set<Long> unresolvedColumns,
            Map<Long, List<OpenEdge>> openEdges,
            BlockPos seed,
            Map<Long, Long> priorEpochs
    ) {
    }
}
