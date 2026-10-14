/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.andriyko69.chronicle.causality;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** The player ultimately responsible for an environmental chain, detached from any live entity. */
public record CausalActor(UUID uuid, String name, Instant since) {
    public CausalActor {
        Objects.requireNonNull(uuid); Objects.requireNonNull(name); Objects.requireNonNull(since);
    }
}
