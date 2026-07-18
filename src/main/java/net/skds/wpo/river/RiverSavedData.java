package net.skds.wpo.river;

import it.unimi.dsi.fastutil.longs.Long2DoubleMap;
import it.unimi.dsi.fastutil.longs.Long2DoubleOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2LongMap;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.saveddata.SavedData;

public final class RiverSavedData extends SavedData {

    private static final String DATA_NAME = "wpo_river_dynamics";
    private static final String RESERVOIRS = "reservoirs";
    private static final String VISITS = "visits";
    private static final SavedData.Factory<RiverSavedData> FACTORY =
            new SavedData.Factory<>(RiverSavedData::new, RiverSavedData::load, null);

    private final Long2DoubleOpenHashMap reservoirLevels = new Long2DoubleOpenHashMap();
    private final Long2LongOpenHashMap chunkLastVisit = new Long2LongOpenHashMap();
    private transient long runtimeCursor;

    public RiverSavedData() {
        reservoirLevels.defaultReturnValue(0.0D);
        chunkLastVisit.defaultReturnValue(Long.MIN_VALUE);
    }

    public static RiverSavedData get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(FACTORY, DATA_NAME);
    }

    public static RiverSavedData load(CompoundTag tag, HolderLookup.Provider registries) {
        RiverSavedData data = new RiverSavedData();
        ListTag reservoirs = tag.getList(RESERVOIRS, Tag.TAG_COMPOUND);
        for (Tag element : reservoirs) {
            if (element instanceof CompoundTag entry) {
                double levels = entry.getDouble("levels");
                if (levels > 0.001D) {
                    data.reservoirLevels.put(entry.getLong("chunk"), levels);
                }
            }
        }
        if (tag.contains(VISITS, Tag.TAG_COMPOUND)) {
            CompoundTag visits = tag.getCompound(VISITS);
            for (String key : visits.getAllKeys()) {
                data.chunkLastVisit.put(Long.parseLong(key), visits.getLong(key));
            }
        }
        return data;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        ListTag reservoirs = new ListTag();
        for (Long2DoubleMap.Entry entry : reservoirLevels.long2DoubleEntrySet()) {
            if (entry.getDoubleValue() <= 0.001D) {
                continue;
            }
            CompoundTag cell = new CompoundTag();
            cell.putLong("chunk", entry.getLongKey());
            cell.putDouble("levels", entry.getDoubleValue());
            reservoirs.add(cell);
        }
        tag.put(RESERVOIRS, reservoirs);

        CompoundTag visits = new CompoundTag();
        for (Long2LongMap.Entry entry : chunkLastVisit.long2LongEntrySet()) {
            visits.putLong(String.valueOf(entry.getLongKey()), entry.getLongValue());
        }
        tag.put(VISITS, visits);
        return tag;
    }

    public long nextCursor(int delta) {
        long cursor = runtimeCursor;
        runtimeCursor += Math.max(1, delta);
        return cursor;
    }

    public void markVisited(ChunkPos pos, long gameTime) {
        chunkLastVisit.put(pos.toLong(), gameTime);
        setDirty();
    }

    public double getReservoirLevels(long chunkKey) {
        return reservoirLevels.get(chunkKey);
    }

    public void addReservoirLevels(long chunkKey, double levels, double cap) {
        if (!(levels > 0.0D) || !(cap > 0.0D)) {
            return;
        }
        double next = Math.min(cap, reservoirLevels.get(chunkKey) + levels);
        reservoirLevels.put(chunkKey, next);
        setDirty();
    }

    public int drainWholeLevels(long chunkKey, int maxLevels) {
        if (maxLevels <= 0) {
            return 0;
        }
        double current = reservoirLevels.get(chunkKey);
        int drained = Math.min(maxLevels, (int) Math.floor(current));
        if (drained <= 0) {
            return 0;
        }
        double remaining = current - drained;
        if (remaining > 0.001D) {
            reservoirLevels.put(chunkKey, remaining);
        } else {
            reservoirLevels.remove(chunkKey);
        }
        setDirty();
        return drained;
    }

    public void sweepStale(long gameTime) {
        long cutoff = gameTime - (14L * 24000L);
        Long2DoubleOpenHashMap stale = null;
        for (Long2DoubleMap.Entry entry : reservoirLevels.long2DoubleEntrySet()) {
            long lastVisit = chunkLastVisit.get(entry.getLongKey());
            if (lastVisit != Long.MIN_VALUE && lastVisit < cutoff && entry.getDoubleValue() < 1.0D) {
                if (stale == null) {
                    stale = new Long2DoubleOpenHashMap();
                }
                stale.put(entry.getLongKey(), entry.getDoubleValue());
            }
        }
        if (stale == null) {
            return;
        }
        for (long key : stale.keySet()) {
            reservoirLevels.remove(key);
            chunkLastVisit.remove(key);
        }
        setDirty();
    }
}
