package net.skds.wpo.river;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.vehicle.Boat;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.level.LevelEvent;
import net.neoforged.neoforge.event.tick.EntityTickEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;

public final class RiverEvents {

    @SubscribeEvent
    public void onRegisterCommands(RegisterCommandsEvent event) {
        RiverBenchmarkCommand.register(event.getDispatcher());
    }

    @SubscribeEvent
    public void onLevelTick(LevelTickEvent.Post event) {
        if (!(event.getLevel() instanceof ServerLevel level) || level.isClientSide()) {
            return;
        }
        if (RiverConfig.COMMON.overworldOnly.get() && level.dimension() != Level.OVERWORLD) {
            return;
        }
        RiverTicker.tick(level);
        RiverBenchmarkManager.tick(level);
    }

    @SubscribeEvent
    public void onEntityTick(EntityTickEvent.Post event) {
        if (!RiverConfig.COMMON.enabled.get() || !RiverConfig.COMMON.entityCurrentForces.get()) {
            return;
        }

        Entity entity = event.getEntity();
        if (!(entity instanceof Boat) || !(entity.level() instanceof ServerLevel level) || !entity.isInWater()) {
            return;
        }
        if (RiverConfig.COMMON.overworldOnly.get() && level.dimension() != Level.OVERWORLD) {
            return;
        }

        RiverCurrentField.currentAt(level, entity.blockPosition()).ifPresent(current -> {
            Vec3 push = current.vector(0.018D);
            if (push.lengthSqr() > 0.0D) {
                entity.push(push.x, 0.0D, push.z);
            }
        });
    }

    @SubscribeEvent
    public void onLevelUnload(LevelEvent.Unload event) {
        if (event.getLevel() instanceof ServerLevel level) {
            RiverBenchmarkManager.onLevelUnload(level);
            RiverCurrentField.clear(level);
            TerrariumBridge.clear(level);
            OsmWaterwayProvider.clear(level);
        }
    }
}
