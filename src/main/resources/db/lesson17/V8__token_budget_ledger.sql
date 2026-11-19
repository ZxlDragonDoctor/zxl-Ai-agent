CREATE TABLE token_budget_account (
 tenant_id varchar(150) NOT NULL REFERENCES tenant ON DELETE CASCADE,
 account_id varchar(150) NOT NULL,
 token_limit bigint NOT NULL CHECK(token_limit>0),
 reserved_tokens bigint NOT NULL DEFAULT 0 CHECK(reserved_tokens>=0),
 spent_tokens bigint NOT NULL DEFAULT 0 CHECK(spent_tokens>=0),
 call_limit integer NOT NULL CHECK(call_limit>0),
 accepted_calls integer NOT NULL DEFAULT 0 CHECK(accepted_calls>=0 AND accepted_calls<=call_limit),
 PRIMARY KEY(tenant_id,account_id)
);
CREATE TABLE token_budget_call (
 tenant_id varchar(150) NOT NULL, account_id varchar(150) NOT NULL, call_id varchar(150) NOT NULL,
 estimate bigint NOT NULL CHECK(estimate>0),
 actual bigint CHECK(actual>=0),
 state varchar(20) NOT NULL CHECK(state IN ('RESERVED','DISPATCHED','UNKNOWN','SETTLED','RELEASED')),
 PRIMARY KEY(tenant_id,account_id,call_id),
 FOREIGN KEY(tenant_id,account_id) REFERENCES token_budget_account ON DELETE CASCADE,
 CHECK((state='SETTLED' AND actual IS NOT NULL) OR (state<>'SETTLED' AND actual IS NULL))
);
