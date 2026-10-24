package dev.kestrel.staffcheck;

import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.server.network.ServerPlayerEntity;

/**
 * Cleans up /check sessions when either the checker or the target disconnects.
 * Registered in StaffCheckMod.onInitialize().
 */
public final class PlayerConnectionListener {

    public static void register() {
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            ServerPlayerEntity player = handler.player;

            // Case 1: the disconnecting player is a checker with an active session
            CheckSession session = CheckCommand.getSessionForChecker(player);
            if (session != null) {
                CheckCommand.handleCheckerDisconnect(player);
                return;
            }

            // Case 2: the disconnecting player is a target being checked
            if (CheckCommand.isTarget(player)) {
                CheckCommand.handleTargetDisconnect(player);
            }
        });
    }
}
