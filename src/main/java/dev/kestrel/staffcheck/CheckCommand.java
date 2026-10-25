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
import net.minecraft.registry.entry.RegistryEntry;
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

public final class CheckCommand {

    private static final Map<UUID, CheckSession> activeSessions = new ConcurrentHashMap<>();
    private static final Map<UUID, UUID> targetToChecker = new ConcurrentHashMap<>();

    private static final ScheduledExecutorService SCHEDULER =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "staffcheck-scheduler");
                t.setDaemon(true);
                return t;
            });

    private static final int    ANIMATION_TICKS  = 30;
    private static final double ANIMATION_HEIGHT = 2.5;

    public static void register(CommandDispatcher<ServerCommandSource> dispatcher) {
        dispatcher.register(literal("check")
                .requires(src -> src.hasPermissionLevel(2))
                .then(argument("player", EntityArgumentType.player())
                        .executes(CheckCommand::executeCheck)));
        dispatcher.register(literal("uncheck")
                .requires(src -> src.hasPermissionLevel(2))
                .executes(CheckCommand::executeUncheck));
    }

    private static int executeCheck(CommandContext<ServerCommandSource> ctx) {
        ServerCommandSource src = ctx.getSource();
        ServerPlayerEntity checker;
        try { checker = src.getPlayerOrThrow(); }
        catch (Exception e) { src.sendError(Text.literal("Must be run by a player.")); return 0; }

        ServerPlayerEntity target;
        try { target = EntityArgumentType.getPlayer(ctx, "player"); }
        catch (Exception e) { src.sendError(Text.literal("Player not found.")); return 0; }

        if (checker.getUuid().equals(target.getUuid())) {
            src.sendError(Text.literal("You cannot check yourself.")); return 0; }
        if (activeSessions.containsKey(checker.getUuid())) {
            src.sendError(Text.literal("Already in a session. Use /uncheck first.")); return 0; }
        if (targetToChecker.containsKey(target.getUuid())) {
            src.sendError(Text.literal("That player is already being checked.")); return 0; }

        CheckSession session = new CheckSession(
                checker, target,
                checker.getPos(), checker.getServerWorld().getRegistryKey(),
