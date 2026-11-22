CREATE TABLE token_budget_period (
 tenant_id varchar(150) NOT NULL REFERENCES tenant ON DELETE CASCADE,
 period_id varchar(10) NOT NULL,
 token_limit bigint NOT NULL CHECK(token_limit>0),
 reserved_tokens bigint NOT NULL DEFAULT 0 CHECK(reserved_tokens>=0),
 spent_tokens bigint NOT NULL DEFAULT 0 CHECK(spent_tokens>=0),
 call_limit integer NOT NULL CHECK(call_limit>0),
 accepted_calls integer NOT NULL DEFAULT 0 CHECK(accepted_calls>=0 AND accepted_calls<=call_limit),
 PRIMARY KEY(tenant_id,period_id)
);
ALTER TABLE token_budget_account ADD COLUMN period_id varchar(10);
ALTER TABLE token_budget_account ADD FOREIGN KEY(tenant_id,period_id) REFERENCES token_budget_period(tenant_id,period_id);
