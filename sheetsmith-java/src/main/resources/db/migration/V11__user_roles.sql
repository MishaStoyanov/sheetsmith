-- Roles, added after the metrics rather than before them: everybody who runs this wants to know
-- what it cost, and only the people putting it in front of a team need to say who may change what.
--
-- Three of them. USER cannot touch other accounts. ADMIN manages people and may hand out ADMIN, but
-- cannot take it back — a one-way door, so two administrators cannot demote each other in a loop.
-- SUPERADMIN is the seeded account and the only one that can demote; it is already the account that
-- cannot be deleted, for the same reason: an instance has to keep a way back in.
--
-- The backfill is the part worth reading twice. Today every account manages every other, so setting
-- everyone to USER would take an instance with several people and wake it up with one administrator
-- and everybody else locked out of a screen they had yesterday. An upgrade must not remove
-- authority somebody already had, so existing accounts become ADMIN and only new ones default to
-- USER.

alter table users
    add column role varchar(16);

update users set role = 'ADMIN';

-- The first row by id, which is the seeded administrator on every instance that has not deleted it.
update users
set role = 'SUPERADMIN'
where id = (select min(id) from users);

alter table users
    alter column role set not null;

alter table users
    add constraint ck_users_role check (role in ('USER', 'ADMIN', 'SUPERADMIN'));

-- A default on the column as well as a value for every existing row. A row inserted without a role
-- — by a script, a support fix, a test fixture written before this migration existed — should be
-- the least it can be rather than fail outright, and USER is that.
alter table users
    alter column role set default 'USER';
