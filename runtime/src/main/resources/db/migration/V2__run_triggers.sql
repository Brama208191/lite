alter table runs
    add column source      text not null default 'manual',
    add column trigger_key text,
    add column trigger     jsonb;

create unique index runs_trigger_key on runs (trigger_key) where trigger_key is not null;
create index runs_source_recent on runs (source, created_at desc);
