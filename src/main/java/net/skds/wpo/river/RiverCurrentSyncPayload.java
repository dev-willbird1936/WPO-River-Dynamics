package net.skds.wpo.river;

import java.util.List;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.network.handling.IPayloadContext;

public record RiverCurrentSyncPayload(
        ResourceLocation dimensionId,
        boolean reset,
        boolean reversed,
        List<ChunkUpdate> chunks
) implements CustomPacketPayload {

    static final int MAX_CHUNKS = 32;
    static final int MAX_COLUMNS = 256;

    public static final Type<RiverCurrentSyncPayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(RiverDynamics.MOD_ID, "current_sync"));

    private static final StreamCodec<RegistryFriendlyByteBuf, List<ChunkUpdate>> CHUNKS_CODEC =
            ChunkUpdate.STREAM_CODEC.apply(ByteBufCodecs.list(MAX_CHUNKS));

    public static final StreamCodec<RegistryFriendlyByteBuf, RiverCurrentSyncPayload> STREAM_CODEC =
            StreamCodec.composite(
                    ResourceLocation.STREAM_CODEC,
                    RiverCurrentSyncPayload::dimensionId,
                    ByteBufCodecs.BOOL,
                    RiverCurrentSyncPayload::reset,
                    ByteBufCodecs.BOOL,
                    RiverCurrentSyncPayload::reversed,
                    CHUNKS_CODEC,
                    RiverCurrentSyncPayload::chunks,
                    RiverCurrentSyncPayload::new);

    public RiverCurrentSyncPayload {
        chunks = List.copyOf(chunks);
    }

    @Override
    public Type<RiverCurrentSyncPayload> type() {
        return TYPE;
    }

    static void handle(RiverCurrentSyncPayload payload, IPayloadContext context) {
        Level level = context.player().level();
        if (!level.isClientSide()
                || !level.dimension().location().equals(payload.dimensionId())) {
            return;
        }
        RiverCurrentField.applyClientSync(level, payload);
    }

    public record ChunkUpdate(
            int chunkX,
            int chunkZ,
            boolean replace,
            List<Column> upserts,
            List<BlockPos> removals
    ) {
        private static final StreamCodec<RegistryFriendlyByteBuf, List<Column>> UPSERTS_CODEC =
                Column.STREAM_CODEC.apply(ByteBufCodecs.list(MAX_COLUMNS));
        private static final StreamCodec<RegistryFriendlyByteBuf, List<BlockPos>> REMOVALS_CODEC =
                BlockPos.STREAM_CODEC.<RegistryFriendlyByteBuf>cast().apply(ByteBufCodecs.list(MAX_COLUMNS));

        static final StreamCodec<RegistryFriendlyByteBuf, ChunkUpdate> STREAM_CODEC =
                StreamCodec.composite(
                        ByteBufCodecs.VAR_INT,
                        ChunkUpdate::chunkX,
                        ByteBufCodecs.VAR_INT,
                        ChunkUpdate::chunkZ,
                        ByteBufCodecs.BOOL,
                        ChunkUpdate::replace,
                        UPSERTS_CODEC,
                        ChunkUpdate::upserts,
                        REMOVALS_CODEC,
                        ChunkUpdate::removals,
                        ChunkUpdate::new);

        public ChunkUpdate {
            upserts = List.copyOf(upserts);
            removals = List.copyOf(removals);
        }
    }

    public record Column(
            BlockPos pos,
            Direction direction,
            BlockPos target,
            float vecX,
            float vecZ,
            float strength,
            float speed
    ) {
        static final StreamCodec<RegistryFriendlyByteBuf, Column> STREAM_CODEC = StreamCodec.of(
                Column::encode,
                Column::decode);

        private static void encode(RegistryFriendlyByteBuf buffer, Column column) {
            BlockPos.STREAM_CODEC.encode(buffer, column.pos());
            Direction.STREAM_CODEC.encode(buffer, column.direction());
            BlockPos.STREAM_CODEC.encode(buffer, column.target());
            buffer.writeFloat(column.vecX());
            buffer.writeFloat(column.vecZ());
            buffer.writeFloat(column.strength());
            buffer.writeFloat(column.speed());
        }

        private static Column decode(RegistryFriendlyByteBuf buffer) {
            return new Column(
                    BlockPos.STREAM_CODEC.decode(buffer),
                    Direction.STREAM_CODEC.decode(buffer),
                    BlockPos.STREAM_CODEC.decode(buffer),
                    buffer.readFloat(),
                    buffer.readFloat(),
                    buffer.readFloat(),
                    buffer.readFloat());
        }
    }
}
