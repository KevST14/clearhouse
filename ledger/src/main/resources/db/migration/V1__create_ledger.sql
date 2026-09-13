-- Balances are cached on the account row for fast reads, but the ledger entries are
-- the source of truth: an account's balance must always equal the sum of its entries.
create table accounts (
    id            uuid        primary key,
    owner_name    text        not null,
    kind          text        not null check (kind in ('CUSTOMER', 'EXTERNAL')),
    currency      text        not null check (currency in ('GBP', 'EUR', 'USD')),
    balance_minor bigint      not null default 0,
    version       bigint      not null default 0,
    created_at    timestamptz not null,
    -- Only the external account (money entering or leaving the system) may go negative.
    constraint accounts_no_customer_overdraft check (kind = 'EXTERNAL' or balance_minor >= 0)
);

create unique index accounts_one_external_per_currency on accounts (currency) where kind = 'EXTERNAL';

create table transfers (
    id              uuid        primary key,
    idempotency_key text        not null unique,
    from_account_id uuid        not null references accounts (id),
    to_account_id   uuid        not null references accounts (id),
    amount_minor    bigint      not null check (amount_minor > 0),
    currency        text        not null,
    created_at      timestamptz not null,
    constraint transfers_distinct_accounts check (from_account_id <> to_account_id)
);

-- Append-only: every transfer writes one negative and one positive entry that sum to zero.
create table ledger_entries (
    id           uuid        primary key,
    transfer_id  uuid        not null references transfers (id),
    account_id   uuid        not null references accounts (id),
    amount_minor bigint      not null check (amount_minor <> 0),
    created_at   timestamptz not null
);

create index ledger_entries_account_idx on ledger_entries (account_id, created_at);
create index ledger_entries_transfer_idx on ledger_entries (transfer_id);

-- One external account per currency, with a well-known id ending in the ISO 4217 numeric code.
insert into accounts (id, owner_name, kind, currency, created_at) values
    ('00000000-0000-0000-0000-000000000826', 'External (GBP)', 'EXTERNAL', 'GBP', now()),
    ('00000000-0000-0000-0000-000000000978', 'External (EUR)', 'EXTERNAL', 'EUR', now()),
    ('00000000-0000-0000-0000-000000000840', 'External (USD)', 'EXTERNAL', 'USD', now());
