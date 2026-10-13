/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.andriyko69.chronicle.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import io.github.andriyko69.chronicle.event.ChronicleClock;
import io.github.andriyko69.chronicle.persistence.StorageFailure;
import io.github.andriyko69.chronicle.query.*;
import io.github.andriyko69.chronicle.runtime.ChronicleRuntime;
import java.util.concurrent.*;
import java.util.function.Consumer;
import java.util.function.Supplier;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

public final class ChronicleCommands {
    private final Supplier<ChronicleRuntime> runtime;
    private final ChronicleClock clock;
    private final LookupParser parser;
    private final LookupSessions sessions;
    public ChronicleCommands(Supplier<ChronicleRuntime> runtime, ChronicleClock clock) {
        this.runtime = runtime; this.clock = clock; parser = new LookupParser(clock); sessions = new LookupSessions(clock);
    }
    public void onRegister(RegisterCommandsEvent event) { register(event.getDispatcher()); }
    public void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(root("chronicle"));
        dispatcher.register(root("ch"));
    }
    public void clearSessions() { sessions.clear(); }
    private LiteralArgumentBuilder<CommandSourceStack> root(String name) {
        return Commands.literal(name).requires(source -> source.hasPermission(2))
                .then(lookup("lookup")).then(lookup("l"))
                .then(page("page")).then(page("p"))
                .then(Commands.literal("status").executes(context -> status(context.getSource())));
    }
    private LiteralArgumentBuilder<CommandSourceStack> lookup(String name) {
        return Commands.literal(name).executes(context -> lookup(context.getSource(), ""))
                .then(Commands.argument("query", StringArgumentType.greedyString())
                        .executes(context -> lookup(context.getSource(), StringArgumentType.getString(context, "query"))));
    }
    private LiteralArgumentBuilder<CommandSourceStack> page(String name) {
        return Commands.literal(name).then(Commands.argument("page", IntegerArgumentType.integer(1, LookupSpec.MAX_PAGE))
                .executes(context -> page(context.getSource(), IntegerArgumentType.getInteger(context, "page"))));
    }
    private static String caller(CommandSourceStack source) {
        return source.getPlayer() == null ? LookupSessions.CONSOLE : source.getPlayer().getUUID().toString();
    }
    private int lookup(CommandSourceStack source, String text) {
        if (!source.getServer().isSameThread()) return 0;
        try {
            var player = source.getPlayer();
            var position = net.minecraft.core.BlockPos.containing(source.getPosition());
            var context = player == null ? LookupParser.Context.console() : new LookupParser.Context(
                    source.getLevel().dimension().location().toString(), new LookupSpec.Center(position.getX(), position.getY(), position.getZ()));
            var spec = parser.parse(text, context);
            String key = caller(source);
            var active = runtime.get();
            if (active == null) return unavailable(source);
            return deliver(source, active, active.lookup(spec, 1), result -> {
                sessions.put(key, spec);
                ChronicleFormatter.page(result, clock, source.getPlayer() == null ? null : source.getLevel().dimension().location().toString()).forEach(line -> send(source, line));
            }, false);
        } catch (IllegalArgumentException failure) { source.sendFailure(Component.literal(failure.getMessage())); return 0; }
    }
    private int page(CommandSourceStack source, int number) {
        if (!source.getServer().isSameThread()) return 0;
        var spec = sessions.get(caller(source));
        if (spec.isEmpty()) { source.sendFailure(Component.literal("No saved lookup, or it expired. Run /ch lookup first.")); return 0; }
        var active = runtime.get();
        if (active == null) return unavailable(source);
        return deliver(source, active, active.lookup(spec.get(), number), result -> {
            if (result.rows().isEmpty()) source.sendFailure(Component.literal("Page " + number + " has no results."));
            else ChronicleFormatter.page(result, clock, source.getPlayer() == null ? null : source.getLevel().dimension().location().toString()).forEach(line -> send(source, line));
        }, false);
    }
    private int status(CommandSourceStack source) {
        if (!source.getServer().isSameThread()) return 0;
        var active = runtime.get();
        if (active == null) return unavailable(source);
        return deliver(source, active, active.status(), result -> ChronicleFormatter.status(result).forEach(line -> send(source, line)), true);
    }
    private <T> int deliver(CommandSourceStack source, ChronicleRuntime active, CompletionStage<T> pending,
            Consumer<T> success, boolean status) {
        var server = source.getServer();
        ServerPlayer player = source.getPlayer();
        pending.whenComplete((value, failure) -> server.execute(() -> {
            // Minecraft 1.21.1 execute() can run inline after stop. Check ownership before any live source access.
            if (!server.isSameThread() || server.isStopped() || runtime.get() != active || !source.hasPermission(2)) return;
            if (player != null && (player.hasDisconnected() || server.getPlayerList().getPlayer(player.getUUID()) != player
                    || !player.hasPermissions(2))) return;
            if (failure == null) success.accept(value);
            else {
                if (status) {
                    ChronicleFormatter.runtimeStatus(active.diagnostics()).forEach(line -> send(source, line));
                    send(source, Component.literal("Database totals unavailable."));
                }
                source.sendFailure(Component.literal(error(failure)));
            }
        }));
        // Rejected futures complete inline: overload output reaches the caller without waiting for a server tick.
        return pending.toCompletableFuture().isCompletedExceptionally() ? 0 : 1;
    }
    private static String error(Throwable failure) {
        while ((failure instanceof CompletionException || failure instanceof ExecutionException) && failure.getCause() != null) failure = failure.getCause();
        if (failure instanceof RejectedExecutionException) return "Chronicle queries unavailable or overloaded. Try again shortly.";
        if (failure instanceof StorageFailure storage && storage.kind() == StorageFailure.Kind.TRANSIENT)
            return "Chronicle query unavailable: " + storage.getMessage();
        return "Chronicle query failed; inspect /ch status and the server log. Database totals unavailable.";
    }
    private static void send(CommandSourceStack source, Component text) { source.sendSuccess(() -> text, false); }
    private static int unavailable(CommandSourceStack source) {
        source.sendFailure(Component.literal("Chronicle runtime unavailable.")); return 0;
    }
}
