ALTER TABLE content_jobs
    ADD COLUMN script_text TEXT,
    ADD COLUMN generation_model VARCHAR(120),
    ADD COLUMN generation_response_id VARCHAR(120),
    ADD COLUMN input_tokens INTEGER,
    ADD COLUMN output_tokens INTEGER;
