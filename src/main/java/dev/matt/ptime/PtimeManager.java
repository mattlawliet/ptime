package dev.matt.ptime;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.authlib.GameProfile;
import com.mojang.blaze3d.platform.NativeImage;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.Writer;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.zone.ZoneRulesException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.renderer.texture.TextureManager;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.player.PlayerSkin;

public final class PtimeManager {
    private static final Set<String> TIMEZONE_IDS = Collections.unmodifiableSet(new TreeSet<>(ZoneId.getAvailableZoneIds()));
    private static final Map<String, String> TIMEZONE_SHORTHANDS = createTimezoneShorthands();
    private static final Comparator<TrackedPlayer> PLAYER_ORDER = Comparator.comparing(TrackedPlayer::name, String.CASE_INSENSITIVE_ORDER);
    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
        .followRedirects(HttpClient.Redirect.NORMAL)
        .connectTimeout(Duration.ofSeconds(10))
        .build();
    private static final Duration SKIN_CACHE_TTL = Duration.ofDays(7);
    private static final Duration SKIN_FETCH_MIN_INTERVAL = Duration.ofMinutes(1);
    private static final Map<String, String> BUNDLED_PLACE_TIMEZONES = loadBundledPlaceTimezones();

    private final Path configPath = FabricLoader.getInstance().getConfigDir().resolve("ptime.json");
    private final Path cachePath = FabricLoader.getInstance().getConfigDir().resolve("ptime_cache.json");
    private final Path skinsDir = FabricLoader.getInstance().getConfigDir().resolve("ptime_skins");
    private final Map<String, TrackedPlayer> trackedPlayers = new LinkedHashMap<>();
    private final Set<String> favoritePlayers = new LinkedHashSet<>();
    private final Map<String, DownloadedSkin> downloadedSkins = new ConcurrentHashMap<>();
    private final Set<String> queuedSkinDownloads = ConcurrentHashMap.newKeySet();
    private final ExecutorService skinExecutor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "ptime-skin-loader");
        thread.setDaemon(true);
        return thread;
    });
    private final Map<String, CachedSkinUrl> skinUrlCache = new ConcurrentHashMap<>();
    private final Map<String, String> geocodeCache = new ConcurrentHashMap<>();
    private String selfTimezoneId = ZoneId.systemDefault().getId();

    public PtimeManager() {
        try {
            Files.createDirectories(skinsDir);
        } catch (IOException ignored) {
        }
        load();
        loadCache();
    }

    public static boolean isValidPlayerName(String playerName) {
        return playerName != null && playerName.matches("[A-Za-z0-9_]{3,16}");
    }

    public synchronized void addOrUpdatePlayer(String playerName, ZoneId zoneId) {
        trackedPlayers.put(normalize(playerName), new TrackedPlayer(playerName, zoneId.getId()));
        save();

        Minecraft client = Minecraft.getInstance();
        if (client != null && client.getConnection() != null) {
            ensureSkinLoaded(client, playerName);
        }
    }

    public synchronized boolean remove(String playerName) {
        String normalized = normalize(playerName);
        boolean removed = trackedPlayers.remove(normalized) != null;
        favoritePlayers.remove(normalized);
        if (removed) {
            save();
        }
        return removed;
    }

    public synchronized boolean isFavorite(String playerName) {
        return favoritePlayers.contains(normalize(playerName));
    }

    public synchronized void toggleFavorite(String playerName) {
        String normalized = normalize(playerName);
        if (!trackedPlayers.containsKey(normalized)) {
            return;
        }

        if (!favoritePlayers.add(normalized)) {
            favoritePlayers.remove(normalized);
        }
        save();
    }

    public synchronized List<TrackedPlayer> trackedPlayers() {
        return trackedPlayers.values().stream().sorted(PLAYER_ORDER).toList();
    }

    public synchronized List<String> trackedPlayerNames() {
        return trackedPlayers.values().stream().map(TrackedPlayer::name).sorted(String.CASE_INSENSITIVE_ORDER).toList();
    }

    public synchronized void setSelfTimezone(ZoneId zoneId) {
        selfTimezoneId = zoneId.getId();
        save();
    }

    public synchronized ZoneId selfZoneId() {
        return ZoneId.of(selfTimezoneId);
    }

    public synchronized String selfTimezoneId() {
        return selfTimezoneId;
    }

    public List<String> onlinePlayerNames() {
        Minecraft client = Minecraft.getInstance();
        if (client == null || client.getConnection() == null) {
            return List.of();
        }

        return client.getConnection().getOnlinePlayers().stream()
            .map(PlayerInfo::getProfile)
            .map(GameProfile::name)
            .filter(PtimeManager::isValidPlayerName)
            .sorted(String.CASE_INSENSITIVE_ORDER)
            .toList();
    }

    public Collection<String> suggestedTimezones() {
        return TIMEZONE_IDS;
    }

    public Collection<String> suggestedTimezoneInputs() {
        Set<String> suggestions = new LinkedHashSet<>(TIMEZONE_SHORTHANDS.keySet());
        for (int i = -12; i <= 14; i++) {
            if (i == 0) {
                suggestions.add("+0");
            } else {
                suggestions.add(i > 0 ? "+" + i : Integer.toString(i));
            }
        }
        suggestions.addAll(TIMEZONE_IDS);
        return suggestions;
    }

    public synchronized Component describeTrackedPlayers() {
        if (trackedPlayers.isEmpty()) {
            return Component.literal("ptime has no tracked players yet. Self timezone: " + selfTimezoneId + ".");
        }

        MutableComponent text = Component.literal("ptime (" + selfTimezoneId + "): ");
        boolean first = true;
        for (TrackedPlayer player : trackedPlayers()) {
            if (!first) {
                text.append(Component.literal(", "));
            }
            first = false;
            text.append(Component.literal(player.name() + " -> " + player.timezoneId()));
        }
        return text;
    }

    public void onSessionStarted(Minecraft client) {
        queuedSkinDownloads.clear();
    }

    public void onSessionEnded() {
        queuedSkinDownloads.clear();
    }

    public Optional<SkinRenderData> getSkin(String playerName) {
        Minecraft client = Minecraft.getInstance();
        if (client == null) {
            return Optional.empty();
        }

        PlayerInfo onlineEntry = findOnlinePlayer(client, playerName);
        if (onlineEntry != null) {
            return Optional.of(SkinRenderData.online(onlineEntry.getSkin()));
        }

        DownloadedSkin downloadedSkin = downloadedSkins.get(normalize(playerName));
        if (downloadedSkin != null) {
            return Optional.of(SkinRenderData.downloaded(downloadedSkin.textureId()));
        }

        if (client.getConnection() != null) {
            ensureSkinLoaded(client, playerName);
        }

        return Optional.empty();
    }

    public void ensureSkinLoaded(Minecraft client, String playerName) {
        if (!isValidPlayerName(playerName)) {
            return;
        }

        if (findOnlinePlayer(client, playerName) != null) {
            return;
        }

        String normalized = normalize(playerName);
        if (downloadedSkins.containsKey(normalized) || !queuedSkinDownloads.add(normalized)) {
            return;
        }

        Path localPath = skinsDir.resolve(normalized + ".png");
        if (Files.exists(localPath)) {
            try (InputStream stream = Files.newInputStream(localPath)) {
                NativeImage image = NativeImage.read(stream);
                client.execute(() -> {
                    registerSkinTexture(client, playerName, normalized, image);
                    queuedSkinDownloads.remove(normalized);
                });
                return;
            } catch (IOException ignored) {
            }
        }

        skinExecutor.execute(() -> downloadSkin(client, playerName, normalized));
    }

    private PlayerInfo findOnlinePlayer(Minecraft client, String playerName) {
        if (client.getConnection() == null) {
            return null;
        }

        for (PlayerInfo entry : client.getConnection().getOnlinePlayers()) {
            if (entry.getProfile().name().equalsIgnoreCase(playerName)) {
                return entry;
            }
        }

        return null;
    }

    private void downloadSkin(Minecraft client, String playerName, String normalized) {
        try {
            String cachedUrl = getCachedSkinUrl(normalized);
            Optional<String> textureUrl;
            if (cachedUrl != null) {
                textureUrl = Optional.of(cachedUrl);
            } else {
                textureUrl = resolveSkinTextureUrl(playerName);
                if (textureUrl.isPresent()) {
                    saveCachedSkinUrl(normalized, textureUrl.get());
                }
            }

            if (textureUrl.isEmpty()) {
                return;
            }

            HttpRequest imageRequest = HttpRequest.newBuilder(URI.create(textureUrl.get()))
                .header("User-Agent", "ptime")
                .timeout(Duration.ofSeconds(15))
                .build();
            HttpResponse<InputStream> imageResponse = HTTP_CLIENT.send(imageRequest, HttpResponse.BodyHandlers.ofInputStream());
            if (imageResponse.statusCode() < 200 || imageResponse.statusCode() >= 300) {
                return;
            }

            try (InputStream stream = imageResponse.body()) {
                NativeImage image = NativeImage.read(stream);
                Path localPath = skinsDir.resolve(normalized + ".png");
                image.writeToFile(localPath);
                client.execute(() -> registerSkinTexture(client, playerName, normalized, image));
            }
        } catch (Exception ignored) {
        } finally {
            queuedSkinDownloads.remove(normalized);
        }
    }

    private void registerSkinTexture(Minecraft client, String playerName, String normalized, NativeImage image) {
        Identifier textureId = Identifier.fromNamespaceAndPath("ptime", "skins/" + normalized.toLowerCase(Locale.ROOT));
        DynamicTexture texture = new DynamicTexture(() -> playerName, image);
        client.getTextureManager().register(textureId, texture);
        texture.upload();
        downloadedSkins.put(normalized, new DownloadedSkin(textureId));
    }

    private Optional<String> resolveSkinTextureUrl(String playerName) throws IOException, InterruptedException {
        String encodedName = URLEncoder.encode(playerName, StandardCharsets.UTF_8);
        HttpRequest profileRequest = HttpRequest.newBuilder(URI.create("https://api.mojang.com/users/profiles/minecraft/" + encodedName))
            .header("User-Agent", "ptime")
            .timeout(Duration.ofSeconds(10))
            .build();

        HttpResponse<String> profileResponse = HTTP_CLIENT.send(profileRequest, HttpResponse.BodyHandlers.ofString());
        if (profileResponse.statusCode() == 204 || profileResponse.statusCode() == 404 || profileResponse.body().isBlank()) {
            return Optional.empty();
        }
        if (profileResponse.statusCode() < 200 || profileResponse.statusCode() >= 300) {
            return Optional.empty();
        }

        JsonObject profileJson = JsonParser.parseString(profileResponse.body()).getAsJsonObject();
        String uuid = profileJson.get("id").getAsString();

        HttpRequest texturesRequest = HttpRequest.newBuilder(URI.create("https://sessionserver.mojang.com/session/minecraft/profile/" + uuid))
            .header("User-Agent", "ptime")
            .timeout(Duration.ofSeconds(10))
            .build();

        HttpResponse<String> texturesResponse = HTTP_CLIENT.send(texturesRequest, HttpResponse.BodyHandlers.ofString());
        if (texturesResponse.statusCode() < 200 || texturesResponse.statusCode() >= 300) {
            return Optional.empty();
        }

        JsonObject texturesJson = JsonParser.parseString(texturesResponse.body()).getAsJsonObject();
        JsonArray properties = texturesJson.getAsJsonArray("properties");
        if (properties == null) {
            return Optional.empty();
        }

        for (JsonElement element : properties) {
            JsonObject propertyJson = element.getAsJsonObject();
            if ("textures".equals(propertyJson.get("name").getAsString())) {
                try {
                    String decoded = new String(
                        java.util.Base64.getDecoder().decode(propertyJson.get("value").getAsString()),
                        StandardCharsets.UTF_8
                    );
                    JsonObject decodedJson = JsonParser.parseString(decoded).getAsJsonObject();
                    JsonObject textures = decodedJson.getAsJsonObject("textures");
                    if (textures == null || !textures.has("SKIN")) {
                        return Optional.empty();
                    }
                    return Optional.of(textures.getAsJsonObject("SKIN").get("url").getAsString());
                } catch (RuntimeException exception) {
                    return Optional.empty();
                }
            }
        }

        return Optional.empty();
    }

    private String getCachedSkinUrl(String normalizedName) {
        CachedSkinUrl cached = skinUrlCache.get(normalizedName);
        if (cached == null) {
            return null;
        }
        if (Instant.now().isAfter(cached.cachedAt().plus(SKIN_CACHE_TTL))) {
            skinUrlCache.remove(normalizedName);
            saveCache();
            return null;
        }
        return cached.url();
    }

    private void saveCachedSkinUrl(String normalizedName, String url) {
        Instant now = Instant.now();
        CachedSkinUrl existing = skinUrlCache.get(normalizedName);
        if (existing != null && Duration.between(existing.cachedAt(), now).compareTo(SKIN_FETCH_MIN_INTERVAL) < 0) {
            return;
        }
        skinUrlCache.put(normalizedName, new CachedSkinUrl(url, now));
        saveCache();
    }

    private void loadCache() {
        if (!Files.exists(cachePath)) {
            return;
        }

        try (Reader reader = Files.newBufferedReader(cachePath)) {
            JsonElement root = JsonParser.parseReader(reader);
            if (!root.isJsonObject()) {
                return;
            }

            JsonObject rootObject = root.getAsJsonObject();
            if (rootObject.has("skins")) {
                JsonObject skins = rootObject.getAsJsonObject("skins");
                for (Map.Entry<String, JsonElement> entry : skins.entrySet()) {
                    JsonObject skinEntry = entry.getValue().getAsJsonObject();
                    String url = skinEntry.get("url").getAsString();
                    String cachedAtStr = skinEntry.get("cachedAt").getAsString();
                    Instant cachedAt = Instant.parse(cachedAtStr);
                    if (Duration.between(cachedAt, Instant.now()).compareTo(SKIN_CACHE_TTL) <= 0) {
                        skinUrlCache.put(entry.getKey(), new CachedSkinUrl(url, cachedAt));
                    }
                }
            }

            if (rootObject.has("geocodes")) {
                JsonObject geocodes = rootObject.getAsJsonObject("geocodes");
                for (Map.Entry<String, JsonElement> entry : geocodes.entrySet()) {
                    geocodeCache.put(entry.getKey(), entry.getValue().getAsString());
                }
            }
        } catch (Exception ignored) {
        }
    }

    private synchronized void saveCache() {
        try {
            Files.createDirectories(cachePath.getParent());

            JsonObject skins = new JsonObject();
            for (Map.Entry<String, CachedSkinUrl> entry : skinUrlCache.entrySet()) {
                JsonObject skinEntry = new JsonObject();
                skinEntry.addProperty("url", entry.getValue().url());
                skinEntry.addProperty("cachedAt", entry.getValue().cachedAt().toString());
                skins.add(entry.getKey(), skinEntry);
            }

            JsonObject geocodes = new JsonObject();
            for (Map.Entry<String, String> entry : geocodeCache.entrySet()) {
                geocodes.addProperty(entry.getKey(), entry.getValue());
            }

            JsonObject root = new JsonObject();
            root.addProperty("version", 1);
            root.add("skins", skins);
            root.add("geocodes", geocodes);

            try (Writer writer = Files.newBufferedWriter(cachePath)) {
                writer.write(root.toString());
            }
        } catch (IOException ignored) {
        }
    }

    public synchronized void load() {
        trackedPlayers.clear();
        favoritePlayers.clear();

        if (!Files.exists(configPath)) {
            return;
        }

        try (Reader reader = Files.newBufferedReader(configPath)) {
            JsonElement root = JsonParser.parseReader(reader);
            if (!root.isJsonObject()) {
                return;
            }

            JsonObject rootObject = root.getAsJsonObject();
            if (rootObject.has("selfTimezone")) {
                String configuredZone = rootObject.get("selfTimezone").getAsString();
                try {
                    ZoneId.of(configuredZone);
                    selfTimezoneId = configuredZone;
                } catch (ZoneRulesException ignored) {
                    selfTimezoneId = ZoneId.systemDefault().getId();
                }
            }

            JsonArray players = rootObject.getAsJsonArray("players");
            if (players == null) {
                return;
            }

            for (JsonElement element : players) {
                if (!element.isJsonObject()) {
                    continue;
                }

                JsonObject object = element.getAsJsonObject();
                String name = object.has("name") ? object.get("name").getAsString() : "";
                String timezoneId = object.has("timezone") ? object.get("timezone").getAsString() : "";
                if (!isValidPlayerName(name)) {
                    continue;
                }

                try {
                    ZoneId.of(timezoneId);
                } catch (ZoneRulesException exception) {
                    continue;
                }

                trackedPlayers.put(normalize(name), new TrackedPlayer(name, timezoneId));
            }

            JsonArray favorites = rootObject.getAsJsonArray("favorites");
            if (favorites != null) {
                for (JsonElement element : favorites) {
                    if (!element.isJsonPrimitive()) {
                        continue;
                    }

                    String name = element.getAsString();
                    if (isValidPlayerName(name) && trackedPlayers.containsKey(normalize(name))) {
                        favoritePlayers.add(normalize(name));
                    }
                }
            }

            favoritePlayers.retainAll(trackedPlayers.keySet());
        } catch (IOException ignored) {
        }
    }

    public synchronized void save() {
        try {
            Files.createDirectories(configPath.getParent());

            JsonArray players = new JsonArray();
            for (TrackedPlayer player : trackedPlayers()) {
                JsonObject object = new JsonObject();
                object.addProperty("name", player.name());
                object.addProperty("timezone", player.timezoneId());
                players.add(object);
            }

            JsonObject root = new JsonObject();
            root.addProperty("selfTimezone", selfTimezoneId);
            root.add("players", players);

            JsonArray favorites = new JsonArray();
            for (TrackedPlayer player : trackedPlayers()) {
                if (favoritePlayers.contains(normalize(player.name()))) {
                    favorites.add(player.name());
                }
            }
            root.add("favorites", favorites);

            try (Writer writer = Files.newBufferedWriter(configPath)) {
                writer.write(root.toString());
            }
        } catch (IOException ignored) {
        }
    }

    public static String formatTime(ZonedDateTime time) {
        return "%02d:%02d:%02d".formatted(time.getHour(), time.getMinute(), time.getSecond());
    }

    public static String formatDate(ZonedDateTime time) {
        return time.getDayOfWeek().name().substring(0, 1)
            + time.getDayOfWeek().name().substring(1).toLowerCase(Locale.ROOT)
            + " "
            + time.getMonth().name().substring(0, 1)
            + time.getMonth().name().substring(1).toLowerCase(Locale.ROOT)
            + " "
            + time.getDayOfMonth();
    }

    public static String formatOffset(ZonedDateTime time) {
        int seconds = time.getOffset().getTotalSeconds();
        int hours = seconds / 3600;
        int minutes = Math.abs((seconds / 60) % 60);
        return "UTC%+d:%02d".formatted(hours, minutes);
    }

    public static String timezoneLabel(String timezoneId) {
        int slash = timezoneId.lastIndexOf('/');
        String display = slash >= 0 ? timezoneId.substring(slash + 1) : timezoneId;
        return display.replace('_', ' ');
    }

    public static ZoneId resolveTimezone(String input) {
        String trimmed = input == null ? "" : input.trim();
        if (trimmed.isEmpty()) {
            throw new ZoneRulesException("Empty timezone");
        }

        if (trimmed.matches("[+-]\\d{1,2}")) {
            int hours = Integer.parseInt(trimmed);
            if (hours < -12 || hours > 14) {
                throw new ZoneRulesException("UTC offsets run from -12 to +14");
            }
            return ZoneOffset.ofHours(hours);
        }

        String shorthand = TIMEZONE_SHORTHANDS.get(trimmed.toUpperCase(Locale.ROOT));
        if (shorthand != null) {
            return ZoneId.of(shorthand);
        }

        return ZoneId.of(trimmed);
    }

    public ZoneId resolvePlaceToTimezone(String placeName) {
        String normalized = placeName.trim().toLowerCase(Locale.ROOT);
        if (normalized.isEmpty()) {
            throw new ZoneRulesException("Empty place name");
        }

        String cached = geocodeCache.get(normalized);
        if (cached != null) {
            return ZoneId.of(cached);
        }

        String bundled = BUNDLED_PLACE_TIMEZONES.get(normalized);
        if (bundled != null) {
            geocodeCache.put(normalized, bundled);
            saveCache();
            return ZoneId.of(bundled);
        }

        try {
            String encoded = URLEncoder.encode(normalized, StandardCharsets.UTF_8);
            HttpRequest request = HttpRequest.newBuilder(
                URI.create("https://geocoding-api.open-meteo.com/v1/search?name=" + encoded + "&count=1&language=en&format=json")
            ).header("User-Agent", "ptime/2.0").timeout(Duration.ofSeconds(10)).build();

            HttpResponse<String> response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new ZoneRulesException("Geocoding API returned " + response.statusCode());
            }

            JsonObject root = JsonParser.parseString(response.body()).getAsJsonObject();
            if (!root.has("results")) {
                throw new ZoneRulesException("No results for \"" + placeName + "\"");
            }

            JsonArray results = root.getAsJsonArray("results");
            if (results.isEmpty()) {
                throw new ZoneRulesException("No results for \"" + placeName + "\"");
            }

            String timezoneId = results.get(0).getAsJsonObject().get("timezone").getAsString();
            geocodeCache.put(normalized, timezoneId);
            saveCache();
            return ZoneId.of(timezoneId);
        } catch (IOException exception) {
            throw new ZoneRulesException("Geocoding lookup failed: " + exception.getMessage());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new ZoneRulesException("Geocoding lookup was interrupted");
        }
    }

    public static Component timezoneHelpText() {
        return Component.literal(
            "ptime timezone input supports full IDs like America/New_York, short forms like EST/CST/MST/PST, and UTC offsets like +2 or -5."
        );
    }

    private static Map<String, String> loadBundledPlaceTimezones() {
        Map<String, String> map = new LinkedHashMap<>();
        try (InputStream stream = PtimeManager.class.getResourceAsStream("/data/ptime/place_timezones.json")) {
            if (stream != null) {
                try (Reader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
                    JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();
                    for (Map.Entry<String, JsonElement> entry : root.entrySet()) {
                        map.put(entry.getKey(), entry.getValue().getAsString());
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return Collections.unmodifiableMap(map);
    }

    private static String normalize(String playerName) {
        return playerName.toLowerCase(Locale.ROOT);
    }

    private static Map<String, String> createTimezoneShorthands() {
        Map<String, String> shorthands = new LinkedHashMap<>();
        shorthands.put("EST", "America/New_York");
        shorthands.put("EDT", "America/New_York");
        shorthands.put("CST", "America/Chicago");
        shorthands.put("CDT", "America/Chicago");
        shorthands.put("MST", "America/Denver");
        shorthands.put("MDT", "America/Denver");
        shorthands.put("PST", "America/Los_Angeles");
        shorthands.put("PDT", "America/Los_Angeles");
        shorthands.put("AKST", "America/Anchorage");
        shorthands.put("AKDT", "America/Anchorage");
        shorthands.put("HST", "Pacific/Honolulu");
        shorthands.put("UTC", "UTC");
        shorthands.put("GMT", "UTC");
        return Collections.unmodifiableMap(shorthands);
    }

    public record TrackedPlayer(String name, String timezoneId) {
        public ZoneId zoneId() {
            return ZoneId.of(timezoneId);
        }
    }

    public record DownloadedSkin(Identifier textureId) {
    }

    public record CachedSkinUrl(String url, Instant cachedAt) {
    }

    public record SkinRenderData(PlayerSkin onlineSkin, Identifier downloadedTexture) {
        public static SkinRenderData online(PlayerSkin skin) {
            return new SkinRenderData(skin, null);
        }

        public static SkinRenderData downloaded(Identifier textureId) {
            return new SkinRenderData(null, textureId);
        }
    }
}
