package cn.ykn.dialog;

import org.bukkit.Server;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerResourcePackStatusEvent;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.zip.ZipFile;
import java.util.zip.ZipEntry;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

final class DialogResourcePack implements Listener {
    private record PackRevision(FileTime modified, long bytes, UUID packId, Set<String> textures) {}

    private final Path archive;
    private final Map<UUID, PackRevision> loaded = new HashMap<>();
    private PackRevision cached;

    DialogResourcePack(Path archive) {
        this.archive = archive;
    }

    static DialogResourcePack forCraftEngine(Server server) {
        var engine = server.getPluginManager().getPlugin("CraftEngine");
        if (engine == null || !engine.isEnabled()) return new DialogResourcePack(null);
        Path folder = engine.getDataFolder().toPath();
        var config = YamlConfiguration.loadConfiguration(folder.resolve("config.yml").toFile());
        String pack = config.getString("resource-pack.delivery.file-to-upload", "generated/resource_pack.zip");
        return new DialogResourcePack(folder.resolve(pack));
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        var player = event.getPlayer();
        joined(player.getUniqueId(), player.hasResourcePack());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        loaded.remove(event.getPlayer().getUniqueId());
    }

    @EventHandler
    public void onResourcePackStatus(PlayerResourcePackStatusEvent event) {
        status(event.getPlayer().getUniqueId(), event.getID(), event.getStatus());
    }

    void joined(UUID player, boolean loadedAtLogin) {
        PackRevision revision = loadedAtLogin ? currentRevision() : null;
        if (revision != null && revision.packId() != null) loaded.put(player, revision);
        else loaded.remove(player);
    }

    void status(UUID player, UUID packId, PlayerResourcePackStatusEvent.Status status) {
        if (packId == null) return;
        if (status == PlayerResourcePackStatusEvent.Status.SUCCESSFULLY_LOADED) {
            PackRevision revision = currentRevision();
            if (revision != null && packId.equals(revision.packId())) loaded.put(player, revision);
        } else if (status != PlayerResourcePackStatusEvent.Status.ACCEPTED
                && status != PlayerResourcePackStatusEvent.Status.DOWNLOADED) {
            PackRevision previous = loaded.get(player);
            if (previous != null && packId.equals(previous.packId())) loaded.remove(player);
        }
    }

    Set<String> loadedFiles(UUID player) {
        PackRevision previous = loaded.get(player);
        return previous != null && previous == currentRevision() ? previous.textures() : Set.of();
    }

    private PackRevision currentRevision() {
        if (archive == null) return null;
        try {
            var attributes = Files.readAttributes(archive, BasicFileAttributes.class);
            var changed = attributes.lastModifiedTime();
            long size = attributes.size();
            if (cached != null && cached.modified().equals(changed) && cached.bytes() == size) return cached;
            try (var zip = new ZipFile(archive.toFile())) {
                Set<String> textures = zip.stream().map(ZipEntry::getName)
                        .filter(name -> name.startsWith("assets/") && (name.endsWith(".png") || name.endsWith(".json")))
                        .collect(Collectors.toUnmodifiableSet());
                return cached = new PackRevision(changed, size, selfHostedPackId(archive), textures);
            }
        } catch (IOException ignored) {
            return null;
        }
    }

    static UUID selfHostedPackId(Path archive) throws IOException {
        // CraftEngine's self host derives its advertised pack ID from the lowercase SHA-1.
        try (var input = Files.newInputStream(archive)) {
            var sha = MessageDigest.getInstance("SHA-1");
            byte[] buffer = new byte[8192];
            for (int count; (count = input.read(buffer)) != -1;) sha.update(buffer, 0, count);
            String hash = HexFormat.of().formatHex(sha.digest());
            return UUID.nameUUIDFromBytes(hash.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }
}

