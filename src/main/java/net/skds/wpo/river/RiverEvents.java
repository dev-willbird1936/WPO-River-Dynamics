package net.skds.wpo.river;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.vehicle.Boat;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.level.ChunkEvent;
import net.neoforged.neoforge.event.level.ChunkWatchEvent;
import net.neoforged.neoforge.event.level.LevelEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
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
        RiverTopologyField.tick(level);
        if (RiverConfig.COMMON.overworldOnly.get() && level.dimension() != Level.OVERWORLD) {
            return;
        }
        RiverTicker.tick(level);
        RiverBenchmarkManager.tick(level);
        RiverFlowArrows.tick(level);
        RiverCurrentSync.flush(level);
    }

    @SubscribeEvent
    public void onEntityTick(EntityTickEvent.Post event) {
        if (!RiverConfig.COMMON.enabled.get() || !RiverConfig.COMMON.entityCurrentForces.get()) {
            return;
        }

        Entity entity = event.getEntity();
        if (!(entity.level() instanceof ServerLevel level)
                || !entity.isInWater()
                || entity.isSpectator()
                || (entity instanceof Player player && player.getAbilities().flying)) {
            return;
        }
        if (RiverConfig.COMMON.overworldOnly.get() && level.dimension() != Level.OVERWORLD) {
            return;
        }

        RiverCurrentField.smoothedFlowAt(level, entity.blockPosition()).ifPresent(flow -> {
            double multiplier;
            double response;
            double maximum;
            if (entity instanceof Boat) {
                multiplier = 1.0D;
                response = 0.24D;
                maximum = 0.035D;
            } else if (entity instanceof ItemEntity) {
                multiplier = 0.85D;
                response = 0.20D;
                maximum = 0.028D;
            } else if (entity instanceof Player) {
                multiplier = 0.65D;
                response = 0.16D;
                maximum = 0.022D;
            } else if (entity instanceof LivingEntity) {
                multiplier = 0.55D;
                response = 0.14D;
                maximum = 0.020D;
            } else {
                multiplier = 0.70D;
                response = 0.16D;
                maximum = 0.024D;
            }
            Vec3 velocity = entity.getDeltaMovement();
            double downstreamSpeed = (velocity.x * flow.x()) + (velocity.z * flow.z());
            double correction = RiverCurrentMotion.correction(
                    downstreamSpeed, flow.speed() * multiplier, response, maximum);
            if (correction > 0.0D) {
                entity.push(flow.x() * correction, 0.0D, flow.z() * correction);
                if (entity instanceof ServerPlayer player) {
                    player.hurtMarked = true;
                }
            }
        });
    }

    @SubscribeEvent
    public void onLevelUnload(LevelEvent.Unload event) {
        if (event.getLevel() instanceof ServerLevel level) {
            RiverFlowArrows.reset();
            RiverBenchmarkManager.onLevelUnload(level);
            RiverCurrentField.clear(level);
            TerrariumBridge.clear(level);
            OsmWaterwayProvider.clear(level);
        } else if (event.getLevel() instanceof Level level && level.isClientSide()) {
            RiverCurrentField.clearClient(level);
        }
    }

    @SubscribeEvent
    public void onChunkSent(ChunkWatchEvent.Sent event) {
        RiverCurrentSync.sendChunkSnapshot(event);
    }

    @SubscribeEvent
    public void onChunkUnload(ChunkEvent.Unload event) {
        if (event.getLevel() instanceof Level level && level.isClientSide()) {
            RiverCurrentField.clearClientChunk(level, event.getChunk().getPos());
        }
    }

    @SubscribeEvent
    public void onChunkLoad(ChunkEvent.Load event) {
        if (event.getLevel() instanceof ServerLevel level) {
            RiverTopologyField.onChunkLoad(level, event.getChunk().getPos());
        }
    }

    @SubscribeEvent
    public void onServerStopping(ServerStoppingEvent event) {
        RiverTopologyField.shutdown();
    }
}
