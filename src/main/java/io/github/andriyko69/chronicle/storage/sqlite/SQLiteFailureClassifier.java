/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.andriyko69.chronicle.storage.sqlite;

import io.github.andriyko69.chronicle.persistence.StorageFailure;
import java.sql.SQLException;
import org.sqlite.SQLiteException;

public final class SQLiteFailureClassifier {
    private SQLiteFailureClassifier() {}
    public static StorageFailure classify(SQLException failure) {
        String code = failure instanceof SQLiteException sqlite ? sqlite.getResultCode().name() : "JDBC_" + failure.getErrorCode();
        StorageFailure.Kind kind;
        if (code.startsWith("SQLITE_BUSY") || code.startsWith("SQLITE_LOCKED")
                || code.equals("SQLITE_CANTOPEN") || code.equals("SQLITE_IOERR")
                || failure instanceof java.sql.SQLTransientConnectionException
                || failure instanceof java.sql.SQLRecoverableException
                || (failure.getSQLState() != null && failure.getSQLState().startsWith("08"))) {
            kind = StorageFailure.Kind.TRANSIENT;
        } else if (code.equals("SQLITE_CONSTRAINT_CHECK") || code.equals("SQLITE_CONSTRAINT_NOTNULL")
                || code.equals("SQLITE_CONSTRAINT_DATATYPE")) {
            kind = StorageFailure.Kind.PERMANENT_EVENT;
        } else kind = StorageFailure.Kind.FATAL;
        return new StorageFailure(kind, failure.getMessage(), code, failure);
    }
}
