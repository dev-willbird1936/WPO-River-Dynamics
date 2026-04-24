package net.skds.wpo.river;

import java.nio.file.Paths;
import java.util.function.Function;

import org.apache.commons.lang3.tuple.Pair;

import net.neoforged.fml.ModContainer;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.common.ModConfigSpec;

public final class RiverConfig {

    public static final Common COMMON;
    private static final ModConfigSpec SPEC;

    static {
        Pair<Common, ModConfigSpec> common = new ModConfigSpec.Builder().configure(Common::new);
        COMMON = common.getLeft();
        SPEC = common.getRight();
    }

    private RiverConfig() {
    }

    public static void init(ModContainer container) {
        Paths.get(System.getProperty("user.dir"), "config", RiverDynamics.MOD_ID).toFile().mkdirs();
        container.registerConfig(ModConfig.Type.COMMON, SPEC, Paths.get(RiverDynamics.MOD_ID, "common.toml").toString());
    }

    public static final class Common {
        public final ModConfigSpec.BooleanValue enabled;
        public final ModConfigSpec.BooleanValue overworldOnly;
        public final ModConfigSpec.BooleanValue terrariumIntegration;
        public final ModConfigSpec.BooleanValue osmWaterwayDirections;
        public final ModConfigSpec.BooleanValue terrainFallbackDirections;
        public final ModConfigSpec.BooleanValue waterBodyMagnetism;
        public final ModConfigSpec.BooleanValue drainAtUnloadedDownstreamEdge;
        public final ModConfigSpec.BooleanValue debugLogging;

        public final ModConfigSpec.IntValue updateInterval;
        public final ModConfigSpec.IntValue sampleRadius;
        public final ModConfigSpec.IntValue columnChecksPerPlayer;
        public final ModConfigSpec.IntValue surfaceSearchDepth;
        public final ModConfigSpec.IntValue waterBodyMagnetRadius;
        public final ModConfigSpec.IntValue targetRiverLevel;
        public final ModConfigSpec.IntValue maxTransferLevels;
        public final ModConfigSpec.IntValue minFallbackHeadDrop;
        public final ModConfigSpec.IntValue magnetMaxUphillHead;
        public final ModConfigSpec.IntValue maxReservoirLevelsPerChunk;

        public final ModConfigSpec.DoubleValue fallbackRainfallMmPerYear;
        public final ModConfigSpec.DoubleValue fallbackCatchmentAreaM2;
        public final ModConfigSpec.DoubleValue runoffCoefficient;
        public final ModConfigSpec.DoubleValue hydrologyYearMinecraftDays;
        public final ModConfigSpec.DoubleValue fallbackHorizontalMetersPerBlock;
        public final ModConfigSpec.DoubleValue verticalMetersPerBlock;
        public final ModConfigSpec.DoubleValue osmTileDegrees;
        public final ModConfigSpec.DoubleValue osmMatchDistanceMeters;

        private Common(ModConfigSpec.Builder builder) {
            Function<String, ModConfigSpec.Builder> translate = key -> builder.translation(RiverDynamics.MOD_ID + ".config." + key);

            builder.push("Systems");
            enabled = translate.apply("enabled").define("enabled", true);
            overworldOnly = translate.apply("overworldOnly").define("overworldOnly", true);
            terrariumIntegration = translate.apply("terrariumIntegration")
                    .comment("Use Terrarium projection, annual rainfall, and elevation data when Terrarium is installed.")
                    .define("terrariumIntegration", true);
            osmWaterwayDirections = translate.apply("osmWaterwayDirections")
                    .comment("Use OpenStreetMap waterway line direction through Overpass when Terrarium projection data is available.")
                    .define("osmWaterwayDirections", true);
            terrainFallbackDirections = translate.apply("terrainFallbackDirections")
                    .comment("Infer flow from local WPO water-head gradients when explicit mapped direction is unavailable.")
                    .define("terrainFallbackDirections", true);
            waterBodyMagnetism = translate.apply("waterBodyMagnetism")
                    .comment("Bias local river steps toward nearby larger existing water bodies so runoff bends into rivers and lakes instead of marching straight across banks.")
                    .define("waterBodyMagnetism", true);
            drainAtUnloadedDownstreamEdge = translate.apply("drainAtUnloadedDownstreamEdge")
                    .comment("Move water into virtual downstream storage when the next river cell is in an unloaded chunk.")
                    .define("drainAtUnloadedDownstreamEdge", true);
            debugLogging = translate.apply("debugLogging").define("debugLogging", false);
            builder.pop();

            builder.push("Simulation");
            updateInterval = translate.apply("updateInterval")
                    .comment("Server ticks between river simulation scans.")
                    .defineInRange("updateInterval", 10, 1, 100);
            sampleRadius = translate.apply("sampleRadius")
                    .comment("Horizontal radius sampled around each player.")
                    .defineInRange("sampleRadius", 48, 8, 256);
            columnChecksPerPlayer = translate.apply("columnChecksPerPlayer")
                    .comment("Candidate columns processed per active player on each update.")
                    .defineInRange("columnChecksPerPlayer", 32, 1, 256);
            surfaceSearchDepth = translate.apply("surfaceSearchDepth")
                    .comment("Vertical blocks searched below the surface heightmap for exposed water.")
                    .defineInRange("surfaceSearchDepth", 24, 1, 96);
            waterBodyMagnetRadius = translate.apply("waterBodyMagnetRadius")
                    .comment("Horizontal radius used to find nearby river/lake mass for water-body magnetism.")
                    .defineInRange("waterBodyMagnetRadius", 32, 2, 96);
            targetRiverLevel = translate.apply("targetRiverLevel")
                    .comment("Visible river level the virtual reservoir tries to maintain.")
                    .defineInRange("targetRiverLevel", 7, 1, 8);
            maxTransferLevels = translate.apply("maxTransferLevels")
                    .comment("Maximum WPO water levels moved by one river cell per scan.")
                    .defineInRange("maxTransferLevels", 2, 1, 8);
            minFallbackHeadDrop = translate.apply("minFallbackHeadDrop")
                    .comment("Minimum absolute WPO head drop required for terrain fallback flow.")
                    .defineInRange("minFallbackHeadDrop", 2, 1, 64);
            magnetMaxUphillHead = translate.apply("magnetMaxUphillHead")
                    .comment("Maximum WPO head climb allowed when magnetism bends flow toward a larger water body.")
                    .defineInRange("magnetMaxUphillHead", 16, 0, 64);
            maxReservoirLevelsPerChunk = translate.apply("maxReservoirLevelsPerChunk")
                    .comment("Cap for virtual runoff storage in one chunk, in WPO levels.")
                    .defineInRange("maxReservoirLevelsPerChunk", 4096, 8, 1_000_000);
            builder.pop();

            builder.push("Hydrology");
            fallbackRainfallMmPerYear = translate.apply("fallbackRainfallMmPerYear")
                    .comment("Annual rainfall used when Terrarium rainfall data is unavailable.")
                    .defineInRange("fallbackRainfallMmPerYear", 800.0D, 0.0D, 8000.0D);
            fallbackCatchmentAreaM2 = translate.apply("fallbackCatchmentAreaM2")
                    .comment("Synthetic upstream area feeding each sampled river cell when no hydrography dataset is available.")
                    .defineInRange("fallbackCatchmentAreaM2", 25_000.0D, 1.0D, 1_000_000_000.0D);
            runoffCoefficient = translate.apply("runoffCoefficient")
                    .comment("Fraction of annual rainfall converted to river discharge.")
                    .defineInRange("runoffCoefficient", 0.35D, 0.0D, 1.0D);
            hydrologyYearMinecraftDays = translate.apply("hydrologyYearMinecraftDays")
                    .comment("Minecraft days treated as one hydrological year for rainfall budget distribution.")
                    .defineInRange("hydrologyYearMinecraftDays", 96.0D, 1.0D, 3650.0D);
            fallbackHorizontalMetersPerBlock = translate.apply("fallbackHorizontalMetersPerBlock")
                    .comment("Horizontal scale used when Terrarium projection scale is unavailable.")
                    .defineInRange("fallbackHorizontalMetersPerBlock", 1.0D, 0.001D, 100000.0D);
            verticalMetersPerBlock = translate.apply("verticalMetersPerBlock")
                    .comment("Vertical meters represented by one Minecraft block for WPO volume conversion.")
                    .defineInRange("verticalMetersPerBlock", 1.0D, 0.001D, 100000.0D);
            builder.pop();

            builder.push("OpenStreetMap");
            osmTileDegrees = translate.apply("osmTileDegrees")
                    .comment("Latitude/longitude tile size used for Overpass waterway queries.")
                    .defineInRange("osmTileDegrees", 0.02D, 0.002D, 0.25D);
            osmMatchDistanceMeters = translate.apply("osmMatchDistanceMeters")
                    .comment("Maximum distance from a block center to an OSM waterway line for downstream direction.")
                    .defineInRange("osmMatchDistanceMeters", 18.0D, 1.0D, 250.0D);
            builder.pop();
        }
    }
}
