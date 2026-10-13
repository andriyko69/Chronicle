/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.andriyko69.chronicle.diagnostics;

public record StorageTotals(long persistedEvents, long deadLetters) {}
