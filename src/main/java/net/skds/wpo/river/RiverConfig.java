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
        public final ModConfigSpec.BooleanValue flatOutletSolver;
        public final ModConfigSpec.BooleanValue channelAxisFallback;
        public final ModConfigSpec.BooleanValue bedSlopeFallback;
        public final ModConfigSpec.BooleanValue waterBodyMagnetism;
        public final ModConfigSpec.BooleanValue generatedRiverReplacement;
        public final ModConfigSpec.BooleanValue requireRiverBiomeForFallback;
        public final ModConfigSpec.BooleanValue infiniteRiverSources;
        public final ModConfigSpec.BooleanValue physicalWaterFlow;
        public final ModConfigSpec.BooleanValue drainAtUnloadedDownstreamEdge;
        public final ModConfigSpec.BooleanValue actualRainRunoff;
        public final ModConfigSpec.BooleanValue currentFlowVectors;
        public final ModConfigSpec.BooleanValue entityCurrentForces;
        public final ModConfigSpec.BooleanValue currentParticles;
        public final ModConfigSpec.BooleanValue debugLogging;

        public final ModConfigSpec.IntValue updateInterval;
        public final ModConfigSpec.IntValue sampleRadius;
        public final ModConfigSpec.IntValue columnChecksPerPlayer;
        public final ModConfigSpec.IntValue surfaceSearchDepth;
        public final ModConfigSpec.IntValue flatSolverMaxCells;
        public final ModConfigSpec.IntValue channelAxisScanDistance;
        public final ModConfigSpec.IntValue flatHeadTolerance;
        public final ModConfigSpec.IntValue flatMinOutletDrop;
        public final ModConfigSpec.IntValue flatMinOutletBedDrop;
        public final ModConfigSpec.IntValue waterBodyMagnetRadius;
        public final ModConfigSpec.IntValue targetRiverLevel;
        public final ModConfigSpec.IntValue minimumRiverLevel;
        public final ModConfigSpec.IntValue maxTransferLevels;
        public final ModConfigSpec.IntValue sourceRefillLevels;
        public final ModConfigSpec.IntValue physicalFlowChainDistance;
        public final ModConfigSpec.IntValue minFallbackHeadDrop;
        public final ModConfigSpec.IntValue magnetMaxUphillHead;
        public final ModConfigSpec.IntValue maxReservoirLevelsPerChunk;
        public final ModConfigSpec.IntValue currentColumnDepth;
        public final ModConfigSpec.IntValue currentMaxAgeTicks;

        public final ModConfigSpec.DoubleValue fallbackRainfallMmPerYear;
        public final ModConfigSpec.DoubleValue stormRainfallMmPerMinecraftDay;
        public final ModConfigSpec.DoubleValue fallbackCatchmentAreaM2;
        public final ModConfigSpec.DoubleValue runoffCoefficient;
        public final ModConfigSpec.DoubleValue hydrologyYearMinecraftDays;
        public final ModConfigSpec.DoubleValue fallbackHorizontalMetersPerBlock;
        public final ModConfigSpec.DoubleValue verticalMetersPerBlock;
        public final ModConfigSpec.DoubleValue flatCurrentStrength;
        public final ModConfigSpec.DoubleValue minCurrentStrength;
        public final ModConfigSpec.DoubleValue maxCurrentStrength;
        public final ModConfigSpec.DoubleValue visualCurrentMultiplier;
        public final ModConfigSpec.DoubleValue rainCurrentBoost;
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
            flatOutletSolver = translate.apply("flatOutletSolver")
                    .comment("Search connected flat surface-water components for a downstream outlet so level rivers can still receive a stable current direction.")
                    .define("flatOutletSolver", true);
            channelAxisFallback = translate.apply("channelAxisFallback")
                    .comment("Infer a current along long flat surface-water corridors when there is no WPO head drop or mapped river direction.")
                    .define("channelAxisFallback", true);
            bedSlopeFallback = translate.apply("bedSlopeFallback")
                    .comment("Infer a current from the streambed's terrain slope when the water surface itself has no WPO head drop."
                            + " A wide, gently-flowing river's surface commonly sits level for many blocks even though the"
                            + " generated terrain underneath keeps descending - this reads that terrain directly instead of"
                            + " leaving the column with no current at all.")
                    .define("bedSlopeFallback", true);
            waterBodyMagnetism = translate.apply("waterBodyMagnetism")
                    .comment("Bias local river steps toward nearby larger existing water bodies so runoff bends into rivers and lakes instead of marching straight across banks.")
                    .define("waterBodyMagnetism", true);
            generatedRiverReplacement = translate.apply("generatedRiverReplacement")
                    .comment("Treat vanilla generated river-biome water columns as flowing WPO river cells.")
                    .define("generatedRiverReplacement", true);
            requireRiverBiomeForFallback = translate.apply("requireRiverBiomeForFallback")
                    .comment("Limit terrain-inferred fallback currents to vanilla river biomes. Disable for terrain/worldgen mods that label river corridors with custom climate biomes.")
                    .define("requireRiverBiomeForFallback", false);
            infiniteRiverSources = translate.apply("infiniteRiverSources")
                    .comment("Refill source-like upstream river cells so generated rivers can act as infinite headwater sources.")
                    .define("infiniteRiverSources", true);
            physicalWaterFlow = translate.apply("physicalWaterFlow")
                    .comment("Move real WPO water levels downstream through resolved river currents instead of exposing only a static flow vector.")
                    .define("physicalWaterFlow", true);
            drainAtUnloadedDownstreamEdge = translate.apply("drainAtUnloadedDownstreamEdge")
                    .comment("Move water into virtual downstream storage when the next river cell is in an unloaded chunk.")
                    .define("drainAtUnloadedDownstreamEdge", true);
            actualRainRunoff = translate.apply("actualRainRunoff")
                    .comment("Add storm runoff to river reservoirs only while the sampled river column is exposed to active rain.")
                    .define("actualRainRunoff", true);
            currentFlowVectors = translate.apply("currentFlowVectors")
                    .comment("Blend cached river currents into WPO's fluid velocity vector (getVel), which affects both client-side flow rendering and server-side entity fluid-push physics - not rendering alone.")
                    .define("currentFlowVectors", true);
            entityCurrentForces = translate.apply("entityCurrentForces")
                    .comment("Apply a direct downstream push to entities that do not fully respond to WPO fluid flow vectors, such as boats.")
                    .define("entityCurrentForces", true);
            currentParticles = translate.apply("currentParticles")
                    .comment("Emit sparse downstream bubble particles from sampled river cells to make current direction visible even without client-side current sync.")
                    .define("currentParticles", true);
            debugLogging = translate.apply("debugLogging").define("debugLogging", false);
            builder.pop();

            builder.push("Simulation");
            updateInterval = translate.apply("updateInterval")
                    .comment("Server ticks between river simulation scans.")
                    .defineInRange("updateInterval", 10, 1, 100);
            sampleRadius = translate.apply("sampleRadius")
                    .comment("Horizontal radius sampled around each player. Currents only exist where sampling reaches; water beyond this renders still.")
                    .defineInRange("sampleRadius", 128, 8, 256);
            columnChecksPerPlayer = translate.apply("columnChecksPerPlayer")
                    .comment("Candidate columns processed per active player on each update.")
                    .defineInRange("columnChecksPerPlayer", 256, 1, 256);
            surfaceSearchDepth = translate.apply("surfaceSearchDepth")
                    .comment("Vertical blocks searched below the surface heightmap for exposed water.")
                    .defineInRange("surfaceSearchDepth", 24, 1, 96);
            flatSolverMaxCells = translate.apply("flatSolverMaxCells")
                    .comment("Maximum connected surface-water cells searched when resolving a flat river outlet."
                            + " On wide bends and lakes, an origin near the edge of this horizon can miss an outlet"
                            + " that a column a few blocks closer would find, falling through to channel-axis and"
                            + " committing a different direction than its neighbors.")
                    .defineInRange("flatSolverMaxCells", 2048, 8, 4096);
            channelAxisScanDistance = translate.apply("channelAxisScanDistance")
                    .comment("Maximum blocks scanned in each cardinal direction when inferring a flat channel axis.")
                    .defineInRange("channelAxisScanDistance", 56, 8, 160);
            flatHeadTolerance = translate.apply("flatHeadTolerance")
                    .comment("Maximum WPO head difference treated as the same flat river surface while searching for an outlet."
                            + " Must exceed the mod's own level transients (see minFallbackHeadDrop) or the BFS's"
                            + " connectivity check itself fragments into a different, slosh-shaped reachable region"
                            + " from every origin, so nearby columns can find different outlets - or none at all.")
                    .defineInRange("flatHeadTolerance", 4, 0, 8);
            flatMinOutletDrop = translate.apply("flatMinOutletDrop")
                    .comment("Minimum WPO head drop that makes a neighbor count as the outlet of a flat river component."
                            + " Must exceed the mod's own level transients (river feeding/refilling moves up to"
                            + " maxTransferLevels=4 into single columns per scan) or the solver chases that noise and"
                            + " nearby columns commit conflicting directions. A real 1-block step is 8."
                            + " Must stay above flatHeadTolerance: a head diff between the two values is neither"
                            + " a same-surface cell nor a qualifying outlet, so the BFS just stalls on it.")
                    .defineInRange("flatMinOutletDrop", 6, 1, 64);
            flatMinOutletBedDrop = translate.apply("flatMinOutletBedDrop")
                    .comment("Minimum solid streambed height drop (OCEAN_FLOOR heightmap, in blocks) required on top of"
                            + " flatMinOutletDrop for a flat-solver outlet to qualify. A wide pool's own level slosh can"
                            + " fake a WPO head drop within a few columns without the terrain actually descending -"
                            + " requiring a real bed step keeps mid-pool columns from latching onto that as a fake exit.")
                    .defineInRange("flatMinOutletBedDrop", 1, 0, 16);
            waterBodyMagnetRadius = translate.apply("waterBodyMagnetRadius")
                    .comment("Horizontal radius used to find nearby river/lake mass for water-body magnetism.")
                    .defineInRange("waterBodyMagnetRadius", 32, 2, 96);
            targetRiverLevel = translate.apply("targetRiverLevel")
                    .comment("Visible river level the virtual reservoir tries to maintain.")
                    .defineInRange("targetRiverLevel", 7, 1, 8);
            minimumRiverLevel = translate.apply("minimumRiverLevel")
                    .comment("Minimum visible WPO water level retained in generated river cells while physical through-flow is active.")
                    .defineInRange("minimumRiverLevel", 4, 1, 8);
            maxTransferLevels = translate.apply("maxTransferLevels")
                    .comment("Maximum WPO water levels moved by one river cell per scan.")
                    .defineInRange("maxTransferLevels", 4, 1, 8);
            sourceRefillLevels = translate.apply("sourceRefillLevels")
                    .comment("Maximum WPO water levels added per scan to each detected infinite river source cell.")
                    .defineInRange("sourceRefillLevels", 4, 1, 8);
            physicalFlowChainDistance = translate.apply("physicalFlowChainDistance")
                    .comment("Maximum downstream river cells searched when pushing physical flow through a full WPO river chain.")
                    .defineInRange("physicalFlowChainDistance", 32, 1, 128);
            minFallbackHeadDrop = translate.apply("minFallbackHeadDrop")
                    .comment("Minimum absolute WPO head drop required for terrain fallback flow."
                            + " Must exceed the mod's own level transients (feeding/refilling and unloaded-edge"
                            + " draining move up to 4 levels per scan) or columns chase self-inflicted slosh in"
                            + " random directions instead of deferring to the coherent channel-axis solver."
                            + " A real 1-block step is 8.")
                    .defineInRange("minFallbackHeadDrop", 6, 1, 64);
            magnetMaxUphillHead = translate.apply("magnetMaxUphillHead")
                    .comment("Maximum WPO head climb allowed when magnetism bends flow toward a larger water body.")
                    .defineInRange("magnetMaxUphillHead", 16, 0, 64);
            maxReservoirLevelsPerChunk = translate.apply("maxReservoirLevelsPerChunk")
                    .comment("Cap for virtual runoff storage in one chunk, in WPO levels.")
                    .defineInRange("maxReservoirLevelsPerChunk", 4096, 8, 1_000_000);
            currentColumnDepth = translate.apply("currentColumnDepth")
                    .comment("Water blocks below the sampled river surface that inherit the same current for entity pushing.")
                    .defineInRange("currentColumnDepth", 16, 1, 16);
            currentMaxAgeTicks = translate.apply("currentMaxAgeTicks")
                    .comment("Ticks before a cached river current expires if the column is not resampled."
                            + " Must comfortably exceed the mean resample interval at the edge of sampleRadius"
                            + " (roughly (2*radius+1)^2 / columnChecksPerPlayer * updateInterval ticks), or"
                            + " mid-distance columns expire between visits and every resolution out there is an"
                            + " isolated re-seed with no neighbor context - visible as patchy, flickering flow.")
                    .defineInRange("currentMaxAgeTicks", 6000, 20, 72000);
            builder.pop();

            builder.push("Hydrology");
            fallbackRainfallMmPerYear = translate.apply("fallbackRainfallMmPerYear")
                    .comment("Annual rainfall used when Terrarium rainfall data is unavailable.")
                    .defineInRange("fallbackRainfallMmPerYear", 800.0D, 0.0D, 8000.0D);
            stormRainfallMmPerMinecraftDay = translate.apply("stormRainfallMmPerMinecraftDay")
                    .comment("Rainfall depth represented by one full Minecraft day of active vanilla rain over the configured catchment.")
                    .defineInRange("stormRainfallMmPerMinecraftDay", 12.0D, 0.0D, 1000.0D);
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
            flatCurrentStrength = translate.apply("flatCurrentStrength")
                    .comment("Base flow-vector strength assigned to flat river cells that resolve toward a downstream outlet.")
                    .defineInRange("flatCurrentStrength", 0.55D, 0.0D, 4.0D);
            minCurrentStrength = translate.apply("minCurrentStrength")
                    .comment("Minimum cached current strength for any resolved river flow.")
                    .defineInRange("minCurrentStrength", 0.12D, 0.0D, 4.0D);
            maxCurrentStrength = translate.apply("maxCurrentStrength")
                    .comment("Maximum cached current strength exposed to WPO flow vectors.")
                    .defineInRange("maxCurrentStrength", 1.25D, 0.0D, 8.0D);
            visualCurrentMultiplier = translate.apply("visualCurrentMultiplier")
                    .comment("Multiplier applied when cached river current is blended into WPO fluid flow vectors.")
                    .defineInRange("visualCurrentMultiplier", 1.6D, 0.0D, 8.0D);
            rainCurrentBoost = translate.apply("rainCurrentBoost")
                    .comment("Temporary current-strength boost while the sampled river column is exposed to active rain.")
                    .defineInRange("rainCurrentBoost", 0.15D, 0.0D, 4.0D);
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
