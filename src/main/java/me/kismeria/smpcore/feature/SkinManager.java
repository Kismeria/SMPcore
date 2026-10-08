package me.kismeria.smpcore.feature;

import me.kismeria.smpcore.Lang;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import com.destroystokyo.paper.profile.PlayerProfile;
import com.destroystokyo.paper.profile.ProfileProperty;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import me.kismeria.smpcore.SmpCore;
import me.kismeria.smpcore.util.Bedrock;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.event.player.PlayerJoinEvent;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Locale;

/**
 * Скины на пиратском сервере (online-mode=false). При входе ищем скин по нику:
 * Mojang (лицензия) → Ely.by → TLauncher → LittleSkin (как CustomSkinLoader).
 * Клиент принимает только скины, подписанные Mojang, поэтому картинки со сторонних сайтов
 * прогоняются через MineSkin — он отдаёт подписанный скин, который видят все: Java и Bedrock (Geyser).
 */
public final class SkinManager implements Listener {

    private static final String AGENT = "SmpCore";

    private record Texture(String value, String signature, String source) {
    }

    private final SmpCore plugin;
    private final File file;
    private final YamlConfiguration cache;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5)).followRedirects(HttpClient.Redirect.NORMAL).build();

    public SkinManager(SmpCore plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "skins.yml");
        this.cache = YamlConfiguration.loadConfiguration(file);
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onPreLogin(AsyncPlayerPreLoginEvent event) {
        if (event.getLoginResult() != AsyncPlayerPreLoginEvent.Result.ALLOWED || !plugin.flag("skins.enabled")) {
            return;
        }
        PlayerProfile profile = event.getPlayerProfile();
        // лицензия при online-mode=true — скин уже есть; Bedrock — скин ставит Floodgate
        if (profile.hasTextures() || Bedrock.is(event.getUniqueId())) {
            return;
        }
        Texture texture = resolve(event.getName());
        if (texture == null) {
            return;
        }
        profile.setProperty(new ProfileProperty("textures", texture.value(), texture.signature()));
        event.setPlayerProfile(profile);
    }

    // ---------------------------------------------------------------- обновление админом

    /**
     * Downloads the skins again (bypassing the cache) and applies them at once, no relog:
     * Paper's setPlayerProfile re-sends the player to everyone, the player included.
     * Bedrock skins come from the GeyserMC global API. Runs one by one to stay under API limits.
     */
    public void refresh(Collection<? extends Player> targets, CommandSender reporter) {
        List<Player> list = new ArrayList<>(targets);
        long bedrockCount = list.stream().filter(p -> Bedrock.is(p.getUniqueId())).count();
        plugin.lang().send(reporter, "skin.started", Lang.ph("count", list.size()), Lang.ph("bedrock", bedrockCount));
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            int[] result = new int[2];
            for (Player p : list) {
                Texture texture = Bedrock.is(p.getUniqueId()) ? bedrock(p) : resolve(p.getName(), true);
                if (texture == null) {
                    result[1]++;
                } else {
                    result[0]++;
                    Bukkit.getScheduler().runTask(plugin, () -> apply(p, texture));
                }
                try {
                    Thread.sleep(400);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            Bukkit.getScheduler().runTask(plugin, () -> plugin.lang().send(reporter, "skin.done",
                    Lang.ph("ok", result[0]), Lang.ph("failed", result[1])));
        });
    }

    private static void apply(Player player, Texture texture) {
        if (!player.isOnline()) {
            return;
        }
        PlayerProfile profile = player.getPlayerProfile();
        for (ProfileProperty property : profile.getProperties()) {
            if (property.getName().equals("textures") && property.getValue().equals(texture.value())) {
                return; // same skin: skip the visible re-send
            }
        }
        profile.removeProperty("textures");
        profile.setProperty(new ProfileProperty("textures", texture.value(), texture.signature()));
        player.setPlayerProfile(profile);
    }

    // ---------------------------------------------------------------- Bedrock

    /**
     * Floodgate is meant to give Java players the Bedrock skin, but on Paper 26.x it does not reach
     * the profile. Geyser uploads every Bedrock skin to the GeyserMC global API, which returns it
     * signed by Mojang; we apply it like a Java skin. Fresh skins take a while to be converted, so retry.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        if (plugin.flag("skins.enabled") && Bedrock.is(player.getUniqueId())) {
            bedrockLater(player, 0, 40L);
        }
    }

    private static final int BEDROCK_ATTEMPTS = 10;

    private void bedrockLater(Player player, int attempt, long delay) {
        Bukkit.getScheduler().runTaskLaterAsynchronously(plugin, () -> {
            if (!player.isOnline()) {
                return;
            }
            Texture texture = bedrock(player);
            if (texture != null) {
                Bukkit.getScheduler().runTask(plugin, () -> apply(player, texture));
            } else if (attempt + 1 < BEDROCK_ATTEMPTS) {
                bedrockLater(player, attempt + 1, 600L);
            }
        }, delay);
    }

    private Texture bedrock(Player player) {
        String xuid = Bedrock.xuid(player.getUniqueId());
        if (xuid == null) {
            return null;
        }
        try {
            JsonObject skin = getJson("https://api.geysermc.org/v2/skin/" + xuid);
            if (skin == null || !skin.has("value") || !skin.has("signature")) {
                return null;
            }
            String value = skin.get("value").getAsString();
            String signature = skin.get("signature").getAsString();
            return value.isBlank() || signature.isBlank() ? null : new Texture(value, signature, "GeyserMC");
        } catch (IOException | RuntimeException e) {
            plugin.getLogger().fine("Bedrock-скин " + player.getName() + ": " + e.getMessage());
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }

    // ---------------------------------------------------------------- поиск

    private Texture resolve(String name) {
        return resolve(name, false);
    }

    /** @param force ignore the cache age (admin refresh) */
    private Texture resolve(String name, boolean force) {
        String key = name.toLowerCase(Locale.ROOT);
        long now = System.currentTimeMillis();
        long ttl = Math.max(1, plugin.getConfig().getInt("skins.cache-hours", 6)) * 3_600_000L;
        Texture cached = cached(key);
        long at;
        synchronized (cache) {
            at = cache.getLong("names." + key + ".at", 0);
        }
        if (!force && now - at < ttl) {
            return cached;
        }
        Texture found = null;
        for (String source : plugin.getConfig().getStringList("skins.sources")) {
            try {
                found = switch (source.toLowerCase(Locale.ROOT)) {
                    case "mojang" -> mojang(name);
                    case "elyby", "ely.by" -> elyBy(name);
                    case "tlauncher" -> tlauncher(name);
                    case "littleskin" -> littleSkin(name);
                    default -> null;
                };
            } catch (IOException | RuntimeException e) {
                plugin.getLogger().fine("Скин " + name + " из " + source + ": " + e.getMessage());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return cached;
            }
            if (found != null) {
                break;
            }
        }
        synchronized (cache) {
            cache.set("names." + key + ".at", now);
            if (found != null) {
                cache.set("names." + key + ".value", found.value());
                cache.set("names." + key + ".signature", found.signature());
                cache.set("names." + key + ".source", found.source());
                plugin.getLogger().info("Скин " + name + " — " + found.source());
            } else if (cached == null) {
                cache.set("names." + key + ".value", null);
            }
            save();
        }
        // ничего не нашли (или сервис лёг) — оставляем старый скин
        return found != null ? found : cached;
    }

    private Texture cached(String key) {
        synchronized (cache) {
            String value = cache.getString("names." + key + ".value");
            String signature = cache.getString("names." + key + ".signature");
            return value != null && signature != null ? new Texture(value, signature, cache.getString("names." + key + ".source", "cache")) : null;
        }
    }

    private Texture mojang(String name) throws IOException, InterruptedException {
        JsonObject user = getJson("https://api.mojang.com/users/profiles/minecraft/" + encode(name));
        if (user == null || !user.has("id")) {
            return null;
        }
        JsonObject profile = getJson("https://sessionserver.mojang.com/session/minecraft/profile/"
                + user.get("id").getAsString() + "?unsigned=false");
        if (profile == null) {
            return null;
        }
        for (JsonElement element : profile.getAsJsonArray("properties")) {
            JsonObject property = element.getAsJsonObject();
            if (property.get("name").getAsString().equals("textures") && property.has("signature")) {
                return new Texture(property.get("value").getAsString(), property.get("signature").getAsString(), "Mojang");
            }
        }
        return null;
    }

    private Texture elyBy(String name) throws IOException, InterruptedException {
        JsonObject profile = getJson("https://skinsystem.ely.by/profile/" + encode(name));
        if (profile == null || !profile.has("properties")) {
            return null;
        }
        for (JsonElement element : profile.getAsJsonArray("properties")) {
            JsonObject property = element.getAsJsonObject();
            if (!property.get("name").getAsString().equals("textures")) {
                continue;
            }
            JsonObject textures = JsonParser.parseString(new String(Base64.getDecoder().decode(
                    property.get("value").getAsString()), StandardCharsets.UTF_8)).getAsJsonObject().getAsJsonObject("textures");
            return fromTextures(textures, "Ely.by");
        }
        return null;
    }

    private Texture tlauncher(String name) throws IOException, InterruptedException {
        JsonObject textures = getJson("https://auth.tlauncher.org/skin/profile/texture/login/" + encode(name));
        return fromTextures(textures, "TLauncher");
    }

    private Texture littleSkin(String name) throws IOException, InterruptedException {
        JsonObject csl = getJson("https://littleskin.cn/csl/" + encode(name) + ".json");
        if (csl == null) {
            return null;
        }
        String hash = null;
        boolean slim = false;
        if (csl.has("skins") && csl.get("skins").isJsonObject()) {
            JsonObject skins = csl.getAsJsonObject("skins");
            // у пустых аккаунтов там null
            if (skins.has("slim") && !skins.get("slim").isJsonNull()) {
                hash = skins.get("slim").getAsString();
                slim = true;
            } else if (skins.has("default") && !skins.get("default").isJsonNull()) {
                hash = skins.get("default").getAsString();
            }
        } else if (csl.has("skin") && !csl.get("skin").isJsonNull()) {
            hash = csl.get("skin").getAsString();
        }
        return hash == null ? null : mineSkin("https://littleskin.cn/textures/" + hash, slim, "LittleSkin");
    }

    /** {"SKIN":{"url":"...","metadata":{"model":"slim"}}} → подписанный скин через MineSkin. */
    private Texture fromTextures(JsonObject textures, String source) throws IOException, InterruptedException {
        if (textures == null || !textures.has("SKIN")) {
            return null;
        }
        JsonObject skin = textures.getAsJsonObject("SKIN");
        if (!skin.has("url")) {
            return null;
        }
        boolean slim = skin.has("metadata") && skin.getAsJsonObject("metadata").has("model")
                && skin.getAsJsonObject("metadata").get("model").getAsString().equalsIgnoreCase("slim");
        return mineSkin(skin.get("url").getAsString(), slim, source);
    }

    // ---------------------------------------------------------------- MineSkin

    private Texture mineSkin(String url, boolean slim, String source) throws IOException, InterruptedException {
        String urlKey = "urls." + sha1(url + (slim ? "#slim" : ""));
        synchronized (cache) {
            String value = cache.getString(urlKey + ".value");
            String signature = cache.getString(urlKey + ".signature");
            if (value != null && signature != null) {
                return new Texture(value, signature, source);
            }
        }
        // MineSkin не всегда может скачать картинку сам (Ely.by отдаёт ему ошибку) — качаем и загружаем файлом
        byte[] png = download(url);
        if (png == null) {
            return null;
        }
        String boundary = "smpcore" + Long.toHexString(System.nanoTime());
        java.io.ByteArrayOutputStream body = new java.io.ByteArrayOutputStream();
        writePart(body, boundary, "variant", null, (slim ? "slim" : "classic").getBytes(StandardCharsets.UTF_8));
        writePart(body, boundary, "visibility", null, "unlisted".getBytes(StandardCharsets.UTF_8));
        writePart(body, boundary, "file", "skin.png", png);
        body.writeBytes(("--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("https://api.mineskin.org/v2/queue"))
                .timeout(Duration.ofSeconds(15))
                .header("User-Agent", AGENT)
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body.toByteArray()));
        authorize(request);
        JsonObject response = parse(http.send(request.build(), HttpResponse.BodyHandlers.ofString()));
        long deadline = System.currentTimeMillis() + Math.max(5, plugin.getConfig().getInt("skins.timeout-seconds", 15)) * 1000L;
        while (response != null) {
            JsonObject texture = textureData(response);
            if (texture != null) {
                Texture result = new Texture(texture.get("value").getAsString(), texture.get("signature").getAsString(), source);
                synchronized (cache) {
                    cache.set(urlKey + ".value", result.value());
                    cache.set(urlKey + ".signature", result.signature());
                }
                return result;
            }
            JsonObject job = response.has("job") ? response.getAsJsonObject("job") : null;
            if (job == null || "failed".equals(job.get("status").getAsString()) || System.currentTimeMillis() > deadline) {
                return null;
            }
            Thread.sleep(1000);
            HttpRequest.Builder poll = HttpRequest.newBuilder(URI.create("https://api.mineskin.org/v2/queue/" + job.get("id").getAsString()))
                    .timeout(Duration.ofSeconds(10)).header("User-Agent", AGENT).GET();
            authorize(poll);
            response = parse(http.send(poll.build(), HttpResponse.BodyHandlers.ofString()));
        }
        return null;
    }

    /** PNG скина (не больше 256 КБ), иначе null. */
    private byte[] download(String url) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(8))
                .header("User-Agent", AGENT).GET().build();
        HttpResponse<byte[]> response = http.send(request, HttpResponse.BodyHandlers.ofByteArray());
        byte[] png = response.body();
        boolean isPng = png != null && png.length > 8 && png.length < 256 * 1024
                && png[0] == (byte) 0x89 && png[1] == 'P' && png[2] == 'N' && png[3] == 'G';
        return response.statusCode() == 200 && isPng ? png : null;
    }

    private static void writePart(java.io.ByteArrayOutputStream out, String boundary, String name, String filename, byte[] data) {
        StringBuilder head = new StringBuilder("--").append(boundary).append("\r\n")
                .append("Content-Disposition: form-data; name=\"").append(name).append('"');
        if (filename != null) {
            head.append("; filename=\"").append(filename).append("\"\r\nContent-Type: image/png");
        }
        head.append("\r\n\r\n");
        out.writeBytes(head.toString().getBytes(StandardCharsets.UTF_8));
        out.writeBytes(data);
        out.writeBytes("\r\n".getBytes(StandardCharsets.UTF_8));
    }

    private void authorize(HttpRequest.Builder request) {
        String key = plugin.getConfig().getString("skins.mineskin-key", "");
        if (key != null && !key.isBlank()) {
            request.header("Authorization", "Bearer " + key);
        }
    }

    private static JsonObject textureData(JsonObject response) {
        if (!response.has("skin") || !response.get("skin").isJsonObject()) {
            return null;
        }
        JsonObject skin = response.getAsJsonObject("skin");
        if (!skin.has("texture")) {
            return null;
        }
        JsonObject data = skin.getAsJsonObject("texture").getAsJsonObject("data");
        return data != null && data.has("value") && data.has("signature") ? data : null;
    }

    // ---------------------------------------------------------------- HTTP

    private JsonObject getJson(String url) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(6))
                .header("User-Agent", AGENT).GET().build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        return response.statusCode() == 200 ? parse(response) : null;
    }

    private static JsonObject parse(HttpResponse<String> response) {
        String body = response.body();
        if (body == null || body.isBlank()) {
            return null;
        }
        JsonElement element = JsonParser.parseString(body);
        if (element.isJsonObject()) {
            JsonObject object = element.getAsJsonObject();
            return object.isEmpty() ? null : object;
        }
        if (element.isJsonArray()) {
            JsonArray array = element.getAsJsonArray();
            return !array.isEmpty() && array.get(0).isJsonObject() ? array.get(0).getAsJsonObject() : null;
        }
        return null;
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static String sha1(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            return Integer.toHexString(value.hashCode());
        }
    }

    private void save() {
        try {
            cache.save(file);
        } catch (IOException e) {
            plugin.getLogger().warning("Не удалось сохранить skins.yml: " + e.getMessage());
        }
    }
}
