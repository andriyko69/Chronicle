/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.andriyko69.chronicle.rollback;

import io.github.andriyko69.chronicle.event.AuditEvent;
import io.github.andriyko69.chronicle.event.EventId;
import io.github.andriyko69.chronicle.runtime.ChronicleRuntime;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.Consumer;
import java.util.function.Supplier;
import net.minecraft.server.MinecraftServer;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Executes at most one rollback or restore at a time, on the server thread, in bounded per-tick slices. After each
 * slice the rows it applied are recorded through the persistence writer, and the next slice waits for that commit: a
 * failed record stops the job, so no change is ever applied twice because its state was silently lost.
 */
public final class RollbackEngine {
    private static final Logger LOG = LoggerFactory.getLogger(RollbackEngine.class);
    public static final int OPERATIONS_PER_TICK = 250;
    /** Synchronous chunk loads are the expensive part of a far-reaching rollback. */
    static final int CHUNK_LOADS_PER_TICK = 8;
    /** How long a step waits for its chunk's entities before resolving its storage anyway (and skipping it if absent). */
    static final int ENTITY_WAIT_TICKS = 200;

    /** {@code emptied}: replaced storage that still held items this request did not cover; they were discarded, never dropped. */
    public record Report(boolean restore, int planned, int blocks, int items, int covered, int emptied, Map<String, Integer> skipped, String failure) {
        public int skippedTotal() { return skipped.values().stream().mapToInt(Integer::intValue).sum(); }
    }

    private final class Job {
        final ChronicleRuntime runtime;
        final RollbackPlan plan;
        final Consumer<Report> done;
        final WorldRollback world;
        final Map<String, Integer> skipped = new TreeMap<>();
        int next, blocks, items, covered, emptied, entityWait;
        /** Set when a change was left in an unknown state; the job ends once the slice's applied rows are recorded. */
        String halted;
        CompletableFuture<Void> recording;
        List<EventId> recordingIds = List.of();
        Job(MinecraftServer server, ChronicleRuntime runtime, RollbackPlan plan, Consumer<Report> done) {
            this.runtime = runtime; this.plan = plan; this.done = done; world = new WorldRollback(server);
        }
        void skip(WorldRollback.Skip reason, int rows) { skipped.merge(reason.label, rows, Integer::sum); }
        Report report(String failure) {
            return new Report(plan.restore(), plan.operations(), blocks, items, covered, emptied, Map.copyOf(skipped), failure);
        }
    }

    private final Supplier<ChronicleRuntime> runtime;
    private boolean reserved;
    private Job job;
    private final List<Runnable> settled = new ArrayList<>();

    public RollbackEngine(Supplier<ChronicleRuntime> runtime) { this.runtime = runtime; }

    /** Lowest priority: storage spills and explosion removals settle at this same tick boundary and must be admitted first. */
    public void register() { NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST, (ServerTickEvent.Post event) -> tick(event.getServer())); }

    /**
     * Runs {@code action} at the end of the current tick, once every audit has settled. A command may arrive in the same
     * tick as a container break whose spill rows are only submitted at the tick boundary; planning before then would
     * restore the break's snapshot while leaving those rows un-flagged for a later rollback to insert again.
     */
    public void afterSettle(MinecraftServer server, Runnable action) {
        owned(server);
        settled.add(action);
    }

    /** Claims the single execution slot from command entry until the job finishes, so overlapping plans cannot interleave. */
    public boolean reserve(MinecraftServer server) {
        owned(server);
        if (reserved) return false;
        reserved = true;
        return true;
    }

    public void release(MinecraftServer server) {
        owned(server);
        if (job == null) reserved = false;
    }

    public void start(MinecraftServer server, ChronicleRuntime active, RollbackPlan plan, Consumer<Report> done) {
        owned(server);
        if (!reserved || job != null) throw new IllegalStateException("Rollback started without its reservation");
        job = new Job(server, active, plan, done);
    }


    /** Driven by the server tick; public so a test can step an unregistered engine deterministically. */
    public void tick(MinecraftServer server) {
        if (!server.isSameThread()) return;
        if (!settled.isEmpty()) {
            var actions = List.copyOf(settled);
            settled.clear();
            for (var action : actions) {
                try { action.run(); }
                catch (RuntimeException failure) { LOG.warn("Chronicle failed to start a rollback request", failure); }
            }
        }
        var current = job;
        if (current == null) return;
        if (current.recording != null) {
            if (!current.recording.isDone()) return;
            try { current.recording.join(); }
            catch (CompletionException | java.util.concurrent.CancellationException failure) {
                finish(current, "could not record the rolled-back state of the last " + current.recordingIds.size()
                        + " applied changes (" + cause(failure) + "); they are " + quarantine(current) + "; the remaining changes were not applied");
                return;
            }
            current.recording = null;
        }
        if (runtime.get() != current.runtime) { finish(current, "Chronicle storage restarted; the remaining changes were not applied"); return; }
        var steps = current.plan.steps();
        if (current.next >= steps.size() || current.halted != null) { finish(current, current.halted); return; }
        int budget = OPERATIONS_PER_TICK;
        int loads = CHUNK_LOADS_PER_TICK;
        var recorded = new ArrayList<EventId>();
        while (current.next < steps.size() && budget > 0) {
            var step = steps.get(current.next);
            if (current.world.entitiesPending(step.event()) && current.entityWait++ < ENTITY_WAIT_TICKS) break;
            current.entityWait = 0;
            if (!current.world.loaded(step.event())) {
                if (loads == 0) break;
                loads--;
            }
            current.next++;
            // A break and its spills are one unit: the spills' handling depends on how the break was applied.
            budget -= 1 + step.companions().size();
            try { budget -= apply(current, step, recorded); }
            catch (RuntimeException failure) {
                // Whatever this step changed is unknown; the rows applied before it in this slice are still recorded below.
                var uncertain = new ArrayList<EventId>();
                uncertain.add(step.event().id());
                step.companions().forEach(companion -> uncertain.add(companion.id()));
                uncertain.removeAll(recorded);
                LOG.error("Chronicle {} left {} in an unknown state", current.plan.restore() ? "restore" : "rollback", uncertain, failure);
                current.halted = "change " + step.event().id() + " failed part-way (" + cause(failure) + "); it is "
                        + quarantine(current, uncertain) + "; the remaining changes were not applied";
                break;
            }
        }
        if (recorded.isEmpty()) {
            if (current.next >= steps.size() || current.halted != null) finish(current, current.halted);
            return;
        }
        current.recordingIds = List.copyOf(recorded);
        current.recording = current.runtime.setRolledBack(recorded, !current.plan.restore()).toCompletableFuture();
    }

    /** Returns the extra budget the step consumed beyond its own operations. */
    private int apply(Job current, RollbackPlan.Step step, List<EventId> recorded) {
        boolean restore = current.plan.restore();
        // Checked before the restore clears the container: anything beyond the spill rows was added since the rollback.
        boolean beyondSpills = restore && step.block() && !step.companions().isEmpty() && current.world.exceeds(step.event(), step.companions());
        var outcome = attempt(current, step, restore);
        if (!outcome.applied()) {
            current.skip(outcome.skip(), 1);
            if (!step.companions().isEmpty()) current.skip(WorldRollback.Skip.PAIRED_BREAK_SKIPPED, step.companions().size());
            return 0;
        }
        recorded.add(step.event().id());
        if (step.block()) current.blocks++; else current.items++;
        // Restoring a break removes the contents its spill rows recorded; anything else cleared was not covered.
        if (outcome.discarded() && (!restore || step.companions().isEmpty() || beyondSpills)) {
            current.emptied++;
            var at = step.event().envelope();
            LOG.warn("Chronicle {} at {} {},{},{} cleared storage contents the request did not cover", restore ? "restore" : "rollback",
                    at.worldId(), at.x(), at.y(), at.z());
        }
        if (step.companions().isEmpty()) return outcome.work();
        // A restored break removes the container with exactly the contents its spill rows recorded.
        if (restore) {
            step.companions().forEach(companion -> recorded.add(companion.id()));
            current.covered += step.companions().size();
            return outcome.work();
        }
        if (!outcome.contentsRestored()) {
            for (var companion : step.companions()) {
                var spill = attempt(current, new RollbackPlan.Step(companion, List.of()), false);
                if (!spill.applied()) { current.skip(spill.skip(), 1); continue; }
                recorded.add(companion.id());
                current.items++;
            }
            return outcome.work();
        }
        // The snapshot already holds the spilled contents: inserting the spill rows on top would duplicate every item.
        // Only what the load lost is inserted, and loaded items are never cleared to make room for a rebuild.
        var missing = current.world.missing(step.companions());
        var partial = new ArrayList<EventId>();
        for (int i = 0; i < missing.length; i++) {
            var companion = step.companions().get(i);
            if (missing[i] == 0) { recorded.add(companion.id()); current.covered++; continue; }
            int expected = new io.github.andriyko69.chronicle.codec.ItemTransactionPayloadCodec().decode(companion.payloadVersion(), companion.payload()).count();
            int lost = missing[i];
            var fix = guarded(current, companion, () -> current.world.insertMissing(companion, lost));
            if (fix.applied()) { recorded.add(companion.id()); current.items++; continue; }
            // Some of the row's items came back with the snapshot and the rest cannot be inserted: neither retry nor flag is safe.
            if (lost < expected) { partial.add(companion.id()); current.skip(WorldRollback.Skip.PARTIAL_SPILL, 1); }
            else current.skip(fix.skip(), 1);
        }
        if (!partial.isEmpty()) {
            LOG.warn("Chronicle rollback of {} could only partly restore spills {}; they are {}", step.event().id(), partial, quarantine(current, partial));
        }
        return outcome.work();
    }

    private static WorldRollback.Outcome attempt(Job current, RollbackPlan.Step step, boolean restore) {
        return guarded(current, step.event(), () -> step.block() ? current.world.block(step.event(), restore) : current.world.item(step.event(), restore));
    }

    /** A failure that left nothing changed is an ordinary, retryable skip; an {@link WorldRollback.Uncertain} one halts the job. */
    private static WorldRollback.Outcome guarded(Job current, AuditEvent event, java.util.function.Supplier<WorldRollback.Outcome> change) {
        try { return change.get(); }
        catch (WorldRollback.Uncertain uncertain) { throw uncertain; }
        catch (RuntimeException failure) {
            LOG.warn("Chronicle {} of {} failed without changing the world", current.plan.restore() ? "restore" : "rollback", event.id(), failure);
            return WorldRollback.Outcome.skipped(WorldRollback.Skip.FAILED);
        }
    }

    /** Applied changes whose state was not recorded must never be planned again, or a retry would apply them twice. */
    private static String quarantine(Job current) { return quarantine(current, current.recordingIds); }
    private static String quarantine(Job current, List<EventId> ids) {
        try {
            current.runtime.quarantine(ids, !current.plan.restore());
            return "quarantined in " + current.runtime.quarantineFile() + " and excluded until it is reviewed and removed";
        } catch (java.io.IOException | RuntimeException failure) {
            LOG.error("Chronicle could not quarantine changes in an unknown state {}", ids, failure);
            return "could not be quarantined; their IDs are in the server log and must not be rolled back again";
        }
    }

    private void finish(Job current, String failure) {
        job = null;
        reserved = false;
        var report = current.report(failure);
        String kind = report.restore() ? "Restore" : "Rollback";
        if (failure != null) LOG.warn("Chronicle {} stopped: {} ({} blocks, {} items applied)", kind, failure, report.blocks(), report.items());
        else LOG.info("Chronicle {} complete: {} blocks, {} items, {} spill rows covered, {} skipped {}", kind, report.blocks(), report.items(),
                report.covered(), report.skippedTotal(), report.skipped());
        try { current.done.accept(report); }
        catch (RuntimeException reply) { LOG.warn("Chronicle failed to report a finished {}", kind, reply); }
    }

    /** A queued state record still commits during runtime shutdown; only the unapplied remainder is abandoned. */
    public void stop(MinecraftServer server) {
        owned(server);
        var current = job;
        if (current != null) {
            // The queued record normally commits while the runtime drains; if it is abandoned, its rows are quarantined.
            // A record that already failed, but was not yet observed by a tick, is quarantined right here.
            var pending = current.recording;
            if (pending != null) pending.whenComplete((ignored, failure) -> { if (failure != null) quarantine(current); });
            finish(current, "server stopping; the remaining changes were not applied");
        }
        settled.clear();
        reserved = false;
    }

    private static String cause(Throwable failure) {
        while (failure.getCause() != null && (failure instanceof CompletionException)) failure = failure.getCause();
        return failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage();
    }
    private static void owned(MinecraftServer server) {
        if (!server.isSameThread()) throw new IllegalStateException("Rollback engine accessed off server thread");
    }
}
