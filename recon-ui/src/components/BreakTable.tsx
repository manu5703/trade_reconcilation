import type { Break, Page, SortField } from "../api";
import { formatAge, humanize } from "../format";

interface Props {
  page?: Page<Break>;
  sort: string;
  loading: boolean;
  selectedId?: number;
  onSort: (sort: string) => void;
  onSelect: (id: number) => void;
}

interface Column {
  label: string;
  /** Present when the server can sort by this column. */
  sortField?: SortField;
  /** Age is the reverse of openedAt: the oldest break has the earliest openedAt. */
  invert?: boolean;
  className?: string;
}

const COLUMNS: Column[] = [
  { label: "Age", sortField: "openedAt", invert: true, className: "num" },
  { label: "Type", sortField: "type" },
  { label: "Status", sortField: "status" },
  { label: "Trade" },
  { label: "Confirm" },
  { label: "Counterparty", sortField: "counterparty" },
  { label: "Field" },
  { label: "Expected → actual" },
  { label: "Owner" },
];

/** What the confirm got wrong, in one cell. A duplicate has no field, just the confirm it repeats. */
function difference(b: Break) {
  if (b.type === "DUPLICATE_CONFIRM") return <><span className="muted">duplicate of</span> {b.expected}</>;
  if (b.expected === undefined) return "—";
  return <>{b.expected} <span className="arrow">→</span> {b.actual}</>;
}

/** Sort is server-side, so it covers every page rather than reordering only the rows on screen. */
export function BreakTable({ page, sort, loading, selectedId, onSort, onSelect }: Props) {
  const descending = sort.startsWith("-");
  const sortField = descending ? sort.slice(1) : sort;

  const ariaSort = (c: Column): "ascending" | "descending" | "none" => {
    if (c.sortField !== sortField) return "none";
    return descending !== Boolean(c.invert) ? "descending" : "ascending";
  };
  const toggle = (c: Column) => {
    if (!c.sortField) return;
    // A new column starts ascending on the server field (A→Z, or oldest first for Age);
    // clicking the same column again flips it.
    const nextDescending = c.sortField === sortField ? !descending : false;
    onSort(`${nextDescending ? "-" : ""}${c.sortField}`);
  };

  return (
    <div className="table-wrap" aria-busy={loading}>
      <table className="breaks">
        <thead>
          <tr>
            {COLUMNS.map((c) => (
              <th key={c.label} scope="col" className={c.className} aria-sort={c.sortField ? ariaSort(c) : undefined}>
                {c.sortField ? (
                  <button type="button" className="sort" onClick={() => toggle(c)}>
                    {c.label}
                    <span className="sort__icon" aria-hidden="true">
                      {ariaSort(c) === "ascending" ? "▲" : ariaSort(c) === "descending" ? "▼" : "↕"}
                    </span>
                  </button>
                ) : (
                  c.label
                )}
              </th>
            ))}
          </tr>
        </thead>
        <tbody>
          {page?.items.map((b) => (
            <tr
              key={b.id}
              className={b.id === selectedId ? "is-selected" : undefined}
              onClick={() => onSelect(b.id)}
              onKeyDown={(e) => { if (e.key === "Enter" || e.key === " ") { e.preventDefault(); onSelect(b.id); } }}
              tabIndex={0}
              aria-label={`Break ${b.id}: ${humanize(b.type)}`}
            >
              <td className="num">{formatAge(b.ageHours)}</td>
              <td><span className={`badge badge--${b.type.toLowerCase()}`}>{humanize(b.type)}</span></td>
              <td><span className={`pill pill--${b.status.toLowerCase()}`}>{humanize(b.status)}</span></td>
              <td className="mono">{b.trade?.tradeRef ?? "—"}</td>
              <td className="mono">{b.confirm?.brokerRef ?? "—"}</td>
              <td className="mono">{b.counterparty ?? "—"}</td>
              <td>{b.field ?? "—"}</td>
              <td className="mono">{difference(b)}</td>
              <td>{b.owner ?? <span className="muted">unassigned</span>}</td>
            </tr>
          ))}
        </tbody>
      </table>
      {page && page.items.length === 0 && <p className="empty">No breaks match these filters.</p>}
      {!page && loading && <p className="empty">Loading breaks…</p>}
    </div>
  );
}
