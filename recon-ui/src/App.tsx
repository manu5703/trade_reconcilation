import { useCallback, useEffect, useState } from "react";
import { api, type Break, type Page, type Summary } from "./api";
import { BreakDrawer } from "./components/BreakDrawer";
import { BreakTable } from "./components/BreakTable";
import { Filters } from "./components/Filters";
import { Pagination } from "./components/Pagination";
import { SummaryTiles } from "./components/SummaryTiles";
import { useUrlState } from "./useUrlState";

export function App() {
  const [view, setView] = useUrlState();
  const { query, selectedId } = view;
  const [page, setPage] = useState<Page<Break>>();
  const [summary, setSummary] = useState<Summary>();
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string>();
  // Bumped after an edit, to refetch the queue and tiles without touching the URL.
  const [revision, setRevision] = useState(0);

  useEffect(() => {
    let current = true;
    setLoading(true);
    api.listBreaks(query).then(
      (p) => { if (current) { setPage(p); setError(undefined); } },
      (e: unknown) => { if (current) setError(e instanceof Error ? e.message : String(e)); },
    ).finally(() => { if (current) setLoading(false); });
    // Ignore a slow response that arrives after the user has already changed the filters.
    return () => { current = false; };
  }, [query, revision]);

  useEffect(() => {
    api.summary().then(setSummary, () => setSummary(undefined));
  }, [revision]);

  const closeDrawer = useCallback(() => setView({ query, selectedId: undefined }), [query, setView]);
  const refresh = useCallback(() => setRevision((r) => r + 1), []);

  return (
    <div className="app">
      <header className="app__header">
        <h1>Break queue</h1>
        <p className="muted">Internal bookings vs broker and clearing confirmations</p>
      </header>
      <SummaryTiles summary={summary} />
      <Filters query={query} summary={summary} onChange={(q) => setView({ query: q, selectedId })} />
      {error && <p className="alert" role="alert">Could not load breaks: {error}</p>}
      <BreakTable
        page={page}
        sort={query.sort}
        loading={loading}
        selectedId={selectedId}
        onSort={(sort) => setView({ query: { ...query, sort, page: 0 }, selectedId })}
        onSelect={(id) => setView({ query, selectedId: id })}
      />
      <Pagination page={page} onPage={(p) => setView({ query: { ...query, page: p }, selectedId })} />
      {selectedId !== undefined && <BreakDrawer breakId={selectedId} onClose={closeDrawer} onChanged={refresh} />}
    </div>
  );
}
