ALTER TABLE membership DROP CONSTRAINT membership_role_check;
ALTER TABLE membership ADD CONSTRAINT membership_role_check
    CHECK (role IN ('employee', 'knowledge-maintainer', 'ticket-approver'));
