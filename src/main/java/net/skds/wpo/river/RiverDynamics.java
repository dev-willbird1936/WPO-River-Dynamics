package net.skds.wpo.river;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;

@Mod(RiverDynamics.MOD_ID)
public final class RiverDynamics {

    public static final String MOD_ID = "wpo_river_dynamics";
    public static final String MOD_NAME = "WPO: River Dynamics";
    public static final Logger LOGGER = LogManager.getLogger();

    public RiverDynamics(IEventBus modBus, ModContainer container) {
        RiverConfig.init(container);
        NeoForge.EVENT_BUS.register(new RiverEvents());
    }
}
