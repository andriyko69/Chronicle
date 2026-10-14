/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.andriyko69.chronicle.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import io.github.andriyko69.chronicle.container.StorageAudit;
import io.github.andriyko69.chronicle.event.ChronicleClock;
import io.github.andriyko69.chronicle.inspect.InspectorSessions;
import io.github.andriyko69.chronicle.persistence.StorageFailure;
import io.github.andriyko69.chronicle.query.*;
import io.github.andriyko69.chronicle.rollback.RollbackEngine;
import io.github.andriyko69.chronicle.rollback.RollbackPlanner;
import io.github.andriyko69.chronicle.runtime.ChronicleRuntime;
import java.util.concurrent.*;
import java.util.function.Consumer;
import java.util.function.Supplier;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class ChronicleCommands {
    private static final Logger LOG = LoggerFactory.getLogger(ChronicleCommands.class);
    private final Supplier<ChronicleRuntime> runtime;
    private final ChronicleClock clock;
    private final LookupParser parser;
    private final LookupSessions sessions;
    private final InspectorSessions inspectors;
    private final RollbackEngine rollbacks;
    public ChronicleCommands(Supplier<ChronicleRuntime> runtime, ChronicleClock clock, InspectorSessions inspectors, RollbackEngine rollbacks) {
        this.runtime = runtime; this.clock = clock; parser = new LookupParser(clock); sessions = new LookupSessions(clock);
        this.inspectors = inspectors; this.rollbacks = rollbacks;
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
                .then(inspect("inspect")).then(inspect("i"))
                .then(rollback("rollback", false)).then(rollback("rb", false))
                .then(rollback("restore", true)).then(rollback("rs", true))
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
    private LiteralArgumentBuilder<CommandSourceStack> inspect(String name) {
        return Commands.literal(name).executes(context -> inspect(context.getSource(), null))
                .then(Commands.literal("on").executes(context -> inspect(context.getSource(), true)))
                .then(Commands.literal("off").executes(context -> inspect(context.getSource(), false)));
    }
    private LiteralArgumentBuilder<CommandSourceStack> rollback(String name, boolean restore) {
        return Commands.literal(name).executes(context -> rollback(context.getSource(), "", restore))
                .then(Commands.argument("query", StringArgumentType.greedyString())
                        .executes(context -> rollback(context.getSource(), StringArgumentType.getString(context, "query"), restore)));
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
    /**
     * Plans off-thread, then executes in server-thread slices. A non-preview request holds the engine's single slot from
     * here until it finishes, so two overlapping requests can never plan against, or mutate, the same rows concurrently.
     */
    private int rollback(CommandSourceStack source, String text, boolean restore) {
        var server = source.getServer();
        if (!server.isSameThread()) return 0;
        LookupParser.Rollback request;
        try {
            var player = source.getPlayer();
            var position = BlockPos.containing(source.getPosition());
            var context = player == null ? LookupParser.Context.console() : new LookupParser.Context(
                    source.getLevel().dimension().location().toString(), new LookupSpec.Center(position.getX(), position.getY(), position.getZ()));
            request = parser.parseRollback(text, context);
        } catch (IllegalArgumentException failure) { source.sendFailure(Component.literal(failure.getMessage())); return 0; }
        var active = runtime.get();
        if (active == null) return unavailable(source);
        boolean apply = !request.preview();
        if (apply && !rollbacks.reserve(server)) {
            source.sendFailure(Component.literal("A Chronicle rollback or restore is already running; try again when it finishes."));
            return 0;
        }
        ServerPlayer player = source.getPlayer();
        rollbacks.afterSettle(server, () -> plan(server, source, player, active, request, restore));
        return 1;
    }
    private void plan(net.minecraft.server.MinecraftServer server, CommandSourceStack source, ServerPlayer player, ChronicleRuntime active,
            LookupParser.Rollback request, boolean restore) {
        boolean apply = !request.preview();
        if (server.isStopped() || runtime.get() != active || !audience(server, source, player)) {
            if (apply) rollbacks.release(server);
            return;
        }
        StorageAudit.checkpointViewers(server);
        active.planRollback(request.spec(), restore).whenComplete((plan, failure) -> server.execute(() -> {
            if (!server.isSameThread()) return;
            boolean live = !server.isStopped() && runtime.get() == active;
            boolean audience = live && audience(server, source, player);
            // A caller who left or lost permission while the plan was computed never mutates the world.
            if (!apply || failure != null || !audience || plan.steps().isEmpty()) {
                if (apply && live) rollbacks.release(server);
                if (!audience) return;
                if (failure != null) source.sendFailure(Component.literal(error(failure)));
                else if (!apply) ChronicleFormatter.rollbackPreview(plan).forEach(line -> send(source, line));
                else ChronicleFormatter.withQuarantine(plan, ChronicleFormatter.nothingTo(restore)).forEach(line -> send(source, line));
                return;
            }
            ChronicleFormatter.withQuarantine(plan, ChronicleFormatter.rollbackStarted(plan)).forEach(line -> send(source, line));
            rollbacks.start(server, active, plan, report -> {
                if (audience(server, source, player)) ChronicleFormatter.rollbackReport(report).forEach(line -> send(source, line));
                else LOG.info("Chronicle {} requested by {} finished after its requester left", restore ? "restore" : "rollback", source.getTextName());
            });
        }));
    }
    private static boolean audience(net.minecraft.server.MinecraftServer server, CommandSourceStack source, ServerPlayer player) {
        if (!source.hasPermission(2)) return false;
        return player == null || !player.hasDisconnected() && server.getPlayerList().getPlayer(player.getUUID()) == player && player.hasPermissions(2);
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
    private int inspect(CommandSourceStack source, Boolean enabled) {
        ServerPlayer player = source.getPlayer();
        if (player == null) { source.sendFailure(Component.literal("The Chronicle inspector requires an in-game player.")); return 0; }
        boolean active = enabled == null ? inspectors.toggle(player.getUUID()) : inspectors.set(player.getUUID(), enabled);
        send(source, ChronicleFormatter.inspector(active));
        return 1;
    }
    /** Runs on the server thread from an inspector click; the lookup itself runs on the query executor. */
    public void inspect(ServerPlayer player, ServerLevel level, BlockPos target) {
        var source = player.createCommandSourceStack();
        String dimension = level.dimension().location().toString();
        var spec = LookupSpec.exact(dimension, new LookupSpec.Center(target.getX(), target.getY(), target.getZ()));
        var active = runtime.get();
        if (active == null) { unavailable(source); return; }
        deliver(source, active, active.lookup(spec, 1), result -> {
            sessions.put(player.getUUID().toString(), spec);
            ChronicleFormatter.inspection(result, clock, dimension, target.getX(), target.getY(), target.getZ()).forEach(line -> send(source, line));
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
            if (!server.isSameThread() || server.isStopped() || runtime.get() != active || !audience(server, source, player)) return;
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
        if (failure instanceof RollbackPlanner.Rejected rejected) return rejected.getMessage();
        if (failure instanceof StorageFailure storage && storage.kind() == StorageFailure.Kind.TRANSIENT)
            return "Chronicle query unavailable: " + storage.getMessage();
        return "Chronicle query failed; inspect /ch status and the server log. Database totals unavailable.";
    }
    private static void send(CommandSourceStack source, Component text) { source.sendSuccess(() -> text, false); }
    private static int unavailable(CommandSourceStack source) {
        source.sendFailure(Component.literal("Chronicle runtime unavailable.")); return 0;
    }
}
