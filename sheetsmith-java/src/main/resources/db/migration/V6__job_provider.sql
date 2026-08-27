-- Who was paid. V5 said LOCAL/CLOUD and which model ran; that is enough to price a run but not to
-- group runs by vendor — "how much went to OpenAI versus Gemini" would otherwise have to be read
-- off the model name, which is a convention rather than a fact and goes stale with every rename.
--
-- OLLAMA for a local run, so the column always answers rather than being null half the time: the
-- vendor of a local run is the runtime, and a chart with a hole in it where local runs should be
-- is harder to read than one with a named slice.
--
-- Nullable and not backfilled, for the same reason as V5: rows that predate the column have no
-- honest answer, and the current settings are not evidence of what an old run used.

alter table job_records
    add column provider varchar(32);
