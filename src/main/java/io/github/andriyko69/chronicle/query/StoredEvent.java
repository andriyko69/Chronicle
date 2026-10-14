/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.andriyko69.chronicle.query;

import io.github.andriyko69.chronicle.event.AuditEvent;
import java.util.Objects;

public record StoredEvent(AuditEvent event, boolean rolledBack) {
    public StoredEvent { Objects.requireNonNull(event); }
}
