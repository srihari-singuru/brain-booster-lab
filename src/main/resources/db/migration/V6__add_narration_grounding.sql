alter table studio_episodes add column narration_grounding_json text;
alter table studio_episodes add column narration_grounding_model varchar(255);
alter table studio_episodes add column narration_grounding_response_id varchar(255);
