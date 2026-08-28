-- Where the spreadsheets live, and how much of them to keep.
--
-- One row, like the LLM settings: this is the instance's own configuration rather than anybody's
-- data, and a table that can hold two answers to "where do the files go" is a table that will.
-- The check constraint is what makes that true rather than merely intended.
--
-- Every column is nullable, and null means "unset" rather than "zero": no root of its own means the
-- directories the instance was started with, and no cap means keep everything until the TTL takes
-- it. A zero here would mean "keep nothing", which is a very different instruction to give by
-- accident.
create table storage_settings
(
    id         smallint primary key default 1 check (id = 1),
    root_dir   text,
    max_files  integer check (max_files is null or max_files > 0),
    max_bytes  bigint check (max_bytes is null or max_bytes > 0),
    updated_at timestamp   not null default now()
);

insert into storage_settings (id) values (1);
