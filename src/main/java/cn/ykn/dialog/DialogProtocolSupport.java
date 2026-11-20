package cn.ykn.dialog;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

/**
 * Resolves each player's Minecraft protocol version using ViaVersion when present,
 * falling back to Paper's native {@link Player#getProtocolVersion()}.
 */
public final class DialogProtocolSupport {
    /** Minecraft 1.21.6+ protocol version where Paper Dialog UI is supported. */
    public static final int PROTO_DIALOG = 771;

    private final Map<UUID, Integer> cachedProtocols = new ConcurrentHashMap<>();
    private final Object viaApi;
    private final Method viaGetPlayerVersion;

    public DialogProtocolSupport(Logger logger) {
        Object api = null;
        Method versionMethod = null;
        try {
            if (Bukkit.getPluginManager().isPluginEnabled("ViaVersion")) {
                api = Class.forName("com.viaversion.viaversion.api.Via")
                        .getMethod("getAPI")
                        .invoke(null);
                versionMethod = api.getClass().getMethod("getPlayerVersion", UUID.class);
                versionMethod.setAccessible(true);
            }
        } catch (ReflectiveOperationException | RuntimeException e) {
            if (logger != null) {
                logger.warning("无法接入 ViaVersion，将退回服务端协议版本检测: " + e.getMessage());
            }
            api = null;
            versionMethod = null;
        }
        this.viaApi = api;
        this.viaGetPlayerVersion = versionMethod;
    }

    public int protocol(Player player) {
        if (player == null) {
            return 0;
        }
        return cachedProtocols.computeIfAbsent(player.getUniqueId(), id -> {
            if (viaApi != null && viaGetPlayerVersion != null) {
                try {
                    Object value = viaGetPlayerVersion.invoke(viaApi, id);
                    if (value instanceof Integer version && version > 0) {
                        return version;
                    }
                } catch (ReflectiveOperationException | RuntimeException ignored) {
                    // Fall back to Paper's reported protocol version.
                }
            }
            return player.getProtocolVersion();
        });
    }

    public boolean supportsDialog(Player player) {
        return protocol(player) >= PROTO_DIALOG;
    }

    public void forget(UUID playerId) {
        if (playerId != null) {
            cachedProtocols.remove(playerId);
        }
    }

    public void clear() {
        cachedProtocols.clear();
    }
}
