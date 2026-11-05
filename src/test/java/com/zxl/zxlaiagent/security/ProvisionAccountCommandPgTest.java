package com.zxl.zxlaiagent.security;

import org.junit.jupiter.api.Test;
import java.io.*;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class ProvisionAccountCommandPgTest {
    @Test void createsHashedAccountAndDuplicateCannotOverwritePasswordRoleOrCreateAnotherTenant() {
        String id="cli-"+UUID.randomUUID(), other=id+"-other";
        try(var keeper=SecurityDatabase.fromEnvironment()) {
            try {
                String[] create={"--subject",id,"--login",id,"--tenant",id,"--role","employee"};
                var output=new StringWriter();
                assertEquals(0,ProvisionAccountCommand.run(create,()->"synthetic-password".toCharArray(),
                        ()->ProvisionAccountCommand.databasePort(SecurityDatabase.pooledFromEnvironment()),new PrintWriter(output)));
                String[] duplicate={"--subject",id,"--login",id,"--tenant",other,"--role","knowledge-maintainer"};
                assertEquals(1,ProvisionAccountCommand.run(duplicate,()->"synthetic-replacement".toCharArray(),
                        ()->ProvisionAccountCommand.databasePort(SecurityDatabase.pooledFromEnvironment()),new PrintWriter(output)));
                keeper.tx(c->{
                    try(var s=SecurityDatabase.statement(c,"SELECT password_hash FROM subject WHERE subject_id=?",id);var rows=s.executeQuery()){
                        assertTrue(rows.next());assertTrue(rows.getString(1).startsWith("$2"));assertNotEquals("synthetic-password",rows.getString(1));
                    }
                    try(var s=SecurityDatabase.statement(c,"SELECT count(*) FROM tenant WHERE tenant_id=?",other);var rows=s.executeQuery()){rows.next();assertEquals(0,rows.getInt(1));}
                    return null;
                });
                var auth=new AuthenticationService(keeper);var session=auth.login(id,"synthetic-password",id);
                assertEquals("employee",session.actor().role());auth.revoke(session.actor().ref());
                assertThrows(SecurityFailure.class,()->auth.login(id,"synthetic-replacement",id));
                assertFalse(output.toString().contains("synthetic-password"));assertFalse(output.toString().contains("synthetic-replacement"));
            } finally {
                keeper.tx(c->{for(String table:new String[]{"authorization_audit","subject","tenant"}){
                    String key=table.equals("tenant")?"tenant_id":"subject_id";
                    try(var s=SecurityDatabase.statement(c,"DELETE FROM "+table+" WHERE "+key+"=?",id)){s.executeUpdate();}
                }return null;});
            }
        }
    }
}
