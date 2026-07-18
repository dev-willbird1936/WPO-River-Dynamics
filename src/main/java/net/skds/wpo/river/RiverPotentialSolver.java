package net.skds.wpo.river;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CancellationException;

/** Builds one bank-aware downstream potential for a complete surface-water component. */
final class RiverPotentialSolver {

    private static final int[][] CARDINAL = { { 0, -1 }, { 1, 0 }, { 0, 1 }, { -1, 0 } };
    private static final int[][] AROUND = {
            { 0, -1 }, { 1, -1 }, { 1, 0 }, { 1, 1 },
            { 0, 1 }, { -1, 1 }, { -1, 0 }, { -1, -1 }
    };
    private static final int RELAXATION_PASSES = 160;
    private static final double MIN_FLOW_DROP = 1.0E-5D;

    private RiverPotentialSolver() {
    }

    static Result solve(Collection<Cell> input) {
        return solve(input, 1.35D, null);
    }

    static Result solve(Collection<Cell> input, double minimumLengthToWidth) {
        return solve(input, minimumLengthToWidth, null);
    }

    static Result solve(Collection<Cell> input, double minimumLengthToWidth, Orientation preferredOrientation) {
        return solve(input, minimumLengthToWidth, preferredOrientation, Set.of());
    }

    static Result solve(
            Collection<Cell> input,
            double minimumLengthToWidth,
            Orientation preferredOrientation,
            Set<Long> openColumns
    ) {
        Map<Long, Cell> cells = new HashMap<>();
        for (Cell cell : input) {
            cells.put(key(cell.x(), cell.z()), cell);
        }
        if (cells.size() < 8) {
            return Result.idle();
        }

        Result bridged = solveSkeleton(cells, minimumLengthToWidth, preferredOrientation, openColumns, true);
        if (bridged.state() != State.UNAVAILABLE) {
            return bridged;
        }
        // A diagonal-only link between two arms (e.g. a near-touching hairpin) can fragment
        // the bridged skeleton graph even though thinning still treats it as one piece. Fall
        // back to the permissive 8-connected graph rather than losing the whole reach.
        return solveSkeleton(cells, minimumLengthToWidth, preferredOrientation, openColumns, false);
    }

    private static Result solveSkeleton(
            Map<Long, Cell> cells,
            double minimumLengthToWidth,
            Orientation preferredOrientation,
            Set<Long> openColumns,
            boolean requireBridge
    ) {
        Set<Long> waterCells = cells.keySet();
        Set<Long> skeleton = thin(waterCells);
        Map<Long, Long> initialOwner = nearestSkeleton(waterCells, skeleton);
        Set<Long> protectedSkeleton = protectedSkeleton(openColumns, initialOwner);
        // A spur shorter than the channel's own width is bank irregularity, not a branch -
        // thinning a wide or irregularly-shaped body grows a spur into every bank lobe deeper
        // than a couple of cells, and a fixed short threshold lets those survive in wide water,
        // where they then read as a real (if short) tributary and pull arrows toward the bank.
        int minimumSpurLength = Math.max(4, (int) Math.ceil(waterCells.size() / (double) Math.max(1, skeleton.size())));
        pruneShortSpurs(skeleton, waterCells, requireBridge, minimumSpurLength, protectedSkeleton);
        List<Long> endpoints = endpoints(skeleton, waterCells, requireBridge);
        if (endpoints.size() < 2) {
            return Result.idle();
        }

        Map<Long, Long> owner = nearestSkeleton(waterCells, skeleton);
        protectedSkeleton = protectedSkeleton(openColumns, owner);
        Map<Long, Integer> widthByOwner = new HashMap<>();
        for (long skeletonOwner : owner.values()) {
            widthByOwner.merge(skeletonOwner, 1, Integer::sum);
        }
        Orientation orientation = chooseOrientation(
                skeleton, waterCells, requireBridge, endpoints, cells, owner, preferredOrientation);
        long outlet = orientation.downstream();
        long upstream = orientation.upstream();
        Set<Long> openOutlets = openColumns.stream()
                .filter(column -> owner.getOrDefault(column, Long.MIN_VALUE) == outlet)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        Map<Long, Long> parent = new HashMap<>();
        Map<Long, Integer> skeletonDistance = distances(skeleton, waterCells, requireBridge, outlet, parent);
        if (skeletonDistance.size() != skeleton.size()) {
            return Result.unavailable();
        }

        int diameter = skeletonDistance.values().stream().mapToInt(Integer::intValue).max().orElse(0);
        double effectiveWidth = cells.size() / (double) Math.max(1, skeleton.size());
        // A body already carrying a prior orientation needs to shrink further below the
        // ratio than a fresh body needs to grow to qualify in the first place - otherwise
        // ordinary flood-fill extent churn flips the same physical reach between IDLE and
        // FLOWING every few cycles, and each crossing risks a fresh (possibly opposite) cold
        // start even with idle-cycle orientation memory in place.
        double idleThresholdRatio = preferredOrientation != null
                ? Math.max(1.0D, minimumLengthToWidth) * 0.6D
                : Math.max(1.0D, minimumLengthToWidth);
        if (diameter < Math.max(8.0D, effectiveWidth * idleThresholdRatio)) {
            return Result.idle();
        }

        Map<Long, Integer> initialDistance = new HashMap<>(skeletonDistance);
        for (long endpoint : endpoints) {
            if (endpoint == outlet || endpoint == upstream) {
                continue;
            }
            if (branchTouchesProtected(endpoint, skeleton, waterCells, requireBridge, protectedSkeleton)) {
                continue;
            }
            if (slopesTowardJunction(endpoint, skeleton, waterCells, requireBridge, cells)) {
                continue;
            } else {
                flattenDanglingBranch(endpoint, skeleton, waterCells, requireBridge, initialDistance);
            }
        }
        double[] potential = new double[cells.size()];
        boolean[] fixed = new boolean[cells.size()];
        List<Long> order = new ArrayList<>(cells.keySet());
        order.sort(Long::compare);
        Map<Long, Integer> index = new HashMap<>();
        for (int i = 0; i < order.size(); i++) {
            index.put(order.get(i), i);
            int distance = initialDistance.getOrDefault(owner.get(order.get(i)), 0);
            potential[i] = diameter == 0 ? 0.0D : distance / (double) diameter;
        }

        // Pin the complete medial graph, not only terminal disks. Relaxation then only
        // projects one curvilinear coordinate across channel width; convergence depends
        // on width rather than total river length.
        for (long skeletonCell : skeleton) {
            int i = index.get(skeletonCell);
            fixed[i] = true;
            potential[i] = initialDistance.getOrDefault(skeletonCell, 0) / (double) diameter;
        }

        relax(cells.keySet(), order, index, potential, fixed);

        Map<Long, List<Long>> children = new HashMap<>();
        for (Map.Entry<Long, Long> entry : parent.entrySet()) {
            children.computeIfAbsent(entry.getValue(), ignored -> new ArrayList<>()).add(entry.getKey());
        }

        Map<Long, Flow> flows = new HashMap<>();
        for (long cellKey : order) {
            int i = index.get(cellKey);
            int x = x(cellKey);
            int z = z(cellKey);

            long skeletonOwner = owner.get(cellKey);
            double[] tangent = skeletonTangent(skeletonOwner, parent, children, cells);

            Pick forward = pickNeighbor(i, x, z, index, potential, tangent, true);
            if (forward.next() == Long.MIN_VALUE) {
                continue;
            }
            // Precompute the mirrored (steepest-rise) pick in the same pass, using the same
            // tie-break rule reflected about the tangent - so toggling reversed later is an
            // exact O(1) field read, never a query-time recomputation with a different rule.
            Pick reverse = pickNeighbor(i, x, z, index, potential, tangent, false);
            Cell cell = cells.get(cellKey);
            double width = widthByOwner.getOrDefault(skeletonOwner, 1);
            double depth = cell == null ? 1.0D : Math.max(1.0D, cell.surfaceY() - cell.bedY() + 1.0D);
            double slope = downstreamBedSlope(skeletonOwner, parent, cells);
            double speed = RiverHydraulics.speed(width, depth, slope);
            flows.put(cellKey, new Flow(
                    forward.vecX(), forward.vecZ(), forward.next(), reverse.next(), potential[i], speed));
        }
        if (flows.isEmpty()) {
            return Result.idle();
        }
        Map<Long, Double> potentials = new HashMap<>();
        for (long cellKey : order) {
            potentials.put(cellKey, potential[index.get(cellKey)]);
        }
        return Result.flowing(flows, potentials, orientation, openOutlets);
    }

    private static Pick pickNeighbor(
            int i,
            int x,
            int z,
            Map<Long, Integer> index,
            double[] potential,
            double[] tangent,
            boolean forward
    ) {
        double vx = 0.0D;
        double vz = 0.0D;
        long next = Long.MIN_VALUE;
        double bestDrop = MIN_FLOW_DROP;
        double bestAlignment = Double.NEGATIVE_INFINITY;
        double tangentX = forward ? tangent[0] : -tangent[0];
        double tangentZ = forward ? tangent[1] : -tangent[1];

        for (int[] step : CARDINAL) {
            long neighbor = key(x + step[0], z + step[1]);
            Integer ni = index.get(neighbor);
            if (ni == null) {
                continue;
            }
            double drop = forward ? potential[i] - potential[ni] : potential[ni] - potential[i];
            if (drop <= MIN_FLOW_DROP) {
                continue;
            }
            vx += step[0] * drop;
            vz += step[1] * drop;
            double alignment = (step[0] * tangentX) + (step[1] * tangentZ);
            if (drop > bestDrop + 1.0E-7D
                    || (Math.abs(drop - bestDrop) <= 1.0E-7D && alignment > bestAlignment)) {
                bestDrop = drop;
                bestAlignment = alignment;
                next = neighbor;
            }
        }

        double length = Math.sqrt((vx * vx) + (vz * vz));
        if (next == Long.MIN_VALUE || length < 1.0E-7D) {
            return Pick.NONE;
        }
        // Harmonic flux supplies the strict target (downstream, or upstream when mirrored).
        // The medial-axis tangent supplies the visible direction, preventing bank-to-center
        // arrows in wide water.
        double tangentLength = Math.sqrt((tangentX * tangentX) + (tangentZ * tangentZ));
        if (tangentLength > 1.0E-7D) {
            vx = tangentX / tangentLength;
            vz = tangentZ / tangentLength;
            double targetX = x(next) - x;
            double targetZ = z(next) - z;
            if ((vx * targetX) + (vz * targetZ) < 0.0D) {
                vx = -vx;
                vz = -vz;
            }
        } else {
            vx /= length;
            vz /= length;
        }
        return new Pick(next, vx, vz);
    }

    private static Set<Long> thin(Set<Long> cells) {
        Set<Long> skeleton = new HashSet<>(cells);
        boolean changed;
        int pass = 0;
        do {
            checkInterrupted();
            changed = thinPass(skeleton, false);
            changed |= thinPass(skeleton, true);
        } while (changed && ++pass < 256);
        return skeleton;
    }

    private static boolean thinPass(Set<Long> pixels, boolean second) {
        List<Long> remove = new ArrayList<>();
        for (long pixel : pixels) {
            int px = x(pixel);
            int pz = z(pixel);
            boolean[] n = new boolean[8];
            int count = 0;
            for (int i = 0; i < AROUND.length; i++) {
                n[i] = pixels.contains(key(px + AROUND[i][0], pz + AROUND[i][1]));
                if (n[i]) {
                    count++;
                }
            }
            if (count < 2 || count > 6) {
                continue;
            }
            int transitions = 0;
            for (int i = 0; i < 8; i++) {
                if (!n[i] && n[(i + 1) & 7]) {
                    transitions++;
                }
            }
            if (transitions != 1) {
                continue;
            }
            boolean north = n[0];
            boolean east = n[2];
            boolean south = n[4];
            boolean west = n[6];
            boolean blocked = second
                    ? (north && east && west) || (north && south && west)
                    : (north && east && south) || (east && south && west);
            if (!blocked) {
                remove.add(pixel);
            }
        }
        pixels.removeAll(remove);
        return !remove.isEmpty();
    }

    private static void pruneShortSpurs(
            Set<Long> skeleton,
            Set<Long> waterCells,
            boolean requireBridge,
            int minimumLength,
            Set<Long> protectedSkeleton
    ) {
        boolean changed;
        do {
            checkInterrupted();
            changed = false;
            for (long endpoint : endpoints(skeleton, waterCells, requireBridge)) {
                List<Long> branch = new ArrayList<>();
                long previous = Long.MIN_VALUE;
                long current = endpoint;
                while (branch.size() < minimumLength) {
                    branch.add(current);
                    List<Long> neighbors = skeletonNeighbors(skeleton, waterCells, current, requireBridge);
                    neighbors.remove(previous);
                    if (neighbors.size() != 1) {
                        break;
                    }
                    previous = current;
                    current = neighbors.getFirst();
                }
                boolean protectedBranch = branch.stream().anyMatch(protectedSkeleton::contains);
                if (!protectedBranch && branch.size() < minimumLength
                        && skeletonNeighbors(skeleton, waterCells, current, requireBridge).size() > 2) {
                    skeleton.removeAll(branch);
                    changed = true;
                    break;
                }
            }
        } while (changed);
    }

    private static Set<Long> protectedSkeleton(Set<Long> openColumns, Map<Long, Long> owner) {
        Set<Long> protectedCells = new HashSet<>();
        for (long column : openColumns) {
            Long skeletonCell = owner.get(column);
            if (skeletonCell != null) {
                protectedCells.add(skeletonCell);
            }
        }
        return protectedCells;
    }

    private static boolean branchTouchesProtected(
            long endpoint,
            Set<Long> skeleton,
            Set<Long> waterCells,
            boolean requireBridge,
            Set<Long> protectedSkeleton
    ) {
        long previous = Long.MIN_VALUE;
        long current = endpoint;
        while (true) {
            if (protectedSkeleton.contains(current)) {
                return true;
            }
            List<Long> neighbors = skeletonNeighbors(skeleton, waterCells, current, requireBridge);
            neighbors.remove(previous);
            if (neighbors.size() != 1) {
                return false;
            }
            previous = current;
            current = neighbors.getFirst();
        }
    }

    private static List<Long> endpoints(Set<Long> skeleton, Set<Long> waterCells, boolean requireBridge) {
        List<Long> result = new ArrayList<>();
        for (long cell : skeleton) {
            if (skeletonNeighbors(skeleton, waterCells, cell, requireBridge).size() <= 1) {
                result.add(cell);
            }
        }
        return result;
    }

    private static Orientation chooseOrientation(
            Set<Long> skeleton,
            Set<Long> waterCells,
            boolean requireBridge,
            List<Long> endpoints,
            Map<Long, Cell> cells,
            Map<Long, Long> owner,
            Orientation preferred
    ) {
        // Newly loaded terrain is stronger evidence than hysteresis. A partial flat reach can
        // cold-start in either polarity; once a uniquely lower outlet appears, it must be able
        // to correct that earlier guess instead of preserving it forever.
        List<Long> outletCandidates = new ArrayList<>();
        for (long endpoint : endpoints) {
            if (!slopesTowardJunction(endpoint, skeleton, waterCells, requireBridge, cells)) {
                outletCandidates.add(endpoint);
            }
        }
        if (outletCandidates.size() == 1) {
            long outlet = outletCandidates.getFirst();
            long upstream = farthestEndpoint(
                    endpoints, distances(skeleton, waterCells, requireBridge, outlet, new HashMap<>()));
            return new Orientation(upstream, outlet);
        }

        if (preferred != null) {
            Long oldUpstream = nearestOwner(preferred.upstream(), waterCells, owner);
            Long oldDownstream = nearestOwner(preferred.downstream(), waterCells, owner);
            if (oldUpstream != null && oldDownstream != null && !oldUpstream.equals(oldDownstream)) {
                Map<Long, Integer> fromUpstream = distances(skeleton, waterCells, requireBridge, oldUpstream, new HashMap<>());
                Map<Long, Integer> fromDownstream = distances(skeleton, waterCells, requireBridge, oldDownstream, new HashMap<>());
                long upstream = endpoints.getFirst();
                long downstream = endpoints.getFirst();
                int minimum = Integer.MAX_VALUE;
                int maximum = Integer.MIN_VALUE;
                for (long endpoint : endpoints) {
                    int sigma = fromUpstream.getOrDefault(endpoint, 0)
                            - fromDownstream.getOrDefault(endpoint, 0);
                    if (sigma < minimum || (sigma == minimum && endpoint < upstream)) {
                        minimum = sigma;
                        upstream = endpoint;
                    }
                    if (sigma > maximum || (sigma == maximum && endpoint > downstream)) {
                        maximum = sigma;
                        downstream = endpoint;
                    }
                }
                if (upstream != downstream) {
                    return new Orientation(upstream, downstream);
                }
            }
        }

        long seed = endpoints.stream().min(Long::compare).orElseThrow();
        long first = farthestEndpoint(endpoints, distances(skeleton, waterCells, requireBridge, seed, new HashMap<>()));
        long second = farthestEndpoint(endpoints, distances(skeleton, waterCells, requireBridge, first, new HashMap<>()));
        Comparator<Long> downhill = endpointOrder(cells);
        return downhill.compare(first, second) <= 0
                ? new Orientation(second, first)
                : new Orientation(first, second);
    }

    /**
     * A prior anchor's exact cell can fall outside this solve's discovered extent even when
     * the same physical water body is still connected - discovery bounds shift with whichever
     * column triggered them. Snap to the nearest still-present water cell instead of requiring
     * an exact match, then defer to its owner; the sigma-projection logic that consumes this
     * still resolves upstream/downstream from there. The cap keeps an unrelated body from
     * adopting a stale anchor.
     */
    static Long nearestOwner(long target, Set<Long> waterCells, Map<Long, Long> owner) {
        Long direct = owner.get(target);
        if (direct != null) {
            return direct;
        }
        int targetX = x(target);
        int targetZ = z(target);
        long bestDistance = 64L * 64L;
        Long best = null;
        for (long cell : waterCells) {
            Long cellOwner = owner.get(cell);
            if (cellOwner == null) {
                continue;
            }
            long dx = x(cell) - targetX;
            long dz = z(cell) - targetZ;
            long distance = (dx * dx) + (dz * dz);
            if (distance < bestDistance) {
                bestDistance = distance;
                best = cellOwner;
            }
        }
        return best;
    }

    /**
     * Picks whichever prior component covers the most of a newly-solved region's columns and
     * returns its orientation, so a cache rebuild (expiry, expansion, or merge) can preserve
     * that anchor instead of the farthest-pair search re-deriving one from scratch each time.
     * Returns null when the region has no meaningful overlap with any previously-known anchor.
     */
    static Orientation previousOrientationFor(
            Set<Long> region,
            Map<Long, Long> priorComponentByColumn,
            Map<Long, Orientation> priorOrientations
    ) {
        Map<Long, Integer> votes = new HashMap<>();
        for (long columnKey : region) {
            Long priorComponent = priorComponentByColumn.get(columnKey);
            if (priorComponent != null && priorOrientations.containsKey(priorComponent)) {
                votes.merge(priorComponent, 1, Integer::sum);
            }
        }
        Long bestComponent = null;
        int bestVotes = 0;
        for (Map.Entry<Long, Integer> entry : votes.entrySet()) {
            if (bestComponent == null || entry.getValue() > bestVotes
                    || (entry.getValue().intValue() == bestVotes && entry.getKey() < bestComponent)) {
                bestVotes = entry.getValue();
                bestComponent = entry.getKey();
            }
        }
        return bestComponent == null ? null : priorOrientations.get(bestComponent);
    }

    private static long farthestEndpoint(List<Long> endpoints, Map<Long, Integer> distance) {
        return endpoints.stream()
                .max(Comparator.comparingInt((Long value) -> distance.getOrDefault(value, -1))
                        .thenComparingLong(Long::longValue))
                .orElseThrow();
    }

    private static Comparator<Long> endpointOrder(Map<Long, Cell> cells) {
        return Comparator.comparingInt((Long value) -> cells.get(value).surfaceY())
                .thenComparingInt(value -> cells.get(value).bedY())
                .thenComparingLong(Long::longValue);
    }

    private static Map<Long, Integer> distances(
            Set<Long> skeleton, Set<Long> waterCells, boolean requireBridge, long origin, Map<Long, Long> parent
    ) {
        Map<Long, Integer> distance = new HashMap<>();
        ArrayDeque<Long> queue = new ArrayDeque<>();
        distance.put(origin, 0);
        queue.add(origin);
        while (!queue.isEmpty()) {
            checkInterrupted();
            long cell = queue.removeFirst();
            int nextDistance = distance.get(cell) + 1;
            for (long neighbor : skeletonNeighbors(skeleton, waterCells, cell, requireBridge)) {
                if (distance.putIfAbsent(neighbor, nextDistance) == null) {
                    parent.put(neighbor, cell);
                    queue.addLast(neighbor);
                }
            }
        }
        return distance;
    }

    private static Map<Long, Long> nearestSkeleton(Set<Long> cells, Set<Long> skeleton) {
        Map<Long, Long> owner = new HashMap<>();
        ArrayDeque<Long> queue = new ArrayDeque<>();
        List<Long> seeds = new ArrayList<>(skeleton);
        seeds.sort(Long::compare);
        for (long seed : seeds) {
            owner.put(seed, seed);
            queue.add(seed);
        }
        while (!queue.isEmpty()) {
            checkInterrupted();
            long cell = queue.removeFirst();
            int cx = x(cell);
            int cz = z(cell);
            // Water cells only touching the rest of the body diagonally are a supported
            // shape (see the diagonal-only-link handling in skeletonNeighbors/thin) - a
            // cardinal-only spread can leave such a cell without an owner, and every cell
            // in the input set is later looked up unconditionally.
            for (int[] step : AROUND) {
                long neighbor = key(cx + step[0], cz + step[1]);
                if (cells.contains(neighbor) && owner.putIfAbsent(neighbor, owner.get(cell)) == null) {
                    queue.addLast(neighbor);
                }
            }
        }
        return owner;
    }

    private static boolean slopesTowardJunction(
            long endpoint, Set<Long> skeleton, Set<Long> waterCells, boolean requireBridge, Map<Long, Cell> cells
    ) {
        long previous = Long.MIN_VALUE;
        long current = endpoint;
        for (int step = 0; step < 96; step++) {
            List<Long> neighbors = skeletonNeighbors(skeleton, waterCells, current, requireBridge);
            neighbors.remove(previous);
            if (neighbors.size() != 1) {
                break;
            }
            previous = current;
            current = neighbors.getFirst();
        }
        Cell start = cells.get(endpoint);
        Cell junction = cells.get(current);
        if (start == null || junction == null) {
            return false;
        }
        if (start.surfaceY() != junction.surfaceY()) {
            return start.surfaceY() > junction.surfaceY();
        }
        return start.bedY() > junction.bedY();
    }

    static double downstreamBedSlope(long owner, Map<Long, Long> parent, Map<Long, Cell> cells) {
        Cell start = cells.get(owner);
        if (start == null) {
            return 0.0D;
        }
        long current = owner;
        double run = 0.0D;
        for (int step = 0; step < 16; step++) {
            Long next = parent.get(current);
            if (next == null) {
                break;
            }
            run += Math.hypot(x(next) - x(current), z(next) - z(current));
            current = next;
        }
        Cell downstream = cells.get(current);
        if (downstream == null || run < 1.0E-6D) {
            return 0.0D;
        }
        return Math.max(0.0D, (start.bedY() - downstream.bedY()) / run);
    }

    private static void flattenDanglingBranch(
            long endpoint,
            Set<Long> skeleton,
            Set<Long> waterCells,
            boolean requireBridge,
            Map<Long, Integer> initialDistance
    ) {
        List<Long> branch = new ArrayList<>();
        long previous = Long.MIN_VALUE;
        long current = endpoint;
        while (true) {
            List<Long> neighbors = skeletonNeighbors(skeleton, waterCells, current, requireBridge);
            neighbors.remove(previous);
            if (neighbors.size() != 1) {
                break;
            }
            branch.add(current);
            previous = current;
            current = neighbors.getFirst();
        }
        int junctionDistance = initialDistance.getOrDefault(current, 0);
        for (long branchCell : branch) {
            initialDistance.put(branchCell, junctionDistance);
        }
    }

    private static void relax(
            Set<Long> cells,
            List<Long> order,
            Map<Long, Integer> index,
            double[] potential,
            boolean[] fixed
    ) {
        for (int pass = 0; pass < RELAXATION_PASSES; pass++) {
            checkInterrupted();
            for (int parity = 0; parity < 2; parity++) {
                for (long cell : order) {
                    int cx = x(cell);
                    int cz = z(cell);
                    if (((cx + cz) & 1) != parity) {
                        continue;
                    }
                    int i = index.get(cell);
                    if (fixed[i]) {
                        continue;
                    }
                    double sum = 0.0D;
                    int count = 0;
                    for (int[] step : CARDINAL) {
                        Integer ni = index.get(key(cx + step[0], cz + step[1]));
                        if (ni != null) {
                            sum += potential[ni];
                            count++;
                        }
                    }
                    if (count > 0) {
                        potential[i] = sum / count;
                    }
                }
            }
        }
    }

    private static void checkInterrupted() {
        if (Thread.currentThread().isInterrupted()) {
            throw new CancellationException("River topology solve interrupted");
        }
    }

    private static double[] skeletonTangent(
            long owner, Map<Long, Long> parent, Map<Long, List<Long>> children, Map<Long, Cell> cells
    ) {
        Cell start = cells.get(owner);
        long current = owner;
        for (int i = 0; i < 5; i++) {
            Long next = parent.get(current);
            if (next == null) {
                break;
            }
            current = next;
        }
        if (current != owner) {
            Cell end = cells.get(current);
            return start != null && end != null
                    ? new double[] { end.x() - start.x(), end.z() - start.z() }
                    : new double[] { 0.0D, 0.0D };
        }
        // owner has no parent - it is the outlet skeleton node itself, so there is no
        // further-downstream point to aim at. Walk one child branch upstream instead and
        // keep the same outlet-ward sign, rather than falling back to the raw gradient
        // (which reintroduces bank-to-center arrows right at the river mouth).
        long upstreamPoint = owner;
        for (int i = 0; i < 5; i++) {
            List<Long> kids = children.get(upstreamPoint);
            if (kids == null || kids.isEmpty()) {
                break;
            }
            upstreamPoint = kids.stream().min(Long::compare).orElseThrow();
        }
        if (upstreamPoint == owner) {
            return new double[] { 0.0D, 0.0D };
        }
        Cell upstreamCell = cells.get(upstreamPoint);
        return start != null && upstreamCell != null
                ? new double[] { start.x() - upstreamCell.x(), start.z() - upstreamCell.z() }
                : new double[] { 0.0D, 0.0D };
    }

    static List<Long> skeletonNeighbors(Set<Long> skeleton, Set<Long> waterCells, long cell, boolean requireBridge) {
        List<Long> result = new ArrayList<>(8);
        int cx = x(cell);
        int cz = z(cell);
        for (int[] step : AROUND) {
            long neighbor = key(cx + step[0], cz + step[1]);
            if (!skeleton.contains(neighbor)) {
                continue;
            }
            if (requireBridge && step[0] != 0 && step[1] != 0) {
                // A diagonal skeleton edge is only real if an orthogonal water cell actually
                // bridges the corner - otherwise a tight meander could shortcut across land.
                boolean bridged = waterCells.contains(key(cx + step[0], cz))
                        || waterCells.contains(key(cx, cz + step[1]));
                if (!bridged) {
                    continue;
                }
            }
            result.add(neighbor);
        }
        return result;
    }

    static long key(int x, int z) {
        return ((long) x << 32) ^ (z & 0xffffffffL);
    }

    static int x(long key) {
        return (int) (key >> 32);
    }

    static int z(long key) {
        return (int) key;
    }

    record Cell(int x, int z, int surfaceY, int bedY) {
    }

    record Orientation(long upstream, long downstream) {
    }

    record Flow(double x, double z, long next, long reverseNext, double potential, double speed) {
    }

    private record Pick(long next, double vecX, double vecZ) {
        private static final Pick NONE = new Pick(Long.MIN_VALUE, 0.0D, 0.0D);
    }

    record Result(
            State state,
            Map<Long, Flow> flows,
            Map<Long, Double> potentials,
            Orientation orientation,
            Set<Long> openOutlets
    ) {
        static Result flowing(
                Map<Long, Flow> flows,
                Map<Long, Double> potentials,
                Orientation orientation,
                Set<Long> openOutlets
        ) {
            return new Result(State.FLOWING, Map.copyOf(flows), Map.copyOf(potentials), orientation, Set.copyOf(openOutlets));
        }

        static Result idle() {
            return new Result(State.IDLE, Map.of(), Map.of(), null, Set.of());
        }

        static Result unavailable() {
            return new Result(State.UNAVAILABLE, Map.of(), Map.of(), null, Set.of());
        }

        Optional<Flow> flowAt(int x, int z) {
            return Optional.ofNullable(flows.get(key(x, z)));
        }
    }

    enum State {
        FLOWING,
        IDLE,
        UNAVAILABLE
    }
}
