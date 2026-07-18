package net.skds.wpo.river;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

public final class RiverBenchmarkCommand {
    private RiverBenchmarkCommand() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("wpo")
            .then(Commands.literal("river_benchmark")
                .requires(source -> source.hasPermission(2))
                .then(Commands.literal("run")
                    .executes(RiverBenchmarkCommand::run)
                    .then(Commands.literal("river_only")
                        .executes(RiverBenchmarkCommand::runRiverOnly)
                    )
                    .then(Commands.literal("manual")
                        .executes(RiverBenchmarkCommand::runRiverOnly)
                    )
                )
                .then(Commands.literal("status")
                    .executes(RiverBenchmarkCommand::status)
                )
                .then(Commands.literal("stop")
                    .executes(RiverBenchmarkCommand::stop)
                )
            )
            .then(Commands.literal("river_probe")
                .requires(source -> source.hasPermission(2))
                .executes(RiverBenchmarkCommand::probe)
            )
            .then(Commands.literal("river_coherence")
                .requires(source -> source.hasPermission(2))
                .executes(RiverBenchmarkCommand::coherence)
            )
            .then(Commands.literal("river_clear")
                .requires(source -> source.hasPermission(2))
                .executes(ctx -> {
                    RiverCurrentField.clearAndSync(ctx.getSource().getLevel());
                    ctx.getSource().sendSuccess(() -> Component.literal("river_clear: cleared cached currents for this dimension"), true);
                    return 1;
                })
            )
            .then(Commands.literal("river_arrows")
                .requires(source -> source.hasPermission(2))
                .executes(ctx -> {
                    boolean on = RiverFlowArrows.toggle();
                    ctx.getSource().sendSuccess(() -> Component.literal("river_arrows: " + (on ? "on" : "off")), false);
                    return 1;
                })
            )
            .then(Commands.literal("river_reverse")
                .requires(source -> source.hasPermission(2))
                .executes(ctx -> {
                    boolean reversed = RiverCurrentField.toggleReversed(ctx.getSource().getLevel());
                    ctx.getSource().sendSuccess(
                            () -> Component.literal("river_reverse: " + (reversed ? "on" : "off")), false);
                    return 1;
                })
            )
            .then(Commands.literal("river_speed")
                .requires(source -> source.hasPermission(2))
                .executes(ctx -> {
                    double current = RiverConfig.COMMON.visualCurrentMultiplier.get();
                    ctx.getSource().sendSuccess(() -> Component.literal("river_speed: " + current), false);
                    return 1;
                })
                .then(Commands.argument("multiplier", DoubleArgumentType.doubleArg(0.0D, 8.0D))
                    .executes(ctx -> {
                        double value = DoubleArgumentType.getDouble(ctx, "multiplier");
                        RiverConfig.COMMON.visualCurrentMultiplier.set(value);
                        ctx.getSource().sendSuccess(() -> Component.literal("river_speed: " + value), true);
                        return 1;
                    })
                )
            )
        );
    }

    private static int run(CommandContext<CommandSourceStack> ctx) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        String message = RiverBenchmarkManager.start(player);
        ctx.getSource().sendSuccess(() -> Component.literal(message), true);
        return 1;
    }

    private static int runRiverOnly(CommandContext<CommandSourceStack> ctx) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        String message = RiverBenchmarkManager.startRiverOnly(player);
        ctx.getSource().sendSuccess(() -> Component.literal(message), true);
        return 1;
    }

    private static int status(CommandContext<CommandSourceStack> ctx) {
        ctx.getSource().sendSuccess(() -> Component.literal(RiverBenchmarkManager.status(ctx.getSource().getLevel())), false);
        return 1;
    }

    private static int stop(CommandContext<CommandSourceStack> ctx) {
        ctx.getSource().sendSuccess(() -> Component.literal(RiverBenchmarkManager.stop(ctx.getSource().getLevel())), true);
        return 1;
    }

    private static int probe(CommandContext<CommandSourceStack> ctx) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        ctx.getSource().sendSuccess(() -> Component.literal(RiverTicker.probe(player)), false);
        return 1;
    }

    private static int coherence(CommandContext<CommandSourceStack> ctx) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        ctx.getSource().sendSuccess(() -> Component.literal(RiverFlowDiagnostics.report(player)), false);
        return 1;
    }
}
