package com.prodigalgal.ircs.notification.mail;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.DriverManager;
import java.sql.Statement;
import java.util.Objects;
import java.util.Scanner;
import org.junit.jupiter.api.Test;

class NotificationMailSendHistorySchemaTest {

    @Test
    void schemaSupportsWriterColumnsAndOpsQueryIndexes() throws Exception {
        String url = "jdbc:h2:mem:notification_mail_send_history_schema;MODE=PostgreSQL;DB_CLOSE_DELAY=-1";
        try (var connection = DriverManager.getConnection(url);
             var statement = connection.createStatement();
             var stream = Objects.requireNonNull(
                     getClass().getResourceAsStream("/db/schema/notification_mail_send_history.sql"))) {
            try (var scanner = new Scanner(stream).useDelimiter("\\A")) {
                String ddl = scanner.hasNext() ? scanner.next() : "";
                assertTrue(ddl.contains("TIMESTAMPTZ"));
                ddl = ddl.replace("TIMESTAMPTZ", "TIMESTAMP");
                try (var ddlScanner = new Scanner(ddl)) {
                    ddlScanner.useDelimiter(";");
                    while (ddlScanner.hasNext()) {
                        execute(statement, ddlScanner.next());
                    }
                }
            }

            try (var columns = connection.getMetaData()
                    .getColumns(null, null, "NOTIFICATION_MAIL_SEND_HISTORY", null)) {
                int count = 0;
                while (columns.next()) {
                    count++;
                }
                assertEquals(14, count);
            }

            try (var indexes = connection.getMetaData()
                    .getIndexInfo(null, null, "NOTIFICATION_MAIL_SEND_HISTORY", false, false)) {
                boolean hasCreatedIndex = false;
                boolean hasStatusModeIndex = false;
                boolean hasTemplateIndex = false;
                boolean hasAuditClassIndex = false;
                while (indexes.next()) {
                    String name = indexes.getString("INDEX_NAME");
                    hasCreatedIndex |= "IDX_NOTIFICATION_MAIL_SEND_HISTORY_CREATED".equalsIgnoreCase(name);
                    hasStatusModeIndex |= "IDX_NOTIFICATION_MAIL_SEND_HISTORY_STATUS_MODE_CREATED"
                            .equalsIgnoreCase(name);
                    hasTemplateIndex |= "IDX_NOTIFICATION_MAIL_SEND_HISTORY_TEMPLATE_CREATED"
                            .equalsIgnoreCase(name);
                    hasAuditClassIndex |= "IDX_NOTIFICATION_MAIL_SEND_HISTORY_AUDIT_CLASS_CREATED"
                            .equalsIgnoreCase(name);
                }
                assertTrue(hasCreatedIndex);
                assertTrue(hasStatusModeIndex);
                assertTrue(hasTemplateIndex);
                assertTrue(hasAuditClassIndex);
            }

            try (var outboxTables = connection.getMetaData()
                    .getTables(null, null, "AUDIT_ES_REPLICATION_OUTBOX", null);
                 var archiveTables = connection.getMetaData()
                         .getTables(null, null, "AUDIT_ARCHIVE_ENTRIES", null)) {
                assertTrue(!outboxTables.next());
                assertTrue(archiveTables.next());
            }
        }
    }

    private static void execute(Statement statement, String sql) throws Exception {
        String trimmed = sql.trim();
        if (!trimmed.isEmpty()) {
            statement.execute(trimmed);
        }
    }
}
