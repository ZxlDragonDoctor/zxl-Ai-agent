package com.zxl.zxlaiagent.security;

import org.junit.jupiter.api.*;
import java.sql.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SecurityDatabaseIdentityPgTest {
    static Driver registered;
    @BeforeAll static void registerPgDriverBeforeStaticMocks()throws SQLException {
        registered=new org.postgresql.Driver();DriverManager.registerDriver(registered);
    }
    @AfterAll static void unregisterTestDriver()throws SQLException {DriverManager.deregisterDriver(registered);}
    String url(){return System.getenv("LESSON12_DB_URL");}
    String user(){return System.getenv("LESSON12_DB_USER");}
    String password(){return System.getenv("LESSON12_DB_PASSWORD");}

    @Test void firstBudgetedCheckNeverAcquiresAnotherConnection()throws Exception {
        var authority=new SecurityDatabase(url(),user(),password());
        var prohibited=new SQLException("Unbudgeted cold request connection must never be acquired");
        try(var candidate=DriverManager.getConnection(url(),user(),password())){
            candidate.setAutoCommit(false);candidate.setNetworkTimeout(Runnable::run,100);
            try(var connections=mockStatic(DriverManager.class)){
                connections.when(()->DriverManager.getConnection(url(),user(),password()))
                    .thenThrow(prohibited);
                assertDoesNotThrow(()->authority.requireSameDatabase(candidate));
                connections.verifyNoInteractions();
            }
            candidate.rollback();
        }
    }

    @Test void identityInitializationFailurePreventsAuthorityStartup() {
        var unavailable=new SQLException("synthetic-startup-identity-failure");
        try(var connections=mockStatic(DriverManager.class)){
            connections.when(()->DriverManager.getConnection(url(),user(),password()))
                .thenThrow(unavailable);
            var failure=assertThrows(SecurityFailure.class,()->new SecurityDatabase(url(),user(),password()));
            assertEquals("AUTHORITY_UNAVAILABLE",failure.code());
        }
    }
}
