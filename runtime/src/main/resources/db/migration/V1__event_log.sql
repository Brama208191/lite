create table runs (
    id         uuid primary key,
    task       text        not null,
    model      text        not null,
    status     text        not null,
    created_at timestamptz not null default clock_timestamp(),
    updated_at timestamptz not null default clock_timestamp()
);

create index runs_active on runs (created_at) where status in ('PENDING', 'RUNNING');
create index runs_recent on runs (created_at desc);

create table events (
    run_id      uuid        not null references runs (id),
    seq         int         not null,
    type        text        not null,
    payload     jsonb       not null,
    lease_token bigint      not null,
    worker_id   text,
    created_at  timestamptz not null default clock_timestamp(),
    primary key (run_id, seq)
);

create table leases (
    run_id     uuid primary key references runs (id),
    token      bigint      not null default 0,
    worker_id  text,
    expires_at timestamptz not null default '-infinity'
);

create index leases_held on leases (worker_id) where worker_id is not null;

create table workers (
    id                text primary key,
    started_at        timestamptz not null default clock_timestamp(),
    heartbeat_at      timestamptz not null default clock_timestamp(),
    kill_requested_at timestamptz
);

create table notes (
    id         bigserial primary key,
    key        text        not null,
    line       text        not null,
    run_id     uuid        not null,
    created_at timestamptz not null default clock_timestamp()
);

create index notes_key on notes (key, id);

create function notify_event() returns trigger as $$
begin
    perform pg_notify('keel_events', new.run_id::text || ':' || new.seq);
    return new;
end;
$$ language plpgsql;

create trigger events_notify after insert on events
    for each row execute function notify_event();
