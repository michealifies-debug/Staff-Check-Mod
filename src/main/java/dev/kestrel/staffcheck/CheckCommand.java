package dev.kestrel.staffcheck;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.command.CommandRegistryAccess;
import net.minecraft.command.argument.EntityArgumentType;
import net.minecraft.entity.decoration.ArmorStandEntity;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtList;
import net.minecraft.nbt.NbtString;
import net.minecraft.network.packet.s2c.play.OverlayMessageS2CPacket;
import net.minecraft.network.packet.s2c.play.TitleFadeS2CPacket;
import net.minecraft.network.packet.s2c.play.TitleS2CPacket;
import net.minecraft.network.packet.s2c.play.SubtitleS2CPacket;
import net.minecraft.network.packet.s2c.play.PlaySoundS2CPacket;
import net.minecraft.registry.Registries;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.GameMode;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static net.minecraft.server.command.CommandManager.argument;
import static net.minecraft.server.command.CommandManager.literal;

/**
 * Handles /check <player> and /uncheck
 *
 * Flow for /check:
 *   1. Checker → SPECTATOR, teleported to target's eye-level camera.
 *   2. Guardian-style entry animation spawns on target (checker skull head).
 *   3. Guardian sound fires for BOTH players.
 *   4. After animation completes (~2 s) → title "[Checker] is WATCHING YOU" in red.
 *
 * Flow for /uncheck:
 *   1. Reverse Guardian animation (rises bottom-to-top) on target.
 *   2. Guardian sound fires for BOTH players.
 *   3. After animation completes → title "[Checker] STOPPED WATCHING YOU" in green.
 *   4. Checker returned to saved location, game mode restored.
 *   5. Target returned to normal (invisible armor stand removed).
 */
public final class CheckCommand {

    // UUID → active session, keyed by CHECKER uuid
    private static final Map<UUID, CheckSession> activeSessions = new ConcurrentHashMap<>();
    // TARGET uuid → checker uuid, for reverse lookup on disconnect / /uncheck from target side
    private static final Map<UUID, UUID> targetToChecker = new ConcurrentHashMap<>();

    private static final ScheduledExecutorService SCHEDULER =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "staffcheck-scheduler");
                t.setDaemon(true);
                return t;
            });

    // Animation frame count — each tick is 50 ms; 30 ticks = 1.5 s rise/fall
    private static final int ANIMATION_TICKS = 30;
    // Vertical travel distance in blocks
    private static final double ANIMATION_HEIGHT = 2.5;

    public static void register(CommandDispatcher<ServerCommandSource> dispatcher) {
        dispatcher.register(literal("check")
                .requires(src -> src.hasPermissionLevel(2))
                .then(argument("player", EntityArgumentType.player())
                        .executes(CheckCommand::executeCheck)
                )
        );

        dispatcher.register(literal("uncheck")
                .requires(src -> src.hasPermissionLevel(2))
                .executes(CheckCommand::executeUncheck)
        );
    }

    // ─────────────────────────────────────────────────────────────────────────
    // /check <player>
    // ─────────────────────────────────────────────────────────────────────────

    private static int executeCheck(CommandContext<ServerCommandSource> ctx) {
        ServerCommandSource src = ctx.getSource();
        ServerPlayerEntity checker;
        try {
            checker = src.getPlayerOrThrow();
        } catch (Exception e) {
            src.sendError(Text.literal("Must be run by a player."));
            return 0;
        }

        ServerPlayerEntity target;
        try {
            target = EntityArgumentType.getPlayer(ctx, "player");
        } catch (Exception e) {
            src.sendError(Text.literal("Player not found."));
            return 0;
        }

        if (checker.getUuid().equals(target.getUuid())) {
            src.sendError(Text.literal("You cannot check yourself."));
            return 0;
        }

        if (activeSessions.containsKey(checker.getUuid())) {
            src.sendError(Text.literal("You already have an active /check session. Use /uncheck first."));
            return 0;
        }

        if (targetToChecker.containsKey(target.getUuid())) {
            src.sendError(Text.literal("That player is already being checked."));
            return 0;
        }

        ServerWorld checkerWorld = checker.getServerWorld();
        Vec3d checkerPos = checker.getPos();
        float checkerYaw = checker.getYaw();
        float checkerPitch = checker.getPitch();
        GameMode previousMode = checker.interactionManager.getGameMode();

        CheckSession session = new CheckSession(
                checker, target,
                checkerPos, checkerWorld.getRegistryKey(),
                checkerYaw, checkerPitch,
                previousMode
        );
        activeSessions.put(checker.getUuid(), session);
        targetToChecker.put(target.getUuid(), checker.getUuid());

        // 1 — Put checker into spectator and teleport to target
        checker.changeGameMode(GameMode.SPECTATOR);
        teleportSpecToTarget(checker, target);

        // 2 — Play Guardian sound for BOTH players immediately
        playGuardianSound(checker);
        playGuardianSound(target);

        // 3 — Run entry animation on the target (armor stand with checker skull descends)
        ServerWorld targetWorld = target.getServerWorld();
        ArmorStandEntity stand = spawnSkullStand(targetWorld, checker, target, true);
        session.skullEntityUuid = stand.getUuid();

        // 4 — After animation finishes, send WATCHING YOU title
        long animDurationMs = ANIMATION_TICKS * 50L;
        SCHEDULER.schedule(() -> {
            target.getServer().execute(() -> {
                if (!activeSessions.containsKey(checker.getUuid())) return; // session ended already
                sendWatchingTitle(target, checker.getName().getString());
            });
        }, animDurationMs, TimeUnit.MILLISECONDS);

        src.sendFeedback(() -> Text.literal("Now checking " + target.getName().getString() + ".").formatted(Formatting.GREEN), false);
        return 1;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // /uncheck
    // ─────────────────────────────────────────────────────────────────────────

    private static int executeUncheck(CommandContext<ServerCommandSource> ctx) {
        ServerCommandSource src = ctx.getSource();
        ServerPlayerEntity checker;
        try {
            checker = src.getPlayerOrThrow();
        } catch (Exception e) {
            src.sendError(Text.literal("Must be run by a player."));
            return 0;
        }

        CheckSession session = activeSessions.get(checker.getUuid());
        if (session == null) {
            src.sendError(Text.literal("No active /check session."));
            return 0;
        }

        doUncheck(session);
        src.sendFeedback(() -> Text.literal("Unchecked " + session.target.getName().getString() + ".").formatted(Formatting.YELLOW), false);
        return 1;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Core uncheck logic (called from command and from disconnect handler)
    // ─────────────────────────────────────────────────────────────────────────

    public static void doUncheck(CheckSession session) {
        ServerPlayerEntity checker = session.checker;
        ServerPlayerEntity target = session.target;

        // Remove session state first to prevent re-entry
        activeSessions.remove(checker.getUuid());
        targetToChecker.remove(target.getUuid());

        // Remove old skull stand if it is still alive
        if (session.skullEntityUuid != null) {
            removeStandByUuid(target.getServerWorld(), session.skullEntityUuid);
            session.skullEntityUuid = null;
        }

        // Play Guardian sound for both
        playGuardianSound(checker);
        playGuardianSound(target);

        // Run exit animation (rises upward) on target
        ServerWorld targetWorld = target.getServerWorld();
        ArmorStandEntity exitStand = spawnSkullStand(targetWorld, checker, target, false);
        UUID exitStandUuid = exitStand.getUuid();

        long animDurationMs = ANIMATION_TICKS * 50L;
        SCHEDULER.schedule(() -> {
            checker.getServer().execute(() -> {
                // Remove exit stand
                removeStandByUuid(targetWorld, exitStandUuid);

                // Send STOPPED WATCHING title to target
                sendStoppedWatchingTitle(target, checker.getName().getString());

                // Return checker to their saved position and game mode
                returnChecker(checker, session);
            });
        }, animDurationMs, TimeUnit.MILLISECONDS);
    }

    // Called when a checked player disconnects — clean up their session silently
    public static void handleTargetDisconnect(ServerPlayerEntity target) {
        UUID checkerUuid = targetToChecker.remove(target.getUuid());
        if (checkerUuid == null) return;
        CheckSession session = activeSessions.remove(checkerUuid);
        if (session == null) return;

        // Remove skull stand
        if (session.skullEntityUuid != null) {
            removeStandByUuid(target.getServerWorld(), session.skullEntityUuid);
        }

        // Return checker quietly
        returnChecker(session.checker, session);
        session.checker.sendMessage(
                Text.literal("[StaffCheck] " + target.getName().getString() + " disconnected — session ended.").formatted(Formatting.YELLOW),
                false
        );
    }

    // Called when the checker disconnects — clean up silently
    public static void handleCheckerDisconnect(ServerPlayerEntity checker) {
        CheckSession session = activeSessions.remove(checker.getUuid());
        if (session == null) return;
        targetToChecker.remove(session.target.getUuid());
        if (session.skullEntityUuid != null) {
            removeStandByUuid(session.target.getServerWorld(), session.skullEntityUuid);
        }
        // Nothing to return — checker is gone
    }

    // Returns true if this player is currently being checked
    public static boolean isTarget(ServerPlayerEntity player) {
        return targetToChecker.containsKey(player.getUuid());
    }

    // Returns the active session for this checker, or null
    public static CheckSession getSessionForChecker(ServerPlayerEntity checker) {
        return activeSessions.get(checker.getUuid());
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Animation helpers
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Spawns an invisible armor stand at the target's location bearing the checker's
     * player-head skull.  When entry=true the stand descends from +2.5 to 0 over
     * ANIMATION_TICKS ticks.  When entry=false it rises from 0 to +2.5.
     */
    private static ArmorStandEntity spawnSkullStand(
            ServerWorld world,
            ServerPlayerEntity checker,
            ServerPlayerEntity target,
            boolean entry
    ) {
        double baseX = target.getX();
        double baseY = target.getY();
        double baseZ = target.getZ();

        // Starting Y offset — entry starts high and drops; exit starts low and rises
        double startOffset = entry ? ANIMATION_HEIGHT : 0.0;
        double endOffset   = entry ? 0.0 : ANIMATION_HEIGHT;

        ArmorStandEntity stand = new ArmorStandEntity(world, baseX, baseY + startOffset, baseZ);
        stand.setInvisible(true);
        stand.setInvulnerable(true);
        stand.setNoGravity(true);
        stand.setSilent(true);
        stand.setCustomNameVisible(false);
        // Marker removes the hitbox so players can't accidentally interact
        stand.setMarker(true);

        // Build the player skull item with checker's skin
        ItemStack skull = new ItemStack(Items.PLAYER_HEAD);
        NbtCompound skullMeta = skull.getOrCreateNbt();
        NbtCompound skullOwner = new NbtCompound();
        skullOwner.putString("Name", checker.getName().getString());
        // Mojang skin UUID string — Minecraft will look up the skin texture at join time
        skullOwner.putString("Id", checker.getUuid().toString());
        skullMeta.put("SkullOwner", skullOwner);
        stand.equipStack(net.minecraft.entity.EquipmentSlot.HEAD, skull);

        world.spawnEntity(stand);

        // Tick-by-tick movement via the main server thread scheduler
        AtomicInteger tick = new AtomicInteger(0);
        final UUID standUuid = stand.getUuid();

        ScheduledFuture<?>[] handle = new ScheduledFuture<?>[1];
        handle[0] = SCHEDULER.scheduleAtFixedRate(() -> {
            world.getServer().execute(() -> {
                int t = tick.getAndIncrement();
                if (t >= ANIMATION_TICKS) {
                    handle[0].cancel(false);
                    return;
                }
                // Ease-out: progress² gives a deceleration feel
                double rawProgress = (double) t / ANIMATION_TICKS;
                // entry: easeIn (fast drop, slow land) = progress²; exit: easeOut (slow rise, fast top)
                double easedProgress = entry
                        ? rawProgress * rawProgress
                        : 1.0 - Math.pow(1.0 - rawProgress, 2);

                double yOffset = startOffset + (endOffset - startOffset) * easedProgress;

                ArmorStandEntity s = findStand(world, standUuid);
                if (s == null) {
                    handle[0].cancel(false);
                    return;
                }
                s.refreshPositionAndAngles(baseX, baseY + yOffset, baseZ, s.getYaw(), s.getPitch());
                s.setPosition(baseX, baseY + yOffset, baseZ);
            });
        }, 50, 50, TimeUnit.MILLISECONDS);

        return stand;
    }

    private static ArmorStandEntity findStand(ServerWorld world, UUID uuid) {
        net.minecraft.entity.Entity e = world.getEntity(uuid);
        if (e instanceof ArmorStandEntity stand) return stand;
        return null;
    }

    private static void removeStandByUuid(ServerWorld world, UUID uuid) {
        ArmorStandEntity stand = findStand(world, uuid);
        if (stand != null) stand.discard();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Spectator teleport
    // ─────────────────────────────────────────────────────────────────────────

    private static void teleportSpecToTarget(ServerPlayerEntity checker, ServerPlayerEntity target) {
        ServerWorld targetWorld = target.getServerWorld();
        checker.teleport(
                targetWorld,
                target.getX(),
                target.getY() + target.getStandingEyeHeight(),
                target.getZ(),
                target.getYaw(),
                target.getPitch()
        );
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Return checker
    // ─────────────────────────────────────────────────────────────────────────

    private static void returnChecker(ServerPlayerEntity checker, CheckSession session) {
        net.minecraft.server.MinecraftServer server = checker.getServer();
        if (server == null) return;
        ServerWorld returnWorld = server.getWorld(session.checkerReturnWorld);
        if (returnWorld == null) returnWorld = server.getOverworld();

        checker.teleport(
                returnWorld,
                session.checkerReturnPos.x,
                session.checkerReturnPos.y,
                session.checkerReturnPos.z,
                session.checkerReturnYaw,
                session.checkerReturnPitch
        );
        checker.changeGameMode(session.checkerPreviousGameMode);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Sound
    // ─────────────────────────────────────────────────────────────────────────

    private static void playGuardianSound(ServerPlayerEntity player) {
        // entity.guardian.curse is the iconic cinematic stare sound
        player.networkHandler.sendPacket(new PlaySoundS2CPacket(
                Registries.SOUND_EVENT.getEntry(SoundEvents.ENTITY_GUARDIAN_CURSE),
                SoundCategory.HOSTILE,
                player.getX(), player.getY(), player.getZ(),
                1.0f,   // volume
                1.0f,   // pitch
                0L      // seed
        ));
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Titles
    // ─────────────────────────────────────────────────────────────────────────

    private static void sendWatchingTitle(ServerPlayerEntity target, String checkerName) {
        // fade-in 20 ticks, stay 60 ticks, fade-out 20 ticks  (20t = 1 s)
        target.networkHandler.sendPacket(new TitleFadeS2CPacket(20, 60, 20));
        target.networkHandler.sendPacket(new TitleS2CPacket(
                Text.literal(checkerName).formatted(Formatting.RED, Formatting.BOLD)
        ));
        target.networkHandler.sendPacket(new SubtitleS2CPacket(
                Text.literal("is WATCHING YOU").formatted(Formatting.RED, Formatting.BOLD)
        ));
    }

    private static void sendStoppedWatchingTitle(ServerPlayerEntity target, String checkerName) {
        target.networkHandler.sendPacket(new TitleFadeS2CPacket(20, 60, 20));
        target.networkHandler.sendPacket(new TitleS2CPacket(
                Text.literal(checkerName).formatted(Formatting.GREEN, Formatting.BOLD)
        ));
        target.networkHandler.sendPacket(new SubtitleS2CPacket(
                Text.literal("STOPPED WATCHING YOU").formatted(Formatting.GREEN, Formatting.BOLD)
        ));
    }
}
