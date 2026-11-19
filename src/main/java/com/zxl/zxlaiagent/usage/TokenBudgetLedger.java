package com.zxl.zxlaiagent.usage;

import com.zxl.zxlaiagent.security.SecurityDatabase;
import java.sql.*;
import java.util.Objects;

/** Trusted server-side accounting primitive. No HTTP authorization or monetary billing is implied.
 * Accepted calls count reservations permanently, including calls released before dispatch.
 * UNKNOWN is conservative and terminal; reconciliation is a separate operator workflow.
 */
public final class TokenBudgetLedger {
    private final SecurityDatabase db;
    private final String tenant;
    public record Account(long limit,long reserved,long spent,int callLimit,int calls) {}
    private record Entry(long estimate,String state,Long actual) {}
    public TokenBudgetLedger(SecurityDatabase db,String tenant) {
        this.db=Objects.requireNonNull(db);
        this.tenant=id(tenant);
    }

    public void createAccount(String account,long tokens,int calls) {
        id(account);
        if(tokens<1||calls<1)throw new IllegalArgumentException("Positive policy required");
        db.tx(c-> {
            try(var s=SecurityDatabase.statement(c,"INSERT INTO token_budget_account(tenant_id,account_id,token_limit,call_limit) VALUES (?,?,?,?)",tenant,account,tokens,calls)) {
                s.executeUpdate();
            }
            return null;
        });
    }

    public Account account(String account) {
        id(account);
        return db.tx(c->read(c,account,false));
    }

    public void reserve(String account,String call,long estimate) {
        id(account);
        id(call);
        if(estimate<1)throw new IllegalArgumentException("Positive estimate required");
        db.tx(c-> {
            var a=read(c,account,true);
            var e=entry(c,account,call);
            if(e!=null) {
                if(e.estimate()!=estimate)throw denied("CALL_BINDING_MISMATCH");
                return null;
            }
            if(a.calls()>=a.callLimit()||a.spent()>a.limit()||a.reserved()>a.limit()-a.spent()||estimate>a.limit()-a.spent()-a.reserved())throw denied("BUDGET_EXHAUSTED");
            try(var s=SecurityDatabase.statement(c,"INSERT INTO token_budget_call(tenant_id,account_id,call_id,estimate,state) VALUES (?,?,?,?,'RESERVED')",tenant,account,call,estimate)) {
                s.executeUpdate();
            }
            try(var s=SecurityDatabase.statement(c,"UPDATE token_budget_account SET reserved_tokens=reserved_tokens+?,accepted_calls=accepted_calls+1 WHERE tenant_id=? AND account_id=?",estimate,tenant,account)) {
                s.executeUpdate();
            }
            return null;
        });
    }

    /** Only the caller receiving true may start a new provider request. */
    public boolean dispatch(String account,String call) {
        return transition(account,call,"DISPATCHED",null);
    }

    public void unknown(String account,String call) {
        transition(account,call,"UNKNOWN",null);
    }

    public void releaseUnsent(String account,String call) {
        transition(account,call,"RELEASED",null);
    }

    public void settle(String account,String call,long actual) {
        if(actual<0)throw new IllegalArgumentException("Nonnegative provider usage required");
        transition(account,call,"SETTLED",actual);
    }

    private boolean transition(String account,String call,String target,Long actual) {
        id(account);
        id(call);
        return db.tx(c-> {
            read(c,account,true);
            var e=entry(c,account,call);
            if(e==null)throw denied("CALL_NOT_FOUND");
            if(e.state().equals(target)) {
                if(!Objects.equals(e.actual(),actual))throw denied("USAGE_REPLAY_MISMATCH");
                return false;
            }
            String required=target.equals("DISPATCHED")||target.equals("RELEASED")?"RESERVED":"DISPATCHED";
            if(!e.state().equals(required))throw denied("INVALID_BUDGET_TRANSITION");
            if(target.equals("SETTLED")||target.equals("RELEASED"))try(var s=SecurityDatabase.statement(c,"UPDATE token_budget_account SET reserved_tokens=reserved_tokens-?,spent_tokens=spent_tokens+? WHERE tenant_id=? AND account_id=?",e.estimate(),actual==null?0L:actual,tenant,account)) {
                s.executeUpdate();
            }
            try(var s=SecurityDatabase.statement(c,"UPDATE token_budget_call SET state=?,actual=? WHERE tenant_id=? AND account_id=? AND call_id=?",target,actual,tenant,account,call)) {
                s.executeUpdate();
            }
            return true;
        });
    }

    private Account read(Connection c,String account,boolean lock)throws SQLException {
        try(var s=SecurityDatabase.statement(c,"SELECT token_limit,reserved_tokens,spent_tokens,call_limit,accepted_calls FROM token_budget_account WHERE tenant_id=? AND account_id=?"+(lock?" FOR UPDATE":""),tenant,account);
        var r=s.executeQuery()) {
            if(!r.next())throw denied("ACCOUNT_NOT_FOUND");
            return new Account(r.getLong(1),r.getLong(2),r.getLong(3),r.getInt(4),r.getInt(5));
        }
    }

    private Entry entry(Connection c,String account,String call)throws SQLException {
        try(var s=SecurityDatabase.statement(c,"SELECT estimate,state,actual FROM token_budget_call WHERE tenant_id=? AND account_id=? AND call_id=?",tenant,account,call);
        var r=s.executeQuery()) {
            if(!r.next())return null;
            return new Entry(r.getLong(1),r.getString(2),(Long)r.getObject(3));
        }
    }

    private static String id(String value) {
        if(value==null||value.isBlank()||value.length()>150||value.chars().anyMatch(Character::isISOControl))throw new IllegalArgumentException("Valid server identity required");
        return value;
    }

    private static IllegalStateException denied(String code) {
        return new IllegalStateException(code);
    }
}
