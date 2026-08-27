-- The first user, so an instance that turns authentication on has a way in.
--
-- Seeded by migration rather than by code at startup: where the first user came from should be a
-- line in the schema's history, not a side effect of whichever boot happened to run first. The
-- hash is a constant here for the same reason — a value generated at startup differs per install
-- and cannot be reasoned about or reset from a document.
--
-- The hash is bcrypt of the word `admin`, and publishing it in an open repository is not a leak:
-- the password itself is written in the README. That is the deal — a known first password, and a
-- flag that nags until it is changed.
--
-- `must_change_password` is set only on this row. It is what stops `admin`/`admin` quietly
-- surviving an instance being put in front of other people: the app says so at startup and, once
-- there is a login, in the interface.
--
-- `on conflict do nothing` because a user may already have inserted a row called admin by hand.
-- A migration that fails there would refuse to start their app over a row they meant to keep.

alter table users
    add column must_change_password boolean not null default false;

insert into users (name, password_hash, must_change_password)
values ('admin', '$2a$10$VXN.dor9JKKtAZ7xjhernu5kcCmarsDg1L7s.yN5z37tUP/rmWMGu', true)
on conflict (name) do nothing;
