create table effects (
    id           bigserial primary key,
    idem_key     text        not null unique,
    kind         text        not null,
    reference    text        not null,
    payload      jsonb       not null,
    payload_hash text        not null,
    response     jsonb       not null,
    applied_at   timestamptz not null default clock_timestamp()
);

create index effects_reference on effects (kind, reference);

create table requests (
    id          bigserial primary key,
    idem_key    text        not null,
    kind        text        not null,
    reference   text        not null,
    outcome     text        not null,
    received_at timestamptz not null default clock_timestamp()
);

create index requests_key on requests (idem_key);
create index requests_reference on requests (reference);
