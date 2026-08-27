-- Which engine answered. The token columns from V3 say what a run cost; without these they cannot
-- say what it cost *of what* — 40 000 tokens is a rounding error on a local model and real money on
-- a cloud one, and the price list an analytics view needs is keyed by the model name.
--
-- Two columns rather than three: the provider (OPENAI, GEMINI, ...) is not stored, because pricing
-- is per model and the model name is what a price list is keyed on. Grouping by vendor means
-- reading it off the model name.
--
-- Both nullable, and deliberately not backfilled: every row already in the table ran before anyone
-- was recording this, and a guessed value in an audit column is worse than an empty one — the
-- current settings are not evidence of what last week's run used.

alter table job_records
    add column provider_mode varchar(16),
    add column model         varchar(255);
