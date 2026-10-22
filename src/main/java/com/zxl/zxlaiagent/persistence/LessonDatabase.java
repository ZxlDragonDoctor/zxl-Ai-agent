package com.zxl.zxlaiagent.persistence;

import org.flywaydb.core.Flyway;
import java.sql.*;

/** Own connections explicitly: enabling this lesson never changes the application's default data source. */
public final class LessonDatabase {
    private final String url, user, password;
    public LessonDatabase(String url, String user, String password) {
        this.url = url; this.user = user; this.password = password;
        Flyway.configure().dataSource(url, user, password).locations("classpath:db/lesson9")
                .table("lesson9_schema_history").load().migrate();
    }
    @FunctionalInterface public interface Work<T> { T apply(Connection c) throws SQLException; }
    public <T> T transaction(Work<T> work) {
        try (Connection c = DriverManager.getConnection(url, user, password)) {
            c.setAutoCommit(false);
            try { T result = work.apply(c); c.commit(); return result; }
            catch (SQLException | RuntimeException e) { c.rollback(); throw e; }
        } catch (SQLException e) { throw new IllegalStateException("Lesson 9 database operation failed; transaction rolled back", e); }
    }
    public long now(Connection transaction) throws SQLException {
        // PostgreSQL CURRENT_TIMESTAMP is fixed at transaction start. A separate autocommit
        // clock query observes fresh database time even after this transaction waited for a row lock.
        try (var clock = DriverManager.getConnection(url, user, password); var s = clock.createStatement()) {
            s.setQueryTimeout(5);
            try (var r=s.executeQuery("SELECT CURRENT_TIMESTAMP")) {r.next();return r.getTimestamp(1).getTime();}
        }
    }
}
