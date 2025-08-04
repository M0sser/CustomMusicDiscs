package me.xai.custommusicdiscs;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import org.bukkit.*;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;
import org.schabi.newpipe.extractor.NewPipe;
import org.schabi.newpipe.extractor.StreamingService;
import org.schabi.newpipe.extractor.ServiceList;
import org.schabi.newpipe.extractor.downloader.Downloader;
import org.schabi.newpipe.extractor.downloader.Request;
import org.schabi.newpipe.extractor.downloader.Response;
import org.schabi.newpipe.extractor.exceptions.ContentNotAvailableException;
import org.schabi.newpipe.extractor.localization.Localization;
import org.schabi.newpipe.extractor.services.youtube.YoutubeService;
import org.schabi.newpipe.extractor.stream.AudioStream;
import org.schabi.newpipe.extractor.stream.StreamExtractor;

import org.json.JSONArray;
import org.json.JSONObject;
import ws.schild.jave.Encoder;
import ws.schild.jave.MultimediaObject;
import ws.schild.jave.encode.AudioAttributes;
import ws.schild.jave.encode.EncodingAttributes;


import java.io.*;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.logging.Level;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public class CustomMusicDiscs extends JavaPlugin implements Listener, TabExecutor {

    /* ------------------------------------------------------------------ */
    /* CONSTANTS & FIELDS                                                 */
    /* ------------------------------------------------------------------ */

	    private NamespacedKey KEY_ID;
	    private NamespacedKey KEY_PACK;

	    private int    limitedMax;
	    private int    reloadCooldownSeconds;
	    private String serverIp;

	    private final Map<String,Integer> limitedSongs   = new HashMap<>();
	    private final Map<String,Path>    limitedPaths   = new HashMap<>();
	    private final LinkedList<String>  limitedQueue   = new LinkedList<>();
	    private final Map<String,Integer> permanentSongs = new HashMap<>();
	    private final Map<String,Path>    permanentPaths = new HashMap<>();

	    private Path soundsDirLimited;
	    private Path soundsDirPermanent;
	    private Path packDirLimited;
	    private Path packDirPermanent;
	    private Path zipLimited;
	    private Path zipPermanent;

	    private HttpServer httpServer;
	    private String  packUrlLimited;
	    private byte[]  packHashLimited;
	    private String  packUrlPermanent;
	    private byte[]  packHashPermanent;

	    private long lastReloadLimited;
	    private long lastReloadPermanent;

            private static boolean NEWPIPE_READY = false;

            private static final int HTTP_PORT = 5523;

            /* --- NEW ---------------------------------------------------------------- */
            private final ExecutorService downloadPool = Executors.newFixedThreadPool(3);
            private static final Pattern YT_URL = Pattern.compile(
                    "^(?:https?://)?(?:www\\.)?(?:youtube\\.com/watch\\?v=|youtu\\.be/)[\\w-]{11}.*$",
                    Pattern.CASE_INSENSITIVE);
            private static final Pattern YT_ID_PATTERN = Pattern.compile("(?<=v=)[^&]+|(?<=be/)[^?&]+", Pattern.CASE_INSENSITIVE);
            private static final String[] PIPED_BASES = {
                    "https://pipedapi.kavin.rocks",

                    "https://pipedapi.r001.workers.dev",
                    "https://pipedapi.adminforge.de"
                    "https://piped.video/api/v1"
            };

	    /* ------------------------------------------------------------------ */
	    /* LIFECYCLE                                                          */
	    /* ------------------------------------------------------------------ */

	    @Override
	    public void onEnable() {
	        saveDefaultConfig();
	        loadConfigValues();

	        KEY_ID   = new NamespacedKey(this, "cmd_id");
	        KEY_PACK = new NamespacedKey(this, "cmd_pack");

	        initNewPipe();
	        initFolders();
	        initHttpServer();
	        registerCommandsAndEvents();

	        if (!Files.exists(zipLimited))   buildResourcePack(true);
	        if (!Files.exists(zipPermanent)) buildResourcePack(false);

	        getLogger().info("CustomMusicDiscs enabled");
	    }

	    @Override
	    public void onDisable() {
	        saveConfigValues();
	        if (httpServer != null) httpServer.stop(0);
	        downloadPool.shutdownNow(); // graceful cancellation
	    }

	    /* ------------------------------------------------------------------ */
	    /* CONFIG I/O                                                         */
	    /* ------------------------------------------------------------------ */

	    private void loadConfigValues() {
	        FileConfiguration cfg = getConfig();
	        limitedMax            = cfg.getInt("limited.max", 10);
	        reloadCooldownSeconds = cfg.getInt("reloadCooldown", 30);
	        serverIp              = cfg.getString("serverIp", "127.0.0.1");
	    }

	    private void saveConfigValues() {
	        FileConfiguration cfg = getConfig();
	        cfg.set("limited.max", limitedMax);
	        cfg.set("reloadCooldown", reloadCooldownSeconds);
	        cfg.set("serverIp", serverIp);
	        saveConfig();
	    }

	    /* ------------------------------------------------------------------ */
	    /* INITIALISATION HELPERS                                             */
	    /* ------------------------------------------------------------------ */

	    private void initFolders() {
	        soundsDirLimited   = getDataFolder().toPath().resolve("sounds/limited");
	        soundsDirPermanent = getDataFolder().toPath().resolve("sounds/permanent");
	        packDirLimited     = getDataFolder().toPath().resolve("pack_limited");
	        packDirPermanent   = getDataFolder().toPath().resolve("pack_permanent");
	        zipLimited         = getDataFolder().toPath().resolve("limited.zip");
	        zipPermanent       = getDataFolder().toPath().resolve("permanent.zip");
	        try {
	            Files.createDirectories(soundsDirLimited);
	            Files.createDirectories(soundsDirPermanent);
	            Files.createDirectories(packDirLimited);
	            Files.createDirectories(packDirPermanent);
	        } catch (IOException ex) {
	            getLogger().log(Level.SEVERE, "Creating directories", ex);
	        }
	    }


            private void initHttpServer() {
                try {
                    httpServer = HttpServer.create(new InetSocketAddress(HTTP_PORT), 0);
                    httpServer.createContext("/limited",   new PackHandler(zipLimited));
                    httpServer.createContext("/permanent", new PackHandler(zipPermanent));
                    httpServer.setExecutor(Executors.newCachedThreadPool());
                    httpServer.start();
                    packUrlLimited   = "http://" + serverIp + ":" + HTTP_PORT + "/limited";
                    packUrlPermanent = "http://" + serverIp + ":" + HTTP_PORT + "/permanent";
                    getLogger().info("HTTP server started on port " + HTTP_PORT);
                } catch (IOException ex) {
                    getLogger().log(Level.SEVERE, "HTTP server", ex);
                }
            }

	    private void registerCommandsAndEvents() {
	        getServer().getPluginManager().registerEvents(this, this);
	        Objects.requireNonNull(getCommand("rewrite"))      .setExecutor(this);
	        Objects.requireNonNull(getCommand("rewriteperm"))  .setExecutor(this);
	        Objects.requireNonNull(getCommand("reloadlimited")).setExecutor(this);
	        Objects.requireNonNull(getCommand("reloadpermanent")).setExecutor(this);
	    }

	    private void initNewPipe() {
	        if (NEWPIPE_READY) return;
	        Downloader dl = new JdkDownloader();
	        NewPipe.init(dl, new Localization("en", "US"));
	        NEWPIPE_READY = true;
	    }

	    /* ------------------------------------------------------------------ */
	    /* COMMANDS                                                           */
	    /* ------------------------------------------------------------------ */

	    @Override
	    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command cmd, @NotNull String label, @NotNull String[] args) {
	        switch (cmd.getName().toLowerCase(Locale.ROOT)) {
	            case "rewrite":        return rewriteCommand(sender, args, true);
	            case "rewriteperm":    return rewriteCommand(sender, args, false);
	            case "reloadlimited":  return reloadPackCommand(sender, true);
	            case "reloadpermanent":return reloadPackCommand(sender, false);
	            default: return false;
	        }
	    }

	    @Override
	    public @NotNull List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command cmd, @NotNull String alias, @NotNull String[] args) {
	        return Collections.emptyList();
	    }

	    /* ------------------------------------------------------------------ */
	    /* REWRITE COMMAND                                                     */
	    /* ------------------------------------------------------------------ */

	    private boolean rewriteCommand(CommandSender sender, String[] args, boolean limited) {
	        if (!(sender instanceof Player player)) {
	            sender.sendMessage(ChatColor.RED + "Only players can run this command");
	            return true;
	        }
	        if (args.length != 1) {
	            player.sendMessage(ChatColor.RED + "Usage: /" + (limited ? "rewrite" : "rewriteperm") + " <youtube_url>");
	            return true;
	        }

	        String url = args[0].trim();
	        if (!YT_URL.matcher(url).matches()) {
	            player.sendMessage(ChatColor.RED + "That doesn't look like a YouTube link.");
	            return true;
	        }

	        player.sendMessage(ChatColor.YELLOW + "Downloading & converting … this may take a moment.");

	        downloadPool.execute(() -> {
	            try {
	                Path oggDir = limited ? soundsDirLimited : soundsDirPermanent;
	                int  id     = (limited ? limitedSongs.size() : permanentSongs.size()) + 1;
	                Path oggFile = oggDir.resolve("song_" + id + ".ogg");

	                downloadAndConvert(url, oggFile);

	                // ---------------- bookkeeping ------------------
	                if (limited) {
	                    limitedSongs.put(url, id);
	                    limitedPaths.put(url, oggFile);
	                    limitedQueue.add(url);
	                    while (limitedQueue.size() > limitedMax) {
	                        String old = limitedQueue.removeFirst();
	                        Integer oldId = limitedSongs.remove(old);
	                        Path oldPath  = limitedPaths.remove(old);
	                        if (oldPath != null) try { Files.deleteIfExists(oldPath); } catch (IOException ignored) {}
	                        if (oldId != null) getLogger().info("Removed oldest temp song #" + oldId);
	                    }
	                } else {
	                    permanentSongs.put(url, id);
	                    permanentPaths.put(url, oggFile);
	                }

	                buildResourcePack(limited);

	                // ---------------- main‑thread work -------------
	                Bukkit.getScheduler().runTask(this, () -> {
	                    ItemStack disc = player.getInventory().getItemInMainHand();
	                    if (!disc.getType().name().startsWith("MUSIC_DISC")) {
	                        player.sendMessage(ChatColor.RED + "Hold a music disc in your main hand!");
	                        return;
	                    }
	                    ItemMeta meta = Objects.requireNonNull(disc.getItemMeta());
	                    PersistentDataContainer pdc = meta.getPersistentDataContainer();
	                    pdc.set(KEY_ID,   PersistentDataType.INTEGER, id);
	                    pdc.set(KEY_PACK, PersistentDataType.STRING,  limited ? "limited" : "permanent");
	                    meta.setDisplayName(ChatColor.AQUA + extractorFriendlyName(url));
	                    disc.setItemMeta(meta);

	                    sendPack(player, limited);
	                    player.sendMessage(ChatColor.GREEN + "Custom disc ready! Place it in a jukebox 🎵");
	                });

	            } catch (Exception ex) {
	                getLogger().log(Level.WARNING, "Rewrite error", ex);
	                Bukkit.getScheduler().runTask(this, () ->
	                        player.sendMessage(ChatColor.RED + "Failed: " + ex.getMessage()));
	            }
	        });
	        return true;
	    }

	    /* ------------------------------------------------------------------ */
	    /* RELOAD PACK COMMAND                                                 */
	    /* ------------------------------------------------------------------ */

	    private boolean reloadPackCommand(CommandSender sender, boolean limited) {
	        if (!(sender instanceof Player)) {
	            sender.sendMessage(ChatColor.RED + "Players only");
	            return true;
	        }
	        long now = Instant.now().getEpochSecond();
	        if (limited) {
	            if (now - lastReloadLimited < reloadCooldownSeconds) {
	                sender.sendMessage(ChatColor.RED + "Please wait a bit before reloading again");
	                return true;
	            }
	            lastReloadLimited = now;
	        } else {
	            if (now - lastReloadPermanent < reloadCooldownSeconds) {
	                sender.sendMessage(ChatColor.RED + "Please wait a bit before reloading again");
	                return true;
	            }
	            lastReloadPermanent = now;
	        }
	        sendPack((Player) sender, limited);
	        return true;
	    }

	    /* ------------------------------------------------------------------ */
	    /* RESOURCE PACK BUILDING                                              */
	    /* ------------------------------------------------------------------ */

	    private void buildResourcePack(boolean limited) {
	        Path packDir = limited ? packDirLimited : packDirPermanent;
	        Path zipFile = limited ? zipLimited     : zipPermanent;
	        Map<String,Integer> map     = limited ? limitedSongs   : permanentSongs;
	        Map<String,Path>    pathMap = limited ? limitedPaths   : permanentPaths;

	        try {
	            // sounds.json
	            Path soundsJson = packDir.resolve("assets/minecraft/sounds.json");
	            Files.createDirectories(soundsJson.getParent());
	            StringBuilder json = new StringBuilder("{\n");
	            boolean first = true;
	            for (int id : map.values()) {
	                if (!first) json.append(',').append('\n');
	                first = false;
	                json.append("  \"cmd.song_"+id+"\": { \"sounds\": [\"cmd/song_"+id+"\"] }");
	            }
	            json.append("\n}\n");
	            Files.writeString(soundsJson, json.toString(), StandardCharsets.UTF_8);

	            // copy oggs
	            for (Map.Entry<String,Integer> e : map.entrySet()) {
	                Path src = pathMap.get(e.getKey());
	                if (src == null) continue;
	                Path dst = packDir.resolve("assets/minecraft/sounds/cmd/song_"+e.getValue()+".ogg");
	                Files.createDirectories(dst.getParent());
	                Files.copy(src, dst, StandardCopyOption.REPLACE_EXISTING);
	            }

	            // zip
	            try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(zipFile))) {
	                Files.walk(packDir).filter(Files::isRegularFile).forEach(path -> {
	                    try {
	                        String entry = packDir.relativize(path).toString().replace(File.separatorChar, '/');
	                        zos.putNextEntry(new ZipEntry(entry));
	                        Files.copy(path, zos);
	                        zos.closeEntry();
	                    } catch (IOException ex) { getLogger().log(Level.SEVERE, "Zip add", ex); }
	                });
	            }

	            MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
	            byte[] hash = sha1.digest(Files.readAllBytes(zipFile));
	            if (limited) packHashLimited = hash; else packHashPermanent = hash;

	        } catch (IOException | NoSuchAlgorithmException ex) {
	            getLogger().log(Level.SEVERE, "Pack build", ex);
	        }
	    }

	    private void sendPack(Player p, boolean limited) {
	        String url  = limited ? packUrlLimited   : packUrlPermanent;
	        byte[] hash = limited ? packHashLimited : packHashPermanent;
	        p.setResourcePack(url, hash);
	        p.sendMessage(ChatColor.YELLOW + "Sent " + (limited ? "limited" : "permanent") + " resource pack.");
	    }

	    /* ------------------------------------------------------------------ */
	    /* JUKEBOX INTERCEPT                                                   */
	    /* ------------------------------------------------------------------ */

	    @EventHandler
	    public void onDiscUse(PlayerInteractEvent e) {
	        if (e.getAction() != Action.RIGHT_CLICK_BLOCK || e.getHand() != EquipmentSlot.HAND) return;
	        if (e.getClickedBlock() == null || e.getClickedBlock().getType() != Material.JUKEBOX) return;
	        ItemStack disc = e.getPlayer().getInventory().getItemInMainHand();
	        if (!disc.getType().name().startsWith("MUSIC_DISC")) return;
	        ItemMeta meta = disc.getItemMeta();
	        if (meta == null) return;
	        PersistentDataContainer pdc = meta.getPersistentDataContainer();
	        Integer id = pdc.get(KEY_ID, PersistentDataType.INTEGER);
	        if (id == null) return;
	        String sound = "cmd.song_" + id;
	        e.setCancelled(true);
	        e.getPlayer().playSound(e.getClickedBlock().getLocation(), sound, SoundCategory.RECORDS, 3f, 1f);
	    }

	    /* ------------------------------------------------------------------ */
	    /* DOWNLOAD + CONVERT                                                  */
	    /* ------------------------------------------------------------------ */

            private void downloadAndConvert(String youtubeUrl, Path targetOgg) throws Exception {
                String audioUrl = null;
                String suffix   = null;

                try {
                    StreamingService yt = NewPipe.getService(ServiceList.YouTube.getServiceId());
                    StreamExtractor extractor = yt.getStreamExtractor(youtubeUrl);
                    extractor.fetchPage();
                    List<AudioStream> audioStreams = extractor.getAudioStreams();
                    if (audioStreams.isEmpty()) throw new IllegalStateException("No audio streams found");
                    AudioStream best = audioStreams.stream()
                            .max(Comparator.comparingInt(AudioStream::getAverageBitrate))
                            .orElseThrow();
                    audioUrl = best.getUrl();
                    suffix   = best.getFormat().getSuffix();
                } catch (ContentNotAvailableException ex) {
                    getLogger().info("Falling back to Piped: " + ex.getMessage());
                    String id = extractYoutubeId(youtubeUrl);
                    IOException last = null;
                    for (String base : PIPED_BASES) {
                        try {

                            URL api = new URL(base + "/api/v1/streams/" + id);
                            HttpURLConnection conn = (HttpURLConnection) api.openConnection();
                            conn.setConnectTimeout(5000);
                            conn.setReadTimeout(5000);

                            conn.setRequestProperty("User-Agent", "Mozilla/5.0");
                            conn.setRequestProperty("Accept", "application/json");
                            int status = conn.getResponseCode();
                            InputStream resp = status == HttpURLConnection.HTTP_OK
                                    ? conn.getInputStream()
                                    : conn.getErrorStream();

                            String ctype = conn.getContentType();
                            String json = new String(resp.readAllBytes(), StandardCharsets.UTF_8);
                            if (status != HttpURLConnection.HTTP_OK)
                                throw new IOException("HTTP " + status + " from " + base + ": " + json);
                            if (ctype == null || !ctype.contains("application/json"))
                                throw new IOException("Unexpected response from " + base + ": " + json);
                            JSONArray arr = new JSONObject(json).getJSONArray("audioStreams");
                            if (arr.isEmpty()) throw new IOException("No audio streams via " + base);
                            JSONObject best = arr.getJSONObject(0);
                            for (int i = 1; i < arr.length(); i++) {
                                JSONObject s = arr.getJSONObject(i);
                                if (s.optInt("bitrate", 0) > best.optInt("bitrate", 0)) best = s;
                            }
                            audioUrl = best.getString("url");
                            suffix   = best.optString("format", best.optString("container", "m4a"));
                            last = null;
                            break;
                        } catch (IOException e) {
                            last = e;
                        }
                    }

                    if (audioUrl == null || suffix == null)
                        throw last != null ? last : new IOException("Piped fallback failed");
                }

                // download
                Path temp = Files.createTempFile("cmd_dl", "." + suffix);
                try (InputStream in = new URL(audioUrl).openStream();
                     OutputStream out = Files.newOutputStream(temp, StandardOpenOption.WRITE)) {
                    in.transferTo(out);
                }

                // convert via FFmpeg (JAVE2)
                AudioAttributes aa = new AudioAttributes();
                aa.setCodec("libvorbis");
                aa.setBitRate(160_000);
                aa.setChannels(2);
                aa.setSamplingRate(44_100);
                EncodingAttributes ea = new EncodingAttributes();
                ea.setOutputFormat("ogg");
                ea.setAudioAttributes(aa);
                new Encoder().encode(new MultimediaObject(temp.toFile()), targetOgg.toFile(), ea);
                Files.deleteIfExists(temp);
            }

            private String extractYoutubeId(String url) {
                var m = YT_ID_PATTERN.matcher(url);
                if (m.find()) return m.group();
                throw new IllegalArgumentException("Invalid YouTube URL");
            }

	    /* ------------------------------------------------------------------ */
	    /* MINI HTTP HANDLER                                                   */
	    /* ------------------------------------------------------------------ */

	    private static class PackHandler implements HttpHandler {
	        private final Path file;
	        PackHandler(Path f) { this.file = f; }
	        @Override public void handle(HttpExchange ex) throws IOException {
	            if (!Files.exists(file)) { ex.sendResponseHeaders(404, -1); return; }
	            Headers h = ex.getResponseHeaders();
	            h.add("Content-Type", "application/zip");
	            byte[] bytes = Files.readAllBytes(file);
	            ex.sendResponseHeaders(200, bytes.length);
	            try (OutputStream os = ex.getResponseBody()) { os.write(bytes); }
	        }
	    }

	    /* ------------------------------------------------------------------ */
	    /* DOWNLOADER IMPLEMENTATION (NewPipe needs one)                       */
	    /* ------------------------------------------------------------------ */

	    private static class JdkDownloader extends Downloader {
	        private static final int TIMEOUT = 30_000;
	        @Override public Response execute(Request req) throws IOException {
	            HttpURLConnection conn = (HttpURLConnection) new URL(req.url()).openConnection();
	            conn.setRequestMethod(req.httpMethod());
	            conn.setConnectTimeout(TIMEOUT);
	            conn.setReadTimeout(TIMEOUT);
	            conn.setInstanceFollowRedirects(true);
                    conn.setRequestProperty(
                            "User-Agent",
                            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
                            "AppleWebKit/537.36 (KHTML, like Gecko) " +
                            "Chrome/124.0.0.0 Safari/537.36");
                    conn.setRequestProperty("Accept-Language", "en-US,en;q=0.9");
	            req.headers().forEach((k,v)-> conn.setRequestProperty(k, String.join(";", v)));
                    byte[] send = req.dataToSend();
                    if (send != null) {
                        conn.setDoOutput(true);
                        try (OutputStream os = conn.getOutputStream()) {
                            os.write(send);
                        }
                    }

                    int code = conn.getResponseCode();
                    String message = conn.getResponseMessage();
                    InputStream is = code >= 400 ? conn.getErrorStream() : conn.getInputStream();
                    String body = "";
                    if (is != null) body = new String(is.readAllBytes(), StandardCharsets.UTF_8);
                    return new Response(code, message, conn.getHeaderFields(), body, conn.getURL().toString());

	             }
	        }
	    

	    /* ------------------------------------------------------------------ */
	    /* FRIENDLY NAME HELPER                                                */
	    /* ------------------------------------------------------------------ */

	    private String extractorFriendlyName(String url) {
	        try {
                    StreamingService yt = NewPipe.getService(ServiceList.YouTube.getServiceId());
	            StreamExtractor se  = yt.getStreamExtractor(url);
	            se.fetchPage();
	            return se.getName();
	        } catch (Exception ignored) { }
	        return "Custom Track";
	    }
	}
