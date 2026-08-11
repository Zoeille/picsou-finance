-- V77: merge the duplicate accounts a missing guard created.
--
-- WalletSyncService.resolveAccount was the one connector that did not check
-- existsSoftDeletedByExternalAccountIdAndMemberId before inserting, so every scheduled resync
-- of a wallet whose account the user had deleted inserted a brand new row for the same
-- external_account_id -- one duplicate per deletion, each starting its balance history over.
-- The guard is now in place; this repairs the rows already written.
--
-- Scope is deliberately narrow: only synced accounts (is_manual = false) sharing one
-- external_account_id within one member. external_account_id is free text on manual accounts
-- (see V75), so manual rows are never touched, whatever they happen to contain.
--
-- Survivor: the live row when there is one, else the most recently created. Everything the
-- losers own is re-pointed at it, then the loser rows go. Not touched, because only manual
-- account types can own them and those are excluded above: real_estate_metadata, debt,
-- property_valuation. If one somehow existed, the final DELETE would fail on its foreign key
-- and abort this migration whole -- a loud stop with nothing changed, which beats guessing.

CREATE TEMP TABLE account_merge_map ON COMMIT DROP AS
WITH duplicated AS (
    SELECT member_id, external_account_id
      FROM account
     WHERE external_account_id IS NOT NULL
       AND is_manual = false
     GROUP BY member_id, external_account_id
    HAVING count(*) > 1
),
ranked AS (
    SELECT a.id,
           first_value(a.id) OVER w AS survivor_id,
           row_number()      OVER w AS rank
      FROM account a
      JOIN duplicated d
        ON d.member_id = a.member_id
       AND d.external_account_id = a.external_account_id
    WINDOW w AS (
        PARTITION BY a.member_id, a.external_account_id
        -- Live row first; among equals, the most recent. id breaks the remaining ties so the
        -- ordering is total and the result reproducible.
        ORDER BY (a.deleted_at IS NULL) DESC, a.created_at DESC, a.id DESC
    )
)
SELECT id AS loser_id, survivor_id, rank
  FROM ranked
 WHERE id <> survivor_id;

CREATE INDEX ON account_merge_map(loser_id);

-- balance_snapshot -- UNIQUE (account_id, date)
DELETE FROM balance_snapshot s USING account_merge_map m
 WHERE s.account_id = m.loser_id
   AND EXISTS (SELECT 1 FROM balance_snapshot k
                WHERE k.account_id = m.survivor_id AND k.date = s.date);
DELETE FROM balance_snapshot s USING account_merge_map m
 WHERE s.account_id = m.loser_id
   AND EXISTS (SELECT 1 FROM balance_snapshot k JOIN account_merge_map n ON n.loser_id = k.account_id
                WHERE n.survivor_id = m.survivor_id AND k.date = s.date
                  AND (n.rank, k.id) < (m.rank, s.id));
UPDATE balance_snapshot s SET account_id = m.survivor_id
  FROM account_merge_map m WHERE s.account_id = m.loser_id;

-- account_holding -- UNIQUE (account_id, ticker)
DELETE FROM account_holding h USING account_merge_map m
 WHERE h.account_id = m.loser_id
   AND EXISTS (SELECT 1 FROM account_holding k
                WHERE k.account_id = m.survivor_id AND k.ticker = h.ticker);
DELETE FROM account_holding h USING account_merge_map m
 WHERE h.account_id = m.loser_id
   AND EXISTS (SELECT 1 FROM account_holding k JOIN account_merge_map n ON n.loser_id = k.account_id
                WHERE n.survivor_id = m.survivor_id AND k.ticker = h.ticker
                  AND (n.rank, k.id) < (m.rank, h.id));
UPDATE account_holding h SET account_id = m.survivor_id
  FROM account_merge_map m WHERE h.account_id = m.loser_id;

-- account_ownership -- UNIQUE (account_id, member_id)
DELETE FROM account_ownership o USING account_merge_map m
 WHERE o.account_id = m.loser_id
   AND EXISTS (SELECT 1 FROM account_ownership k
                WHERE k.account_id = m.survivor_id AND k.member_id = o.member_id);
DELETE FROM account_ownership o USING account_merge_map m
 WHERE o.account_id = m.loser_id
   AND EXISTS (SELECT 1 FROM account_ownership k JOIN account_merge_map n ON n.loser_id = k.account_id
                WHERE n.survivor_id = m.survivor_id AND k.member_id = o.member_id
                  AND (n.rank, k.id) < (m.rank, o.id));
UPDATE account_ownership o SET account_id = m.survivor_id
  FROM account_merge_map m WHERE o.account_id = m.loser_id;

-- crypto_exchange_position -- UNIQUE (account_id, product, ticker)
DELETE FROM crypto_exchange_position p USING account_merge_map m
 WHERE p.account_id = m.loser_id
   AND EXISTS (SELECT 1 FROM crypto_exchange_position k
                WHERE k.account_id = m.survivor_id AND k.product = p.product AND k.ticker = p.ticker);
DELETE FROM crypto_exchange_position p USING account_merge_map m
 WHERE p.account_id = m.loser_id
   AND EXISTS (SELECT 1 FROM crypto_exchange_position k JOIN account_merge_map n ON n.loser_id = k.account_id
                WHERE n.survivor_id = m.survivor_id AND k.product = p.product AND k.ticker = p.ticker
                  AND (n.rank, k.id) < (m.rank, p.id));
UPDATE crypto_exchange_position p SET account_id = m.survivor_id
  FROM account_merge_map m WHERE p.account_id = m.loser_id;

-- goal_account -- PRIMARY KEY (goal_id, account_id). A goal already linked to the survivor
-- simply keeps that link; the duplicate one is redundant, not information.
DELETE FROM goal_account g USING account_merge_map m
 WHERE g.account_id = m.loser_id
   AND EXISTS (SELECT 1 FROM goal_account k
                WHERE k.account_id = m.survivor_id AND k.goal_id = g.goal_id);
DELETE FROM goal_account g USING account_merge_map m
 WHERE g.account_id = m.loser_id
   AND EXISTS (SELECT 1 FROM goal_account k JOIN account_merge_map n ON n.loser_id = k.account_id
                WHERE n.survivor_id = m.survivor_id AND k.goal_id = g.goal_id
                  AND n.rank < m.rank);
UPDATE goal_account g SET account_id = m.survivor_id
  FROM account_merge_map m WHERE g.account_id = m.loser_id;

-- transaction -- no uniqueness to reconcile, every row moves.
UPDATE transaction t SET account_id = m.survivor_id
  FROM account_merge_map m WHERE t.account_id = m.loser_id;

DELETE FROM account a USING account_merge_map m WHERE a.id = m.loser_id;
