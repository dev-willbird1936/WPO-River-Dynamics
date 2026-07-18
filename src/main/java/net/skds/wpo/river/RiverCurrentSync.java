package net.skds.wpo.river;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.neoforged.neoforge.event.level.ChunkWatchEvent;
import net.neoforged.neoforge.network.PacketDistributor;

public final class RiverCurrentSync {

    private static final Map<ServerLevel, Map<Long, DirtyChunk>> PENDING =
            Collections.synchronizedMap(new WeakHashMap<>());

    private RiverCurrentSync() {
    }

    static void queueUpsert(ServerLevel level, BlockPos pos, RiverCurrentField.Current current) {
        DirtyChunk chunk = dirtyChunk(level, new ChunkPos(pos).toLong());
        chunk.upsert(toWire(pos, current));
    }

    static void queueRemoval(ServerLevel level, BlockPos pos) {
        DirtyChunk chunk = dirtyChunk(level, new ChunkPos(pos).toLong());
        chunk.remove(pos);
    }

    public static void flush(ServerLevel level) {
        Map<Long, DirtyChunk> pending;
        synchronized (PENDING) {
            pending = PENDING.remove(level);
        }
        if (pending == null || pending.isEmpty() || level.players().isEmpty()) {
            return;
        }

        List<RiverCurrentSyncPayload.ChunkUpdate> updates = pending.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(entry -> entry.getValue().drain(new ChunkPos(entry.getKey())))
                .filter(update -> !update.upserts().isEmpty() || !update.removals().isEmpty())
                .toList();
        if (updates.isEmpty()) {
            return;
        }

        ResourceLocation dimension = level.dimension().location();
        boolean reversed = RiverCurrentField.isReversed(level);
        for (ServerPlayer player : level.players()) {
            List<RiverCurrentSyncPayload.ChunkUpdate> visible = updates.stream()
                    .filter(update -> player.getChunkTrackingView().contains(update.chunkX(), update.chunkZ()))
                    .toList();
            for (int from = 0; from < visible.size(); from += RiverCurrentSyncPayload.MAX_CHUNKS) {
                int to = Math.min(visible.size(), from + RiverCurrentSyncPayload.MAX_CHUNKS);
                PacketDistributor.sendToPlayer(player, new RiverCurrentSyncPayload(
                        dimension,
                        false,
                        reversed,
                        visible.subList(from, to)));
            }
        }
    }

    public static void sendChunkSnapshot(ChunkWatchEvent.Sent event) {
        ServerLevel level = event.getLevel();
        ChunkPos chunkPos = event.getPos();
        List<RiverCurrentSyncPayload.Column> columns = RiverCurrentField.snapshotChunk(level, chunkPos).stream()
                .map(snapshot -> toWire(snapshot.pos(), snapshot.current()))
                .toList();
        RiverCurrentSyncPayload.ChunkUpdate update = new RiverCurrentSyncPayload.ChunkUpdate(
                chunkPos.x,
                chunkPos.z,
                true,
                columns,
                List.of());
        PacketDistributor.sendToPlayer(event.getPlayer(), new RiverCurrentSyncPayload(
                level.dimension().location(),
                false,
                RiverCurrentField.isReversed(level),
                List.of(update)));
    }

    public static void broadcastState(ServerLevel level) {
        PacketDistributor.sendToPlayersInDimension(level, new RiverCurrentSyncPayload(
                level.dimension().location(),
                false,
                RiverCurrentField.isReversed(level),
                List.of()));
    }

    public static void broadcastReset(ServerLevel level) {
        synchronized (PENDING) {
            PENDING.remove(level);
        }
        PacketDistributor.sendToPlayersInDimension(level, new RiverCurrentSyncPayload(
                level.dimension().location(),
                true,
                RiverCurrentField.isReversed(level),
                List.of()));
    }

    public static void discard(ServerLevel level) {
        synchronized (PENDING) {
            PENDING.remove(level);
        }
    }

    private static DirtyChunk dirtyChunk(ServerLevel level, long chunkKey) {
        synchronized (PENDING) {
            return PENDING.computeIfAbsent(level, ignored -> new HashMap<>())
                    .computeIfAbsent(chunkKey, ignored -> new DirtyChunk());
        }
    }

    private static RiverCurrentSyncPayload.Column toWire(BlockPos pos, RiverCurrentField.Current current) {
        return new RiverCurrentSyncPayload.Column(
                pos.immutable(),
                current.direction(),
                current.target().immutable(),
                (float) current.vecX(),
                (float) current.vecZ(),
                (float) current.strength(),
                (float) current.speed());
    }

    private static final class DirtyChunk {
        private final Map<Long, RiverCurrentSyncPayload.Column> upserts = new HashMap<>();
        private final Map<Long, BlockPos> removals = new HashMap<>();

        synchronized void upsert(RiverCurrentSyncPayload.Column column) {
            long key = RiverCurrentField.columnKey(column.pos());
            removals.remove(key);
            upserts.put(key, column);
        }

        synchronized void remove(BlockPos pos) {
            long key = RiverCurrentField.columnKey(pos);
            upserts.remove(key);
            removals.put(key, pos.immutable());
        }

        synchronized RiverCurrentSyncPayload.ChunkUpdate drain(ChunkPos chunkPos) {
            List<RiverCurrentSyncPayload.Column> upsertList = new ArrayList<>(upserts.values());
            upsertList.sort(Comparator.comparingLong(column -> column.pos().asLong()));
            List<BlockPos> removalList = new ArrayList<>(removals.values());
            removalList.sort(Comparator.comparingLong(BlockPos::asLong));
            return new RiverCurrentSyncPayload.ChunkUpdate(
                    chunkPos.x,
                    chunkPos.z,
                    false,
                    upsertList,
                    removalList);
        }
    }
}
