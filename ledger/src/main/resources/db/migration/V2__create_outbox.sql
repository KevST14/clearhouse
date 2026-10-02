-- Transactional outbox: events are written in the same transaction as the change they
-- describe, then a publisher copies them to Kafka. A transfer and its event either both
-- commit or neither does.
create table outbox_events (
    id           uuid        primary key,
    -- Insert order. Transfers on the same account insert while holding that account's row
    -- lock, so their events are numbered in the order the transfers happened.
    position     bigint      generated always as identity unique,
    topic        text        not null,
    message_key  text        not null,
    payload      jsonb       not null,
    created_at   timestamptz not null default now(),
    published_at timestamptz
);

create index outbox_events_unpublished_idx on outbox_events (position) where published_at is null;
