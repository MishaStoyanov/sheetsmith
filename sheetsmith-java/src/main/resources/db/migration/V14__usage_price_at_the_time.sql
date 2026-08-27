-- What a call actually cost, as of when it was made.
--
-- Until now spend was worked out on every read from the *current* price list, which means editing a
-- price silently rewrote history: correct today's figure for gpt-4o and last March's chart moves
-- with it. That is the same fault as any other number that changes without anybody deciding it
-- should, and it is worse here because the whole point of this table is to be an audit.
--
-- The two rates rather than the total. A stored total cannot explain itself — you cannot tell a
-- cheap call from a mispriced one — while the rates can be read, checked and multiplied out again.
-- The arithmetic stays in one place; only its inputs stop moving.
--
-- Null means the model had no price when the call was made, which is not the same as free. Local
-- runs are null for the same reason they always were: they bill nothing, so there is no rate.

alter table llm_usage
    add column input_per_million  numeric(12, 4),
    add column output_per_million numeric(12, 4);

-- Existing rows are frozen at today's prices rather than left to keep drifting. It is a guess —
-- nobody recorded what these cost at the time — but it is the best one available, and making it
-- once is better than making a new one every time somebody edits the price list. Rows whose model
-- is unpriced stay null: unknown, and honestly so.
update llm_usage u
set input_per_million  = p.input_per_million,
    output_per_million = p.output_per_million
from model_prices p
where upper(u.provider) = upper(p.provider)
  and u.model = p.model
  and u.provider_mode = 'CLOUD';
