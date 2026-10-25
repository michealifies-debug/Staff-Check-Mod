package dev.kestrel.staffcheck;

import com.mojang.authlib.GameProfile;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.command.argument.EntityArgumentType;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.ProfileComponent;
import net.minecraft.entity.decoration.ArmorStandEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.network.packet.s2c.play.PlaySoundS2CPacket;
import net.minecraft.network.packet.s2c.play.SubtitleS2CPacket;
import net.minecraft.network.packet.s2c.play.TitleFadeS2CPacket;
import net.minecraft.network.packet.s2c.play.TitleS2CPacket;
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
 *   3. Elder Guardian sound fires for BOTH players.
 *   4. After animation completes (~1.5 s) → title "[Checker] is WATCHING YOU" in red.
 *
 * Flow for /uncheck:
 *   1. Reverse Guardian animation (rises bottom-to-top) on target.
 *   2. Elder Guardian sound fires for BOTH players.
 *   3. After animation completes → title "[Checker] STOPPED WATCHING YOU" in green.
 *   4. Checker returned to saved location, game mode restored.
 */
public final class CheckCommand {

    // UUID → active session, keyed by CHECKER uuid
    private static final Map<UUID, CheckSession> activeSessions = new ConcurrentHashMap<>();
    // TARGET uuid → checker uuid, for reverse lookup
    private static final Map<UUID, UUID> targetToChecker = new ConcurrentHashMap<>();

    private static final ScheduledExecutorService SCHEDULER =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "staffcheck-scheduler");
                t.setDaemon(true);
                return t;
            });

    private static final int  ANIMATION_TICKS  = 30;
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
        try { checker = src.getPlayerOrThrow(); }
        catch (Exception e) { src.sendError(Text.literal("Must be run by a player.")); return 0; }

        ServerPlayerEntity target;
        try { target = EntityArgumentType.getPlayer(ctx, "player"); }
        catch (Exception e) { src.sendError(Text.literal("Player not found.")); return 0; }

        if (checker.getUuid().equals(target.getUuid())) {
            src.sendError(Text.literal("You cannot check yourself.")); return 0;
        }
        if (activeSessions.containsKey(checker.getUuid())) {
            src.sendError(Text.literal("You already have an active /check session. Use /uncheck first.")); return 0;
        }
        if (targetToChecker.containsKey(target.getUuid())) {
            src.sendError(Text.literal("That player is already being checked.")); return 0;
        }

        CheckSession session = new CheckSession(
                checker, target,
                checker.getPos(), checker.getServerWorld().getRegistryKey(),
                checker.getYaw(), checker.getPitch(),
                checker.interactionManager.getGameMode()
        );
        activeSessions.put(checker.getUuid(), session);
        targetToChecker.put(target.getUuid(), checker.getUuid());

        checker.changeGameMode(GameMode.SPECTATOR);
        teleportSpecToTarget(checker, target);

        playGuardianSound(checker);
        playGuardianSound(target);

        ArmorStandEntity stand = spawnSkullStand(target.getServerWorld(), checker, target, true);
        session.skullEntityUuid = stand.getUuid();

        long animMs = ANIMATION_TICKS * 50L;
        SCHEDULER.schedule(() -> checker.getServer().execute(() -> {
            if (!activeSessions.containsKey(checker.getUuid())) return;
            sendWatchingTitle(target, checker.getName().getString());
        }), animMs, TimeUnit.MILLISECONDS);

        src.sendFeedback(() -> Text.literal("Now checking " + target.getName().getString() + ".").formatted(Formatting.GREEN), false);
        return 1;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // /uncheck
    // ─────────────────────────────────────────────────────────────────────────

    private static int executeUncheck(CommandContext<ServerCommandSource> ctx) {
        ServerCommandSource src = ctx.getSource();
        ServerPlayerEntity checker;
        try { checker = src.getPlayerOrThrow(); }
        catch (Exception e) { src.sendError(Text.literal("Must be run by a player.")); return 0; }

        CheckSession session = activeSessions.get(checker.getUuid());
        if (session == null) { src.sendError(Text.literal("No active /check session.")); return 0; }

        doUncheck(session);
        src.sendFeedback(() -> Text.literal("Unchecked " + session.target.getName().getString() + ".").formatted(Formatting.YELLOW), false);
        return 1;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Core uncheck (also called from disconnect handler)
    // ─────────────────────────────────────────────────────────────────────────

    public static void doUncheck(CheckSession session) {
        ServerPlayerEntity checker = session.checker;
        ServerPlayerEntity target  = session.target;

        activeSessions.remove(checker.getUuid());
        targetToChecker.remove(target.getUuid());

        if (session.skullEntityUuid != null) {
            removeStandByUuid(target.getServerWorld(), session.skullEntityUuid);
            session.skullEntityUuid = null;
        }

        playGuardianSound(checker);
        playGuardianSound(target);

        ArmorStandEntity exitStand = spawnSkullStand(target.getServerWorld(), checker, target, false);
        UUID exitUuid = exitStand.getUuid();

        long animMs = ANIMATION_TICKS * 50L;
        SCHEDULER.schedule(() -> checker.getServer().execute(() -> {
            removeStandByUuid(target.getServerWorld(), exitUuid);
            sendStoppedWatchingTitle(target, checker.getName().getString());
            returnChecker(checker, session);
        }), animMs, TimeUnit.MILLISECONDS);
    }

    public static void handleTargetDisconnect(ServerPlayerEntity target) {
        UUID checkerUuid = targetToChecker.remove(target.getUuid());
        if (checkerUuid == null) return;
        CheckSession session = activeSessions.remove(checkerUuid);
        if (session == null) return;
        if (session.skullEntityUuid != null) removeStandByUuid(target.getServerWorld(), session.skullEntityUuid);
        returnChecker(session.checker, session);
        session.checker.sendMessage(Text.literal("[StaffCheck] " + target.getName().getString() + " disconnected — session ended.").formatted(Formatting.YELLOW), false);
    }

    public static void handleCheckerDisconnect(ServerPlayerEntity checker) {
        CheckSession session = activeSessions.remove(checker.getUuid());
        if (session == null) return;
        targetToChecker.remove(session.target.getUuid());
        if (session.skullEntityUuid != null) removeStandByUuid(session.target.getServerWorld(), session.skullEntityUuid);
    }

    public static boolean isTarget(ServerPlayerEntity player) {
        return targetToChecker.containsKey(player.getUuid());
    }

    public static CheckSession getSessionForChecker(ServerPlayerEntity checker) {
        return activeSessions.get(checker.getUuid());
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Animation
    // ─────────────────────────────────────────────────────────────────────────

    private static ArmorStandEntity spawnSkullStand(
            ServerWorld world, ServerPlayerEntity checker, ServerPlayerEntity target, boolean entry) {

        double baseX = target.getX();
        double baseY = target.getY();
        double baseZ = target.getZ();

        double startOffset = entry ? ANIMATION_HEIGHT : 0.0;
        double endOffset   = entry ? 0.0 : ANIMATION_HEIGHT;

        ArmorStandEntity stand = new ArmorStandEntity(world, baseX, baseY + startOffset, baseZ);
        stand.setInvisible(true);
        stand.setInvulnerable(true);
        stand.setNoGravity(true);
        stand.setSilent(true);
        stand.setCustomNameVisible(false);

        // Set Marker flag via NBT (setMarker is private in 1.21.1)
        NbtCompound nbt = new NbtCompound();
        stand.writeNbt(nbt);
        nbt.putBoolean("Marker", true);
        stand.readNbt(nbt);

        // Build player skull with checker's skin using 1.21.1 component API
        ItemStack skull = new ItemStack(Items.PLAYER_HEAD);
        GameProfile profile = checker.getGameProfile();
        skull.set(DataComponentTypes.PROFILE, new ProfileComponent(profile));

        stand.equipStack(net.minecraft.entity.EquipmentSlot.HEAD, skull);
        world.spawnEntity(stand);

        AtomicInteger tick = new AtomicInteger(0);
        UUID standUuid = stand.getUuid();
        ScheduledFuture<?>[] handle = new ScheduledFuture<?>[1];
        handle[0] = SCHEDULER.scheduleAtFixedRate(() -> {
            world.getServer().execute(() -> {
                int t = tick.getAndIncrement();
                if (t >= ANIMATION_TICKS) { handle[0].cancel(false); return; }

                double rawProgress = (double) t / ANIMATION_TICKS;
                double eased = entry
                        ? rawProgress * rawProgress
                        : 1.0 - Math.pow(1.0 - rawProgress, 2);

                double yOffset = startOffset + (endOffset - startOffset) * eased;
                ArmorStandEntity s = findStand(world, standUuid);
                if (s == null) { handle[0].cancel(false); return; }
                s.setPosition(baseX, baseY + yOffset, baseZ);
            });
        }, 50, 50, TimeUnit.MILLISECONDS);

        return stand;
    }

    private static ArmorStandEntity findStand(ServerWorld world, UUID uuid) {
        net.minecraft.entity.Entity e = world.getEntity(uuid);
        return e instanceof ArmorStandEntity a ? a : null;
    }

    private static void removeStandByUuid(ServerWorld world, UUID uuid) {
        ArmorStandEntity s = findStand(world, uuid);
        if (s != null) s.discard();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Teleport helpers
    // ─────────────────────────────────────────────────────────────────────────

    private static void teleportSpecToTarget(ServerPlayerEntity checker, ServerPlayerEntity target) {
        checker.teleport(
                target.getServerWorld(),
                target.getX(), target.getY() + target.getStandingEyeHeight(), target.getZ(),
                target.getYaw(), target.getPitch()
        );
    }

    private static void returnChecker(ServerPlayerEntity checker, CheckSession session) {
        net.minecraft.server.MinecraftServer server = checker.getServer();
        if (server == null) return;
        ServerWorld returnWorld = server.getWorld(session.checkerReturnWorld);
        if (returnWorld == null) returnWorld = server.getOverworld();
        checker.teleport(returnWorld,
                session.checkerReturnPos.x, session.checkerReturnPos.y, session.checkerReturnPos.z,
                session.checkerReturnYaw, session.checkerReturnPitch);
        checker.changeGameMode(session.checkerPreviousGameMode);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Sound — ENTITY_ELDER_GUARDIAN_CURSE is the correct 1.21.1 Yarn name
    // ─────────────────────────────────────────────────────────────────────────

    private static void playGuardianSound(ServerPlayerEntity player) {
        player.networkHandler.sendPacket(new PlaySoundS2CPacket(
                SoundEvents.ENTITY_ELDER_GUARDIAN_CURSE,
                SoundCategory.HOSTILE,
                player.getX(), player.getY(), player.getZ(),
                1.0f, 1.0f, 0L
        ));
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Titles
    // ─────────────────────────────────────────────────────────────────────────

    private static void sendWatchingTitle(ServerPlayerEntity target, String checkerName) {
        // 20t fade-in, 60t stay (~3 s), 20t fade-out
        target.networkHandler.sendPacket(new TitleFadeS2CPacket(20, 60, 20));
        target.networkHandler.sendPacket(new TitleS2CPacket(
                Text.literal(checkerName).formatted(Formatting.RED, Formatting.BOLD)));
        target.networkHandler.sendPacket(new SubtitleS2CPacket(
                Text.literal("is WATCHING YOU").formatted(Formatting.RED, Formatting.BOLD)));
    }

    private static void sendStoppedWatchingTitle(ServerPlayerEntity target, String checkerName) {
        target.networkHandler.sendPacket(new TitleFadeS2CPacket(20, 60, 20));
        target.networkHandler.sendPacket(new TitleS2CPacket(
                Text.literal(checkerName).formatted(Formatting.GREEN, Formatting.BOLD)));
        target.networkHandler.sendPacket(new SubtitleS2CPacket(
                Text.literal("STOPPED WATCHING YOU").formatted(Formatting.GREEN, Formatting.BOLD)));
    }
}
