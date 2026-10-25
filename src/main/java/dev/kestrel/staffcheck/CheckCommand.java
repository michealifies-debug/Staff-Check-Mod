package dev.kestrel.staffcheck;

import com.mojang.authlib.GameProfile;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.command.argument.EntityArgumentType;
import net.minecraft.command.permission.Permission;
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
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.world.GameMode;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static net.minecraft.server.command.CommandManager.argument;
import static net.minecraft.server.command.CommandManager.literal;

public final class CheckCommand {

    private static final Map<UUID, CheckSession> activeSessions = new ConcurrentHashMap<>();
    private static final Map<UUID, UUID> targetToChecker = new ConcurrentHashMap<>();

    private static final ScheduledExecutorService SCHEDULER =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "staffcheck-scheduler");
                t.setDaemon(true);
                return t;
            });

    private static final int ANIMATION_TICKS = 30;
    private static final double ANIMATION_HEIGHT = 2.5;

    public static void register(CommandDispatcher<ServerCommandSource> dispatcher) {
        dispatcher.register(literal("check")
                .requires(src -> src.hasPermission(Permission.GAMEMASTERS))
                .then(argument("player", EntityArgumentType.player())
                        .executes(CheckCommand::executeCheck)));

        dispatcher.register(literal("uncheck")
                .requires(src -> src.hasPermission(Permission.GAMEMASTERS))
                .executes(CheckCommand::executeUncheck));
    }

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
            src.sendError(Text.literal("Already in a session. Use /uncheck first."));
            return 0;
        }

        if (targetToChecker.containsKey(target.getUuid())) {
            src.sendError(Text.literal("That player is already being checked."));
            return 0;
        }

        CheckSession session = new CheckSession(
                checker,
                target,
                checker.getPos(),
                checker.getServerWorld().getRegistryKey(),
                checker.getYaw(),
                checker.getPitch(),
                checker.interactionManager.getGameMode()
        );

        activeSessions.put(checker.getUuid(), session);
        targetToChecker.put(target.getUuid(), checker.getUuid());

        checker.changeGameMode(GameMode.SPECTATOR);
        teleportSpecToTarget(checker, target);

        playGuardianSound(checker);
        playGuardianSound(target);

        ArmorStandEntity stand = spawnSkullStand(
                target.getServerWorld(), checker, target, true
        );
        session.skullEntityUuid = stand.getUuid();

        SCHEDULER.schedule(() -> checker.getServer().execute(() -> {
            if (!activeSessions.containsKey(checker.getUuid())) {
                return;
            }
            sendWatchingTitle(target, checker.getName().getString());
        }), ANIMATION_TICKS * 50L, TimeUnit.MILLISECONDS);

        src.sendFeedback(
                () -> Text.literal("Now checking " + target.getName().getString())
                        .formatted(Formatting.GREEN),
                false
        );

        return 1;
    }

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

        src.sendFeedback(
                () -> Text.literal("Unchecked " + session.target.getName().getString())
                        .formatted(Formatting.YELLOW),
                false
        );

        return 1;
    }

    public static void doUncheck(CheckSession session) {
        ServerPlayerEntity checker = session.checker;
        ServerPlayerEntity target = session.target;

        activeSessions.remove(checker.getUuid());
        targetToChecker.remove(target.getUuid());

        if (session.skullEntityUuid != null) {
            removeStandByUuid(target.getServerWorld(), session.skullEntityUuid);
            session.skullEntityUuid = null;
        }

        playGuardianSound(checker);
        playGuardianSound(target);

        ArmorStandEntity exitStand = spawnSkullStand(
                target.getServerWorld(), checker, target, false
        );

        UUID exitUuid = exitStand.getUuid();

        SCHEDULER.schedule(() -> checker.getServer().execute(() -> {
            removeStandByUuid(target.getServerWorld(), exitUuid);
            sendStoppedWatchingTitle(target, checker.getName().getString());
            returnChecker(checker, session);
        }), ANIMATION_TICKS * 50L, TimeUnit.MILLISECONDS);
    }

    public static void handleTargetDisconnect(ServerPlayerEntity target) {
        UUID checkerUuid = targetToChecker.remove(target.getUuid());

        if (checkerUuid == null) {
            return;
        }

        CheckSession session = activeSessions.remove(checkerUuid);

        if (session == null) {
            return;
        }

        if (session.skullEntityUuid != null) {
            removeStandByUuid(target.getServerWorld(), session.skullEntityUuid);
        }

        returnChecker(session.checker, session);

        session.checker.sendMessage(
                Text.literal("[StaffCheck] " + target.getName().getString() + " disconnected.")
                        .formatted(Formatting.YELLOW),
                false
        );
    }

    public static void handleCheckerDisconnect(ServerPlayerEntity checker) {
        CheckSession session = activeSessions.remove(checker.getUuid());

        if (session == null) {
            return;
        }

        targetToChecker.remove(session.target.getUuid());

        if (session.skullEntityUuid != null) {
            removeStandByUuid(session.target.getServerWorld(), session.skullEntityUuid);
        }
    }

    public static boolean isTarget(ServerPlayerEntity player) {
        return targetToChecker.containsKey(player.getUuid());
    }

    public static CheckSession getSessionForChecker(ServerPlayerEntity checker) {
        return activeSessions.get(checker.getUuid());
    }

    private static ArmorStandEntity spawnSkullStand(
            ServerWorld world,
            ServerPlayerEntity checker,
            ServerPlayerEntity target,
            boolean entry
    ) {
        double baseX = target.getX();
        double baseY = target.getY();
        double baseZ = target.getZ();

        double startOffset = entry ? ANIMATION_HEIGHT : 0.0;
        double endOffset = entry ? 0.0 : ANIMATION_HEIGHT;

        ArmorStandEntity stand = new ArmorStandEntity(
                world,
                baseX,
                baseY + startOffset,
                baseZ
        );

        stand.setInvisible(true);
        stand.setInvulnerable(true);
        stand.setNoGravity(true);
        stand.setSilent(true);
        stand.setCustomNameVisible(false);

        NbtCompound nbt = new NbtCompound();
        stand.writeNbt(nbt);
        nbt.putBoolean("Marker", true);
        stand.readNbt(nbt);

        ItemStack skull = new ItemStack(Items.PLAYER_HEAD);
        skull.set(
                DataComponentTypes.PROFILE,
                new ProfileComponent(checker.getGameProfile())
        );

        stand.equipStack(net.minecraft.entity.EquipmentSlot.HEAD, skull);
        world.spawnEntity(stand);

        AtomicInteger tick = new AtomicInteger(0);
        UUID standUuid = stand.getUuid();

        ScheduledFuture<?>[] handle = new ScheduledFuture<?>[1];

        handle[0] = SCHEDULER.scheduleAtFixedRate(
                () -> world.getServer().execute(() -> {
                    int t = tick.getAndIncrement();

                    if (t >= ANIMATION_TICKS) {
                        handle[0].cancel(false);
                        return;
                    }

                    double raw = (double) t / ANIMATION_TICKS;
                    double eased = entry
                            ? raw * raw
                            : 1.0 - Math.pow(1.0 - raw, 2);

                    double yOff = startOffset + (endOffset - startOffset) * eased;

                    ArmorStandEntity s = findStand(world, standUuid);

                    if (s == null) {
                        handle[0].cancel(false);
                        return;
                    }

                    s.setPosition(baseX, baseY + yOff, baseZ);
                }),
                50,
                50,
                TimeUnit.MILLISECONDS
        );

        return stand;
    }

    private static ArmorStandEntity findStand(ServerWorld world, UUID uuid) {
        net.minecraft.entity.Entity entity = world.getEntity(uuid);
        return entity instanceof ArmorStandEntity stand ? stand : null;
    }

    private static void removeStandByUuid(ServerWorld world, UUID uuid) {
        ArmorStandEntity stand = findStand(world, uuid);

        if (stand != null) {
            stand.discard();
        }
    }

    private static void teleportSpecToTarget(
            ServerPlayerEntity checker,
            ServerPlayerEntity target
    ) {
        checker.teleport(
                target.getServerWorld(),
                target.getX(),
                target.getY() + target.getStandingEyeHeight(),
                target.getZ(),
                Set.of(),
                target.getYaw(),
                target.getPitch(),
                false
        );
    }

    private static void returnChecker(
            ServerPlayerEntity checker,
            CheckSession session
    ) {
        net.minecraft.server.MinecraftServer server = checker.getServer();

        if (server == null) {
            return;
        }

        ServerWorld returnWorld = server.getWorld(session.checkerReturnWorld);

        if (returnWorld == null) {
            returnWorld = server.getOverworld();
        }

        checker.teleport(
                returnWorld,
                session.checkerReturnPos.x,
                session.checkerReturnPos.y,
                session.checkerReturnPos.z,
                Set.of(),
                session.checkerReturnYaw,
                session.checkerReturnPitch,
                false
        );

        checker.changeGameMode(session.checkerPreviousGameMode);
    }

    private static void playGuardianSound(ServerPlayerEntity player) {
        player.networkHandler.sendPacket(new PlaySoundS2CPacket(
                RegistryEntry.of(SoundEvents.ENTITY_ELDER_GUARDIAN_CURSE),
                SoundCategory.HOSTILE,
                player.getX(),
                player.getY(),
                player.getZ(),
                1.0f,
                1.0f,
                0L
        ));
    }

    private static void sendWatchingTitle(
            ServerPlayerEntity target,
            String checkerName
    ) {
        target.networkHandler.sendPacket(new TitleFadeS2CPacket(20, 60, 20));

        target.networkHandler.sendPacket(new TitleS2CPacket(
                Text.literal(checkerName).formatted(Formatting.RED, Formatting.BOLD)
        ));

        target.networkHandler.sendPacket(new SubtitleS2CPacket(
                Text.literal("is WATCHING YOU").formatted(Formatting.RED, Formatting.BOLD)
        ));
    }

    private static void sendStoppedWatchingTitle(
            ServerPlayerEntity target,
            String checkerName
    ) {
        target.networkHandler.sendPacket(new TitleFadeS2CPacket(20, 60, 20));

        target.networkHandler.sendPacket(new TitleS2CPacket(
                Text.literal(checkerName).formatted(Formatting.GREEN, Formatting.BOLD)
        ));

        target.networkHandler.sendPacket(new SubtitleS2CPacket(
                Text.literal("STOPPED WATCHING YOU").formatted(Formatting.GREEN, Formatting.BOLD)
        ));
    }
}
