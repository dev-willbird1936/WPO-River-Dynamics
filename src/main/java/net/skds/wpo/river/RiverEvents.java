package net.skds.wpo.river;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.level.LevelEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;

public final class RiverEvents {

    @SubscribeEvent
    public void onLevelTick(LevelTickEvent.Post event) {
        if (!(event.getLevel() instanceof ServerLevel level) || level.isClientSide()) {
            return;
        }
        if (RiverConfig.COMMON.overworldOnly.get() && level.dimension() != Level.OVERWORLD) {
            return;
        }
        RiverTicker.tick(level);
    }

    @SubscribeEvent
    public void onLevelUnload(LevelEvent.Unload event) {
        if (event.getLevel() instanceof ServerLevel level) {
            TerrariumBridge.clear(level);
            OsmWaterwayProvider.clear(level);
        }
    }
}
