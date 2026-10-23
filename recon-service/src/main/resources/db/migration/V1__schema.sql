-- Reconciliation schema. Money, prices and quantities are NUMERIC(20,6) (docs/decisions/0001).
-- Refs are stored as received; the matcher normalizes them in memory.

-- Reference data --------------------------------------------------------------------------

create table instrument (
    isin            varchar(12)   primary key,
    asset_class     varchar(16)   not null check (asset_class in ('EQUITY', 'FIXED_INCOME')),
    reference_price numeric(20,6)
);

create table counterparty (
    counterparty_id varchar(16)   primary key,
    name            varchar(200)  not null,
    confirm_source  varchar(16)   not null check (confirm_source in ('BROKER', 'CLEARING')),
    commission_bps  numeric(20,6) not null
);

create table counterparty_alias (
    counterparty_id varchar(16)  not null references counterparty,
    alias           varchar(200) not null,
    primary key (counterparty_id, alias)
);

-- Trades and confirms -----------------------------------------------------------------------

create table internal_trade (
    id           bigint generated always as identity primary key,
    trade_ref    varchar(64)   not null unique,
    block_ref    varchar(64),
    account      varchar(32)   not null,
    isin         varchar(32)   not null,
    side         varchar(4)    not null check (side in ('BUY', 'SELL')),
    quantity     numeric(20,6) not null check (quantity > 0),
    price        numeric(20,6) not null check (price > 0),
    trade_date   date          not null,
    settle_date  date          not null,
    counterparty varchar(200)  not null,
    net_amount   numeric(20,6) not null
);
create index internal_trade_trade_date on internal_trade (trade_date);

create table external_confirm (
    id           bigint generated always as identity primary key,
    broker_ref   varchar(64)   not null unique,
    source       varchar(16)   not null check (source in ('BROKER', 'CLEARING')),
    -- Wider than an ISIN: confirms arrive unnormalized (padding, lower case).
    isin         varchar(32)   not null,
    side         varchar(4)    not null check (side in ('BUY', 'SELL')),
    quantity     numeric(20,6) not null check (quantity > 0),
    price        numeric(20,6) not null check (price > 0),
    commission   numeric(20,6) not null check (commission >= 0),
    trade_date   date          not null,
    settle_date  date          not null,
    counterparty varchar(200)  not null,
    net_amount   numeric(20,6) not null
);
create index external_confirm_trade_date on external_confirm (trade_date);

-- Runs and their results --------------------------------------------------------------------

create table recon_run (
    id                   bigint generated always as identity primary key,
    business_date        date        not null,
    status               varchar(16) not null check (status in ('RUNNING', 'COMPLETED', 'FAILED')),
    started_at           timestamptz not null,
    finished_at          timestamptz,
    duration_ms          bigint,
    internal_count       integer,
    external_count       integer,
    matched_internal     integer,
    stp_internal         integer,
    exact_matches        integer,
    block_matches        integer,
    fuzzy_matches        integer,
    breaks_found         integer,
    breaks_new           integer,
    breaks_carried       integer,
    breaks_auto_resolved integer,
    error                text
);
-- At most one run in progress per business date; a second POST gets a 409.
create unique index recon_run_one_running_per_date on recon_run (business_date) where status = 'RUNNING';
create index recon_run_business_date on recon_run (business_date, id);

-- One row per internal trade per run: which confirm it matched and which rule decided it.
create table match_link (
    run_id      bigint       not null references recon_run on delete cascade,
    internal_id bigint       not null references internal_trade,
    external_id bigint       not null references external_confirm,
    rule        varchar(8)   not null check (rule in ('EXACT', 'BLOCK', 'FUZZY')),
    score       numeric(5,2) not null,
    primary key (run_id, internal_id)
);
create index match_link_external on match_link (run_id, external_id);

-- The break register. A row lives across runs: run_id is the latest run that found it, while
-- opened_at, owner and note survive re-runs (carry-over).
create table recon_break (
    id                bigint generated always as identity primary key,
    run_id            bigint       not null references recon_run,
    business_date     date         not null,
    type              varchar(32)  not null check (type in ('MISSING_CONFIRM', 'UNEXPECTED_CONFIRM', 'QUANTITY_MISMATCH',
                          'PRICE_OUT_OF_TOLERANCE', 'SETTLE_DATE_MISMATCH', 'SIDE_MISMATCH', 'COUNTERPARTY_MISMATCH',
                          'COMMISSION_DIFFERENCE', 'DUPLICATE_CONFIRM')),
    internal_id       bigint       references internal_trade,
    external_id       bigint       references external_confirm,
    field             varchar(32),
    expected          varchar(200),
    actual            varchar(200),
    -- Canonical counterparty ID, copied here so the queue can filter without joins.
    counterparty      varchar(200),
    status            varchar(16)  not null check (status in ('OPEN', 'ASSIGNED', 'RESOLVED')),
    opened_at         timestamptz  not null,
    resolved_at       timestamptz,
    owner             varchar(100),
    note              text,
    resolution_reason text,
    -- Optimistic locking: two people editing the same break can't overwrite each other.
    version           bigint       not null default 0,
    check (internal_id is not null or external_id is not null),
    check ((status = 'RESOLVED') = (resolved_at is not null)),
    check (status <> 'ASSIGNED' or owner is not null)
);
-- One live break per key. NULLS NOT DISTINCT (PostgreSQL 15+) makes two MISSING_CONFIRMs for the
-- same trade collide even though their external_id and field are both NULL.
create unique index recon_break_one_live_per_key on recon_break (type, internal_id, external_id, field)
    nulls not distinct where status <> 'RESOLVED';
create index recon_break_queue on recon_break (status, opened_at);
create index recon_break_business_date on recon_break (business_date) where status <> 'RESOLVED';
create index recon_break_counterparty on recon_break (counterparty);
