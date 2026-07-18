package net.skds.wpo.river;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.material.Fluids;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.skds.wpo.WPOConfig;
import net.skds.wpo.api.FlowBias;

@Mod(RiverDynamics.MOD_ID)
public final class RiverDynamics {

    public static final String MOD_ID = "wpo_river_dynamics";
    public static final String MOD_NAME = "WPO: River Dynamics";
    public static final Logger LOGGER = LogManager.getLogger();

    public RiverDynamics(IEventBus modBus, ModContainer container) {
        RiverConfig.init(container);
        RiverNetwork.init(modBus);
        NeoForge.EVENT_BUS.register(new RiverEvents());
        if (FMLEnvironment.dist == Dist.CLIENT) {
            RiverCurrentField.setClientRefresh(RiverClientRefresh::markDirty);
            NeoForge.EVENT_BUS.addListener(RenderLevelStageEvent.class, RiverFlowOverlayRenderer::onRenderLevelStage);
        }
        FlowBias.register((level, pos, state) -> {
            if (!(level instanceof Level actualLevel) || !state.getType().isSame(Fluids.WATER)) {
                return FlowBias.Bias.NONE;
            }
            // Keep WPO's cardinal steering and continuous flow vector on the same coherent
            // reach-wide direction. Raw per-column seeds can legitimately disagree while a
            // broad flat channel is still being discovered; exposing them here recreated those
            // disagreements in fluid transport even after the visual field was corrected.
            Direction rawDirection = RiverCurrentField.currentAt(actualLevel, pos)
                    .map(RiverCurrentField.Current::direction)
                    .orElse(null);
            if (rawDirection != null && RiverCurrentField.isReversed(actualLevel)) {
                rawDirection = rawDirection.getOpposite();
            }
            RiverCurrentField.Flow smoothed = RiverCurrentField.smoothedFlowAt(actualLevel, pos).orElse(null);
            if (smoothed == null) {
                return rawDirection == null ? FlowBias.Bias.NONE : new FlowBias.Bias(rawDirection, 0.0D);
            }

            double strength = 0.0D;
            if (RiverConfig.COMMON.currentFlowVectors.get()) {
                double depthFactor = Math.max(0.25D, Math.min(1.0D,
                        state.getAmount() / (double) WPOConfig.MAX_FLUID_LEVEL));
                strength = smoothed.strength() * RiverConfig.COMMON.visualCurrentMultiplier.get() * depthFactor;
            }
            return new FlowBias.Bias(smoothed.direction(), strength, smoothed.x(), smoothed.z());
        });
    }
}
