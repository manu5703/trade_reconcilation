import { useEffect, useState } from "react";
import { BREAK_STATUSES, BREAK_TYPES, type BreakQuery, type BreakStatus, type BreakType, type Summary } from "../api";
import { humanize } from "../format";

interface Props {
  query: BreakQuery;
  summary?: Summary;
  onChange: (query: BreakQuery) => void;
}

const AGE_OPTIONS = [
  { label: "Any age", value: undefined },
  { label: "Older than 24h", value: 24 },
  { label: "Older than 3 days", value: 72 },
  { label: "Older than 7 days", value: 168 },
];

/** Every change goes back to page 0: page 4 of the old filter means nothing under the new one. */
export function Filters({ query, summary, onChange }: Props) {
  // The counterparty box applies on Enter or blur, not on every keystroke.
  const [counterparty, setCounterparty] = useState(query.counterparty ?? "");
  useEffect(() => setCounterparty(query.counterparty ?? ""), [query.counterparty]);

  const set = (patch: Partial<BreakQuery>) => onChange({ ...query, ...patch, page: 0 });
  const applyCounterparty = () => {
    const value = counterparty.trim() || undefined;
    if (value !== query.counterparty) set({ counterparty: value });
  };
  const anyFilter = query.status || query.type || query.counterparty || query.minAgeHours !== undefined;

  return (
    <form className="filters" aria-label="Filters" onSubmit={(e) => { e.preventDefault(); applyCounterparty(); }}>
      <label>
        <span>Status</span>
        <select value={query.status ?? ""} onChange={(e) => set({ status: (e.target.value || undefined) as BreakStatus | undefined })}>
          <option value="">All statuses</option>
          {BREAK_STATUSES.map((s) => <option key={s} value={s}>{humanize(s)}</option>)}
        </select>
      </label>
      <label>
        <span>Type</span>
        <select value={query.type ?? ""} onChange={(e) => set({ type: (e.target.value || undefined) as BreakType | undefined })}>
          <option value="">All types</option>
          {BREAK_TYPES.map((t) => (
            <option key={t} value={t}>
              {humanize(t)}{summary ? ` (${summary.byType[t] ?? 0} open)` : ""}
            </option>
          ))}
        </select>
      </label>
      <label>
        <span>Counterparty</span>
        <input
          value={counterparty}
          placeholder="e.g. CP007"
          list="counterparties"
          onChange={(e) => setCounterparty(e.target.value)}
          onBlur={applyCounterparty}
        />
        <datalist id="counterparties">
          {summary && Object.keys(summary.byCounterparty).map((cp) => <option key={cp} value={cp} />)}
        </datalist>
      </label>
      <label>
        <span>Age</span>
        <select
          value={query.minAgeHours ?? ""}
          onChange={(e) => set({ minAgeHours: e.target.value === "" ? undefined : Number(e.target.value) })}
        >
          {AGE_OPTIONS.map((o) => <option key={o.label} value={o.value ?? ""}>{o.label}</option>)}
        </select>
      </label>
      {anyFilter && (
        <button type="button" className="button button--ghost"
          onClick={() => onChange({ ...query, status: undefined, type: undefined, counterparty: undefined, minAgeHours: undefined, page: 0 })}>
          Clear filters
        </button>
      )}
    </form>
  );
}
