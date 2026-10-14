/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.andriyko69.chronicle.rollback;

import io.github.andriyko69.chronicle.event.AuditEvent;
import java.util.List;
import java.util.Objects;

/**
 * Detached audit rows in application order; world objects are resolved only when a step runs on the server thread.
 * {@code quarantined} counts matching rows left out because an interrupted rollback may already have applied them.
 */
public record RollbackPlan(boolean restore, List<Step> steps, int quarantined) {
    /** A spill's {@code companions} are the ITEM_TAKE rows that recorded the contents a container break released. */
    public record Step(AuditEvent event, List<AuditEvent> companions) {
        public Step {
            Objects.requireNonNull(event);
            companions = List.copyOf(companions);
        }
        public boolean block() { return RollbackPlanner.block(event); }
    }
    public RollbackPlan { steps = List.copyOf(steps); }
    public RollbackPlan(boolean restore, List<Step> steps) { this(restore, steps, 0); }
    public int blockChanges() { return (int)steps.stream().filter(Step::block).count(); }
    public int itemChanges() { return (int)steps.stream().filter(step -> !step.block()).count() + spillRows(); }
    public int spillRows() { return steps.stream().mapToInt(step -> step.companions().size()).sum(); }
    public int operations() { return steps.size() + spillRows(); }
}
