-- A per-person ceiling on what may be spent in a calendar month.
--
-- Null means no limit, and that is the default for everybody: an instance that has never thought
-- about budgets must not start refusing work because a column appeared. Existing accounts keep it
-- too — a migration that quietly imposed a limit would be indistinguishable, from the inside, from
-- the application breaking.
--
-- Numeric rather than a float, for the same reason every other money column here is: a budget
-- compared against a total is an equality nobody wants decided by binary rounding.
--
-- What it can and cannot see is worth writing down next to the column itself. Spend is only
-- knowable for a model with a price, so a local model — which costs nothing — and a model nobody
-- has priced both contribute zero to the figure this is checked against. That is not a bug to be
-- fixed here: it is the honest reach of a limit denominated in money, and the interface says so
-- rather than implying a completeness it does not have.

alter table users
    add column monthly_budget numeric(12, 4);

alter table users
    add constraint ck_users_monthly_budget_positive
        check (monthly_budget is null or monthly_budget >= 0);
