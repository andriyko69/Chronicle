/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.andriyko69.chronicle.persistence;

public final class StorageFailure extends Exception {
    public enum Kind { TRANSIENT, PERMANENT_EVENT, FATAL }
    private final Kind kind;
    private final String backendCode;
    public StorageFailure(Kind kind, String message, String backendCode, Throwable cause) {
        super(message, cause); this.kind = kind; this.backendCode = backendCode;
    }
    public Kind kind() { return kind; }
    public String backendCode() { return backendCode; }
}
