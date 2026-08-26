-- What a run cost and who asked for it. The timings were already here (created_at,
-- processing_started_at, processing_finished_at); these are the two things missing.
--
-- Tokens are three columns rather than one because a paid provider charges different prices for
-- what it read and what it wrote, so a single total cannot answer "why was that expensive".
-- They are nullable: a local model reports usage it does not bill for, and some report none at all.
--
-- user_id is nullable and clears rather than cascades. Every row already in the table predates the
-- users table and has no owner, and deleting a person must not delete the record that a run
-- happened — an audit that disappears when someone leaves is not an audit.

alter table job_records
    add column prompt_tokens     bigint,
    add column completion_tokens bigint,
    add column total_tokens      bigint,
    add column user_id           bigint;

alter table job_records
    add constraint fk_job_records_user foreign key (user_id) references users (id) on delete set null;

create index ix_job_records_user on job_records (user_id);
