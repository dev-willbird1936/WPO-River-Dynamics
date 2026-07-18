package net.skds.wpo.river;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

public final class RiverNetwork {

    private static final String PROTOCOL_VERSION = "1";

    private RiverNetwork() {
    }

    public static void init(IEventBus modBus) {
        modBus.addListener(RiverNetwork::registerPayloads);
    }

    private static void registerPayloads(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar(PROTOCOL_VERSION);
        registrar.playToClient(
                RiverCurrentSyncPayload.TYPE,
                RiverCurrentSyncPayload.STREAM_CODEC,
                RiverCurrentSyncPayload::handle);
    }
}
