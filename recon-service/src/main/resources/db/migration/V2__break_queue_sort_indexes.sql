-- One index per sortable queue column, ending in id (the tie-breaker). With them, a page of the
-- queue is an ordered index scan plus 25 primary-key lookups into internal_trade and
-- external_confirm. Without them, PostgreSQL joined every break to the trade and confirm tables
-- before sorting: 260 ms per page against a 1M-trade day, p99 842 ms under load (docs/results.md).
create index recon_break_sort_opened_at     on recon_break (opened_at, id);
create index recon_break_sort_type          on recon_break (type, id);
create index recon_break_sort_counterparty  on recon_break (counterparty, id);
create index recon_break_sort_status        on recon_break (status, id);
create index recon_break_sort_business_date on recon_break (business_date, id);
