package net.skds.wpo.river;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.levelgen.Heightmap;
import net.skds.wpo.api.WPOFluidAccess;

final class OsmWaterwayProvider {

    private static final URI[] OVERPASS_URIS = {
            URI.create("https://overpass-api.de/api/interpreter"),
            URI.create("https://overpass.kumi.systems/api/interpreter")
    };
    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(6))
            .build();
    private static final Map<TileKey, TileEntry> TILE_CACHE = new ConcurrentHashMap<>();
    private static final long EMPTY_RETRY_BACKOFF_MILLIS = 5L * 60L * 1000L;
    private static final double EARTH_METERS_PER_DEGREE = 111_320.0D;

    Optional<FlowVector> findDirection(ServerLevel level, double blockX, double blockZ, TerrariumBridge.ProjectionContext projection) {
        if (!RiverConfig.COMMON.osmWaterwayDirections.get()) {
            return Optional.empty();
        }
        Optional<TerrariumBridge.GeoPoint> geo = projection.toGeo(blockX, blockZ);
        if (geo.isEmpty()) {
            return Optional.empty();
        }

        double tileDegrees = RiverConfig.COMMON.osmTileDegrees.get();
        TileKey tile = TileKey.from(level.dimension().location().toString(), geo.get(), tileDegrees);
        CompletableFuture<List<Waterway>> future = tileFuture(tile);
        if (!future.isDone()) {
            return Optional.empty();
        }

        double maxDistance = RiverConfig.COMMON.osmMatchDistanceMeters.get();
        FlowVector best = null;
        for (Waterway waterway : future.getNow(List.of())) {
            Optional<FlowVector> vector = waterway.directionNear(geo.get(), projection, maxDistance);
            if (vector.isPresent() && (best == null || vector.get().distanceMeters() < best.distanceMeters())) {
                best = vector.get();
            }
        }
        if (best == null) {
            return Optional.empty();
        }

        // OSM way point order isn't guaranteed to follow real-world flow direction - a
        // backwards-digitized way would otherwise silently reverse the published flow with
        // nothing to catch it. Only reject a clear uphill step; flat bed reaches (deltas,
        // wide slow water) are common and aren't evidence the geometry is wrong.
        if (!matchesBedSlope(level, blockX, blockZ, best.direction())) {
            if (RiverConfig.COMMON.debugLogging.get()) {
                RiverDynamics.LOGGER.debug(
                        "OSM waterway direction {} at {},{} climbs the bed slope; ignoring possibly backwards-digitized way",
                        best.direction(), blockX, blockZ);
            }
            return Optional.empty();
        }
        return Optional.of(best);
    }

    private static boolean matchesBedSlope(ServerLevel level, double blockX, double blockZ, Direction direction) {
        int sourceX = (int) Math.floor(blockX);
        int sourceZ = (int) Math.floor(blockZ);
        int targetX = sourceX + direction.getStepX();
        int targetZ = sourceZ + direction.getStepZ();
        BlockPos target = new BlockPos(targetX, level.getMinBuildHeight(), targetZ);
        if (!WPOFluidAccess.isChunkLoaded(level, target)) {
            return true;
        }
        int sourceBed = level.getHeight(Heightmap.Types.OCEAN_FLOOR, sourceX, sourceZ);
        int targetBed = level.getHeight(Heightmap.Types.OCEAN_FLOOR, targetX, targetZ);
        return targetBed <= sourceBed;
    }

    static void clear(ServerLevel level) {
        String dimension = level.dimension().location().toString();
        TILE_CACHE.keySet().removeIf(key -> key.dimension().equals(dimension));
    }

    // A tile that failed (or genuinely has no mapped waterways) resolves to an empty list either
    // way - retry it after a backoff instead of caching that result forever for the session.
    private static CompletableFuture<List<Waterway>> tileFuture(TileKey tile) {
        TileEntry existing = TILE_CACHE.get(tile);
        if (existing != null && existing.future().isDone() && existing.future().getNow(List.of()).isEmpty()
                && System.currentTimeMillis() - existing.fetchedAtMillis() > EMPTY_RETRY_BACKOFF_MILLIS) {
            TILE_CACHE.remove(tile, existing);
        }
        return TILE_CACHE.computeIfAbsent(tile, key -> new TileEntry(loadTileAsync(key), System.currentTimeMillis())).future();
    }

    private static CompletableFuture<List<Waterway>> loadTileAsync(TileKey tile) {
        return CompletableFuture.supplyAsync(() -> fetchTile(tile));
    }

    private record TileEntry(CompletableFuture<List<Waterway>> future, long fetchedAtMillis) {
    }

    private static List<Waterway> fetchTile(TileKey tile) {
        Bounds bounds = tile.bounds();
        String query = String.format(Locale.ROOT, """
                [out:json][timeout:12];
                (
                  way["waterway"~"river|canal|stream|ditch|drain"](%f,%f,%f,%f);
                );
                out tags geom;
                """,
                bounds.south(), bounds.west(), bounds.north(), bounds.east()
        );
        String requestBody = "data=" + URLEncoder.encode(query, StandardCharsets.UTF_8);
        for (URI uri : OVERPASS_URIS) {
            HttpRequest request = HttpRequest.newBuilder(uri)
                    .timeout(Duration.ofSeconds(16))
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .header("Accept", "application/json")
                    .header("User-Agent", "WPO-River-Dynamics/0.1")
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                    .build();
            try {
                HttpResponse<String> response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() == 200) {
                    return parseWaterways(response.body());
                }
            } catch (IOException e) {
                if (RiverConfig.COMMON.debugLogging.get()) {
                    RiverDynamics.LOGGER.debug("OSM waterway request failed for {} from {}", tile, uri, e);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return List.of();
            } catch (RuntimeException e) {
                if (RiverConfig.COMMON.debugLogging.get()) {
                    RiverDynamics.LOGGER.debug("OSM waterway response parse failed for {} from {}", tile, uri, e);
                }
            }
        }
        return List.of();
    }

    private static List<Waterway> parseWaterways(String body) {
        JsonObject root = JsonParser.parseString(body).getAsJsonObject();
        JsonArray elements = root.getAsJsonArray("elements");
        if (elements == null) {
            return List.of();
        }

        List<Waterway> waterways = new ArrayList<>();
        for (JsonElement element : elements) {
            if (!element.isJsonObject()) {
                continue;
            }
            JsonObject object = element.getAsJsonObject();
            JsonArray geometry = object.getAsJsonArray("geometry");
            if (geometry == null || geometry.size() < 2) {
                continue;
            }
            List<TerrariumBridge.GeoPoint> points = new ArrayList<>(geometry.size());
            for (JsonElement pointElement : geometry) {
                if (!pointElement.isJsonObject()) {
                    continue;
                }
                JsonObject point = pointElement.getAsJsonObject();
                if (point.has("lat") && point.has("lon")) {
                    points.add(new TerrariumBridge.GeoPoint(point.get("lat").getAsDouble(), point.get("lon").getAsDouble()));
                }
            }
            if (points.size() >= 2) {
                waterways.add(new Waterway(points));
            }
        }
        return waterways;
    }

    private record Waterway(List<TerrariumBridge.GeoPoint> points) {
        Optional<FlowVector> directionNear(
                TerrariumBridge.GeoPoint sample,
                TerrariumBridge.ProjectionContext projection,
                double maxDistance
        ) {
            FlowVector best = null;
            for (int i = 1; i < points.size(); i++) {
                TerrariumBridge.GeoPoint from = points.get(i - 1);
                TerrariumBridge.GeoPoint to = points.get(i);
                double distance = distanceToSegmentMeters(sample, from, to);
                if (distance > maxDistance) {
                    continue;
                }
                Optional<TerrariumBridge.BlockPoint> blockFrom = projection.toBlock(from.lat(), from.lon());
                Optional<TerrariumBridge.BlockPoint> blockTo = projection.toBlock(to.lat(), to.lon());
                if (blockFrom.isEmpty() || blockTo.isEmpty()) {
                    continue;
                }
                double dx = blockTo.get().x() - blockFrom.get().x();
                double dz = blockTo.get().z() - blockFrom.get().z();
                if (Math.abs(dx) + Math.abs(dz) < 0.001D) {
                    continue;
                }
                Direction direction = Direction.getNearest((float) dx, 0.0F, (float) dz);
                FlowVector vector = new FlowVector(direction, distance);
                if (best == null || vector.distanceMeters() < best.distanceMeters()) {
                    best = vector;
                }
            }
            return Optional.ofNullable(best);
        }
    }

    record FlowVector(Direction direction, double distanceMeters) {
    }

    private record TileKey(String dimension, int lat, int lon, double degrees) {
        static TileKey from(String dimension, TerrariumBridge.GeoPoint point, double degrees) {
            return new TileKey(dimension, (int) Math.floor(point.lat() / degrees), (int) Math.floor(point.lon() / degrees), degrees);
        }

        Bounds bounds() {
            double margin = degrees * 0.2D;
            return new Bounds(
                    clampLatitude(lat * degrees - margin),
                    clampLatitude((lat + 1) * degrees + margin),
                    clampLongitude(lon * degrees - margin),
                    clampLongitude((lon + 1) * degrees + margin)
            );
        }
    }

    private record Bounds(double south, double north, double west, double east) {
    }

    private static double clampLatitude(double latitude) {
        return Math.max(-85.0D, Math.min(85.0D, latitude));
    }

    private static double clampLongitude(double longitude) {
        return Math.max(-180.0D, Math.min(180.0D, longitude));
    }

    private static double distanceToSegmentMeters(
            TerrariumBridge.GeoPoint sample,
            TerrariumBridge.GeoPoint a,
            TerrariumBridge.GeoPoint b
    ) {
        TerrariumBridge.GeoPoint closest = closestPointOnSegment(sample, a, b);
        return distanceMeters(sample, closest);
    }

    private static TerrariumBridge.GeoPoint closestPointOnSegment(
            TerrariumBridge.GeoPoint sample,
            TerrariumBridge.GeoPoint a,
            TerrariumBridge.GeoPoint b
    ) {
        double cosLat = Math.cos(Math.toRadians(sample.lat()));
        double px = sample.lon() * EARTH_METERS_PER_DEGREE * cosLat;
        double py = sample.lat() * EARTH_METERS_PER_DEGREE;
        double ax = a.lon() * EARTH_METERS_PER_DEGREE * cosLat;
        double ay = a.lat() * EARTH_METERS_PER_DEGREE;
        double bx = b.lon() * EARTH_METERS_PER_DEGREE * cosLat;
        double by = b.lat() * EARTH_METERS_PER_DEGREE;
        double dx = bx - ax;
        double dy = by - ay;
        if (dx == 0.0D && dy == 0.0D) {
            return a;
        }
        double t = Math.max(0.0D, Math.min(1.0D, ((px - ax) * dx + (py - ay) * dy) / (dx * dx + dy * dy)));
        return new TerrariumBridge.GeoPoint(
                a.lat() + (b.lat() - a.lat()) * t,
                a.lon() + (b.lon() - a.lon()) * t
        );
    }

    private static double distanceMeters(TerrariumBridge.GeoPoint a, TerrariumBridge.GeoPoint b) {
        double cosLat = Math.cos(Math.toRadians((a.lat() + b.lat()) * 0.5D));
        double dx = (b.lon() - a.lon()) * EARTH_METERS_PER_DEGREE * cosLat;
        double dy = (b.lat() - a.lat()) * EARTH_METERS_PER_DEGREE;
        return Math.hypot(dx, dy);
    }
}
