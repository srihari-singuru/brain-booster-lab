create table studio_episodes (
    id uuid primary key,
    version bigint not null default 0,
    brief text not null,
    status varchar(255) not null,
    spec_json text,
    review_json text,
    last_error text,
    script_model varchar(255),
    response_id varchar(255),
    created_at timestamptz not null,
    approved_at timestamptz
);
alter table content_jobs add column last_error text;
