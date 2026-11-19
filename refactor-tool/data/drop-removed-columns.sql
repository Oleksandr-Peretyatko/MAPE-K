-- Columns and tables that no entity maps any more. Hibernate runs with ddl-auto: update, which
-- never drops anything, so they stay in rt-db until this is run BY HAND:
--
--   docker compose exec -T rt-db psql -U rt -d refactordb -v ON_ERROR_STOP=1 < data/drop-removed-columns.sql
--
-- Run it only after the cleaned-up rt-app has booted and the UI still shows the old iterations,
-- versions and plans. Nothing reads these columns, so there is no hurry and no rollback.

-- AI recommendation feature
ALTER TABLE plan_document
    DROP COLUMN IF EXISTS recommendation_set_id,
    DROP COLUMN IF EXISTS recommendation_index,
    DROP COLUMN IF EXISTS selected_recommendation_json;
ALTER TABLE plan_version
    DROP COLUMN IF EXISTS recommendation_set_id,
    DROP COLUMN IF EXISTS recommendation_index;
ALTER TABLE loop_iteration
    DROP COLUMN IF EXISTS failed_recommendation_index;
DROP TABLE IF EXISTS recommendation_set;

-- Legacy table of the pre-MAPE-K execute phase; never written by current code.
DROP TABLE IF EXISTS refactor_iteration;
