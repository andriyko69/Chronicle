/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.andriyko69.chronicle.ingest;

import io.github.andriyko69.chronicle.journal.JournalPosition;
import java.util.concurrent.CompletionStage;

/** ACCEPTED means bounded memory admission; journaled completes only after successful sync. */
public record Submission(Status status, CompletionStage<JournalPosition> journaled) {
    public enum Status { ACCEPTED, NOT_READY, QUEUE_FULL, STOPPED, UNHEALTHY }
}
