ALTER TABLE content_jobs
    ADD COLUMN artifact_path VARCHAR(500),
    ADD COLUMN render_command TEXT,
    ADD COLUMN rendered_at TIMESTAMPTZ;
