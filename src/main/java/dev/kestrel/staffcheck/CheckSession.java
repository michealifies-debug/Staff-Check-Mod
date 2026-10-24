package dev.kestrel.staffcheck;

import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.GameMode;

/**
 * Holds the full state for one active /check engagement.
 * Checker and target are never null while the session is live.
 */
public final class CheckSession {

    /** Player who issued /check */
    public final ServerPlayerEntity checker;

    /** Player being watched */
    public final ServerPlayerEntity target;

    /** Where the checker was standing when /check was issued */
    public final Vec3d checkerReturnPos;

    /** World key the checker was in — returned on /uncheck */
    public final net.minecraft.registry.RegistryKey<net.minecraft.world.World> checkerReturnWorld;

    /** Checker's yaw at /check time */
    public final float checkerReturnYaw;

    /** Checker's pitch at /check time */
    public final float checkerReturnPitch;

    /** Game mode the checker had before being put into spectator */
    public final GameMode checkerPreviousGameMode;

    /** Skull display entity UUID — cleaned up on /uncheck */
    public java.util.UUID skullEntityUuid = null;

    public CheckSession(
            ServerPlayerEntity checker,
            ServerPlayerEntity target,
            Vec3d checkerReturnPos,
            net.minecraft.registry.RegistryKey<net.minecraft.world.World> checkerReturnWorld,
            float checkerReturnYaw,
            float checkerReturnPitch,
            GameMode checkerPreviousGameMode
    ) {
        this.checker = checker;
        this.target = target;
        this.checkerReturnPos = checkerReturnPos;
        this.checkerReturnWorld = checkerReturnWorld;
        this.checkerReturnYaw = checkerReturnYaw;
        this.checkerReturnPitch = checkerReturnPitch;
        this.checkerPreviousGameMode = checkerPreviousGameMode;
    }
}
