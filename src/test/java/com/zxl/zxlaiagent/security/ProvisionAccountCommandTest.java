package com.zxl.zxlaiagent.security;

import org.junit.jupiter.api.Test;
import java.io.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;

class ProvisionAccountCommandTest {
    String[] args(){return new String[]{"--subject","synthetic-user","--login","synthetic-login","--tenant","synthetic-tenant","--role","employee"};}
    @Test void validatesAllOptionsBeforeReadingPasswordOrOpeningDatabase(){
        for(String[] invalid:new String[][]{new String[0],{"--password","secret"},{"--subject","x","--subject","y"},{"--subject","x","--login","y","--tenant","t","--role","admin"}}){
            assertEquals(2,ProvisionAccountCommand.run(invalid,()->{fail("No password read");return null;},()->{fail("No database");return null;},new PrintWriter(new StringWriter())));
        }
    }
    @Test void successfulProvisionClearsPasswordAndClosesDatabase(){
        var password="synthetic-secret".toCharArray();var closed=new AtomicBoolean();var calls=new AtomicInteger();var output=new StringWriter();
        assertEquals(0,ProvisionAccountCommand.run(args(),()->password,()->new ProvisionAccountCommand.Port(){
            public void provision(String subject,String login,String secret,String tenant,String role){assertEquals("synthetic-user",subject);assertEquals("employee",role);assertEquals("synthetic-secret",secret);calls.incrementAndGet();}
            public void close(){closed.set(true);}
        },new PrintWriter(output)));
        assertEquals(1,calls.get());assertTrue(closed.get());assertArrayEquals(new char[password.length],password);assertFalse(output.toString().contains("synthetic-secret"));
    }
    @Test void failureNeverPrintsExceptionOrSecretAndStillCloses(){
        var password="synthetic-secret".toCharArray();var closed=new AtomicBoolean();var output=new StringWriter();
        assertEquals(1,ProvisionAccountCommand.run(args(),()->password,()->new ProvisionAccountCommand.Port(){
            public void provision(String s,String l,String p,String t,String r){throw new IllegalStateException("sensitive-driver-message "+p);}
            public void close(){closed.set(true);}
        },new PrintWriter(output)));
        assertTrue(closed.get());assertArrayEquals(new char[password.length],password);assertFalse(output.toString().contains("synthetic-secret"));assertFalse(output.toString().contains("sensitive-driver-message"));
    }
    @Test void invalidPasswordNeverOpensDatabaseAndIsErased(){
        for(String value:new String[]{"short"," ","a".repeat(73)}){var secret=value.toCharArray();assertEquals(2,ProvisionAccountCommand.run(args(),()->secret,()->{fail("No database");return null;},new PrintWriter(new StringWriter())));assertArrayEquals(new char[secret.length],secret);}
    }
}
