package com.gatedjei.discovery;

import com.gatedjei.Config;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ServerData;

/**
 * Works out a stable key for "which discovery set are we using right now", from the client side.
 */
public final class SaveContext {
    private SaveContext() {}

    /** @return the storage key for the current world, honouring the GLOBAL/PER_SAVE config. */
    public static String currentKey() {
        if (Config.DISCOVERY_SCOPE.get() == Config.DiscoveryScope.GLOBAL) {
            return "global";
        }
        return perSaveKey();
    }

    private static String perSaveKey() {
        Minecraft mc = Minecraft.getInstance();

        // Singleplayer: key off the world's name.
        // TODO(verify): getWorldData().getLevelName() returns the display name. Two saves can share a
        // display name; if that matters to you, switch to the on-disk folder id via the integrated
        // server's LevelStorageAccess#getLevelId() (needs an access transformer for storageSource).
        if (mc.getSingleplayerServer() != null) {
            try {
                String name = mc.getSingleplayerServer().getWorldData().getLevelName();
                return "sp_" + sanitize(name);
            } catch (Throwable t) {
                return "sp_unknown";
            }
        }

        // Multiplayer: key off the server address.
        ServerData server = mc.getCurrentServer();
        if (server != null && server.ip != null) {
            return "mp_" + sanitize(server.ip);
        }

        // LAN / unknown.
        return "default";
    }

    private static String sanitize(String raw) {
        if (raw == null || raw.isBlank()) {
            return "unnamed";
        }
        String cleaned = raw.toLowerCase().replaceAll("[^a-z0-9._-]", "_");
        // Keep filenames sane.
        if (cleaned.length() > 80) {
            cleaned = cleaned.substring(0, 80) + "_" + Integer.toHexString(raw.hashCode());
        }
        return cleaned;
    }
}
