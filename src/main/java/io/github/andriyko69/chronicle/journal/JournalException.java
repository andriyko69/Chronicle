/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.andriyko69.chronicle.journal;

public final class JournalException extends RuntimeException {
    public JournalException(String message, Throwable cause) { super(message, cause); }
}
