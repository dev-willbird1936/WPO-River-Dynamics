package net.skds.wpo.river;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Iterator;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;

final class TerrariumBridge {

    private static final Map<ChunkSampleKey, CompletableFuture<Optional<ChunkSample>>> CHUNK_SAMPLES = new ConcurrentHashMap<>();
    private static volatile Boolean terrariumClassesPresent;

    private TerrariumBridge() {
    }

    static Optional<ProjectionContext> projection(ServerLevel level) {
        if (!RiverConfig.COMMON.terrariumIntegration.get() || !classesPresent()) {
            return Optional.empty();
        }
        try {
            Object generator = level.getChunkSource().getGenerator();
            Method configurationMethod = generator.getClass().getMethod("configuration");
            Object configuration = configurationMethod.invoke(generator);
            Object projection = configuration.getClass().getMethod("projection").invoke(configuration);
            Method lat = projection.getClass().getMethod("lat", double.class, double.class);
            Method lon = projection.getClass().getMethod("lon", double.class, double.class);
            Method blockX = projection.getClass().getMethod("blockX", double.class, double.class);
            Method blockZ = projection.getClass().getMethod("blockZ", double.class, double.class);
            Method metersPerBlock = projection.getClass().getMethod("idealMetersPerBlock");
            double scale = ((Number) metersPerBlock.invoke(projection)).doubleValue();
            return Optional.of(new ProjectionContext(projection, lat, lon, blockX, blockZ, scale));
        } catch (ReflectiveOperationException | ClassCastException e) {
            return Optional.empty();
        }
    }

    static double horizontalMetersPerBlock(ServerLevel level) {
        return projection(level).map(ProjectionContext::metersPerBlock)
                .orElseGet(() -> RiverConfig.COMMON.fallbackHorizontalMetersPerBlock.get());
    }

    static Optional<TerrariumSample> sample(ServerLevel level, BlockPos pos) {
        if (!RiverConfig.COMMON.terrariumIntegration.get() || !classesPresent()) {
            return Optional.empty();
        }
        try {
            Class<?> holderClass = Class.forName("dev.gegy.terrarium.world.GeoProviderHolder");
            Method getProvider = holderClass.getMethod("get", ServerLevel.class);
            Object provider = getProvider.invoke(null, level);
            if (provider == null) {
                return Optional.empty();
            }

            int chunkX = pos.getX() >> 4;
            int chunkZ = pos.getZ() >> 4;
            ChunkSampleKey key = new ChunkSampleKey(level.dimension().location().toString(), ChunkPos.asLong(chunkX, chunkZ));
            CompletableFuture<Optional<ChunkSample>> future = CHUNK_SAMPLES.computeIfAbsent(key, ignored -> loadChunkSample(provider, chunkX, chunkZ));
            if (!future.isDone()) {
                return Optional.empty();
            }
            return future.getNow(Optional.empty()).map(sample -> sample.at(pos.getX() & 15, pos.getZ() & 15));
        } catch (ReflectiveOperationException | ClassCastException e) {
            return Optional.empty();
        }
    }

    static void clear(ServerLevel level) {
        String dimension = level.dimension().location().toString();
        for (Iterator<ChunkSampleKey> it = CHUNK_SAMPLES.keySet().iterator(); it.hasNext();) {
            if (it.next().dimension().equals(dimension)) {
                it.remove();
            }
        }
    }

    private static CompletableFuture<Optional<ChunkSample>> loadChunkSample(Object provider, int chunkX, int chunkZ) {
        try {
            Method getOrLoad = provider.getClass().getMethod("getOrLoad", ChunkPos.class);
            Object result = getOrLoad.invoke(provider, new ChunkPos(chunkX, chunkZ));
            if (!(result instanceof CompletableFuture<?> future)) {
                return CompletableFuture.completedFuture(Optional.empty());
            }
            return future.handle((chunk, throwable) -> {
                if (throwable != null || chunk == null) {
                    return Optional.empty();
                }
                return readChunkSample(chunk);
            });
        } catch (ReflectiveOperationException | ClassCastException e) {
            return CompletableFuture.completedFuture(Optional.empty());
        }
    }

    private static Optional<ChunkSample> readChunkSample(Object geoChunk) {
        try {
            Class<?> geoChunkClass = Class.forName("dev.gegy.terrarium.backend.GeoChunk");
            Class<?> attachmentsClass = Class.forName("dev.gegy.terrarium.backend.earth.EarthAttachments");
            Method from = attachmentsClass.getMethod("from", geoChunkClass);
            Object maybeAttachments = from.invoke(null, geoChunk);
            if (!(maybeAttachments instanceof Optional<?> optional) || optional.isEmpty()) {
                return Optional.empty();
            }
            Object attachments = optional.get();
            Object rainfall = attachments.getClass().getMethod("annualRainfall").invoke(attachments);
            Object elevation = attachments.getClass().getMethod("elevation").invoke(attachments);
            Method getRainfall = rainfall.getClass().getMethod("getRainfall", int.class, int.class);
            Method getElevation = elevation.getClass().getMethod("getInt", int.class, int.class);

            int[] rainfallMm = new int[16 * 16];
            int[] elevationMeters = new int[16 * 16];
            for (int z = 0; z < 16; z++) {
                for (int x = 0; x < 16; x++) {
                    int index = x + z * 16;
                    rainfallMm[index] = ((Number) getRainfall.invoke(rainfall, x, z)).intValue();
                    elevationMeters[index] = ((Number) getElevation.invoke(elevation, x, z)).intValue();
                }
            }
            return Optional.of(new ChunkSample(rainfallMm, elevationMeters));
        } catch (ReflectiveOperationException | ClassCastException e) {
            return Optional.empty();
        }
    }

    private static boolean classesPresent() {
        Boolean present = terrariumClassesPresent;
        if (present != null) {
            return present;
        }
        try {
            Class.forName("dev.gegy.terrarium.world.GeoProviderHolder");
            Class.forName("dev.gegy.terrarium.backend.earth.EarthAttachments");
            terrariumClassesPresent = true;
            return true;
        } catch (ClassNotFoundException e) {
            terrariumClassesPresent = false;
            return false;
        }
    }

    record ProjectionContext(
            Object projection,
            Method lat,
            Method lon,
            Method blockX,
            Method blockZ,
            double metersPerBlock
    ) {
        Optional<GeoPoint> toGeo(double x, double z) {
            try {
                return Optional.of(new GeoPoint(
                        ((Number) lat.invoke(projection, x, z)).doubleValue(),
                        ((Number) lon.invoke(projection, x, z)).doubleValue()
                ));
            } catch (IllegalAccessException | InvocationTargetException | ClassCastException e) {
                return Optional.empty();
            }
        }

        Optional<BlockPoint> toBlock(double latitude, double longitude) {
            try {
                return Optional.of(new BlockPoint(
                        ((Number) blockX.invoke(projection, latitude, longitude)).doubleValue(),
                        ((Number) blockZ.invoke(projection, latitude, longitude)).doubleValue()
                ));
            } catch (IllegalAccessException | InvocationTargetException | ClassCastException e) {
                return Optional.empty();
            }
        }
    }

    record GeoPoint(double lat, double lon) {
    }

    record BlockPoint(double x, double z) {
    }

    record TerrariumSample(int rainfallMmPerYear, int elevationMeters) {
    }

    private record ChunkSampleKey(String dimension, long chunk) {
    }

    private record ChunkSample(int[] rainfallMmPerYear, int[] elevationMeters) {
        TerrariumSample at(int x, int z) {
            int index = x + z * 16;
            return new TerrariumSample(rainfallMmPerYear[index], elevationMeters[index]);
        }
    }
}
