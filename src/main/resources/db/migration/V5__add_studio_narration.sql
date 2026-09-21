alter table studio_episodes add column narration_json text;
alter table studio_episodes add column narration_review_json text;
alter table studio_episodes add column narration_model varchar(255);
alter table studio_episodes add column narration_response_id varchar(255);
