ALTER TABLE agent_runs
    ADD COLUMN model_provider VARCHAR(128),
    ADD COLUMN model_requested VARCHAR(512),
    ADD COLUMN pricing_profile VARCHAR(200);

-- A new binary could have written TaskEnvelope 1.1 while a rolling deployment
-- still exposed the V4 schema. Backfill only those explicitly versioned rows.
-- Historical 1.0 JSON, Bundle bytes and bundle_hash are deliberately untouched.
UPDATE agent_runs
SET model_provider = task_envelope ->> 'modelProvider',
    model_requested = task_envelope ->> 'modelRequested',
    pricing_profile = task_envelope ->> 'pricingProfile'
WHERE task_envelope ->> 'schemaVersion' = '1.1';

ALTER TABLE agent_runs
    ADD CONSTRAINT agent_runs_model_binding_valid CHECK (
        (
            (
                jsonb_typeof(task_envelope -> 'schemaVersion') = 'string'
                AND task_envelope ->> 'schemaVersion' = '1.0'
                AND model_provider IS NULL
                AND model_requested IS NULL
                AND pricing_profile IS NULL
                AND COALESCE(
                    jsonb_typeof(task_envelope -> 'modelProvider'), 'null'
                ) = 'null'
                AND COALESCE(
                    jsonb_typeof(task_envelope -> 'modelRequested'), 'null'
                ) = 'null'
                AND COALESCE(
                    jsonb_typeof(task_envelope -> 'pricingProfile'), 'null'
                ) = 'null'
            )
            OR
            (
                jsonb_typeof(task_envelope -> 'schemaVersion') = 'string'
                AND task_envelope ->> 'schemaVersion' = '1.1'
                AND jsonb_typeof(task_envelope -> 'modelProvider') = 'string'
                AND jsonb_typeof(task_envelope -> 'modelRequested') = 'string'
                AND jsonb_typeof(task_envelope -> 'pricingProfile') = 'string'
                AND model_provider = task_envelope ->> 'modelProvider'
                AND model_requested = task_envelope ->> 'modelRequested'
                AND pricing_profile = task_envelope ->> 'pricingProfile'
                AND model_provider ~ '^[a-z][a-z0-9._-]{0,127}$'
                AND char_length(model_requested) BETWEEN 1 AND 512
                AND model_requested ~ '^[A-Za-z0-9][A-Za-z0-9._~:/-]*$'
                AND pricing_profile ~ '^[a-z][a-z0-9._-]{0,199}$'
            )
        ) IS TRUE
    );
