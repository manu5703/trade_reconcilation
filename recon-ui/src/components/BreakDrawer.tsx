import { useEffect, useRef, useState } from "react";
import { api, ApiError, type Break, type BreakPatch } from "../api";
import { formatAge, formatTimestamp, formatValue, humanize } from "../format";

interface Props {
  breakId: number;
  onClose: () => void;
  /** Called after a successful change so the queue and tiles can refresh. */
  onChanged: () => void;
}

interface Row {
  label: string;
  /** The matcher's field name, used to highlight the row the break is about. */
  field?: string;
  trade: (b: Break) => string | number | undefined;
  confirm: (b: Break) => string | number | undefined;
}

// The matcher decides what differs (after normalization and tolerances); the drawer only
// highlights its verdict. A client-side comparison would wrongly flag " us0378331005" vs
// "US0378331005", or a price inside tolerance.
const ROWS: Row[] = [
  { label: "Reference", trade: (b) => b.trade?.tradeRef, confirm: (b) => b.confirm?.brokerRef },
  { label: "ISIN", trade: (b) => b.trade?.isin, confirm: (b) => b.confirm?.isin },
  { label: "Side", field: "side", trade: (b) => b.trade?.side, confirm: (b) => b.confirm?.side },
  { label: "Quantity", field: "quantity", trade: (b) => b.trade?.quantity, confirm: (b) => b.confirm?.quantity },
  { label: "Price", field: "price", trade: (b) => b.trade?.price, confirm: (b) => b.confirm?.price },
  {
    label: "Commission",
    field: "commission",
    // Bookings carry no commission; the expected value is the counterparty's schedule.
    trade: (b) => (b.field === "commission" ? `${b.expected} (scheduled)` : undefined),
    confirm: (b) => b.confirm?.commission,
  },
  { label: "Trade date", trade: (b) => b.trade?.tradeDate, confirm: (b) => b.confirm?.tradeDate },
  { label: "Settle date", field: "settleDate", trade: (b) => b.trade?.settleDate, confirm: (b) => b.confirm?.settleDate },
  { label: "Counterparty", field: "counterparty", trade: (b) => b.trade?.counterparty, confirm: (b) => b.confirm?.counterparty },
  { label: "Net amount", field: "netAmount", trade: (b) => b.trade?.netAmount, confirm: (b) => b.confirm?.netAmount },
  { label: "Account", trade: (b) => b.trade?.account, confirm: () => undefined },
  { label: "Source", trade: () => undefined, confirm: (b) => b.confirm?.source },
];

export function BreakDrawer({ breakId, onClose, onChanged }: Props) {
  const [brk, setBrk] = useState<Break>();
  const [loadError, setLoadError] = useState<string>();
  const closeRef = useRef<HTMLButtonElement>(null);

  useEffect(() => {
    let current = true;
    setBrk(undefined);
    setLoadError(undefined);
    api.getBreak(breakId).then(
      (b) => current && setBrk(b),
      (e: unknown) => current && setLoadError(e instanceof Error ? e.message : String(e)),
    );
    return () => { current = false; };
  }, [breakId]);

  useEffect(() => {
    closeRef.current?.focus();
    const onKey = (e: KeyboardEvent) => { if (e.key === "Escape") onClose(); };
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, [onClose]);

  return (
    <>
      <div className="scrim" onClick={onClose} aria-hidden="true" />
      <aside className="drawer" role="dialog" aria-modal="true" aria-labelledby="drawer-title">
        <header className="drawer__header">
          <div>
            <h2 id="drawer-title">Break {breakId}</h2>
            {brk && (
              <div className="drawer__meta">
                <span className={`badge badge--${brk.type.toLowerCase()}`}>{humanize(brk.type)}</span>
                <span className={`pill pill--${brk.status.toLowerCase()}`}>{humanize(brk.status)}</span>
                <span className="muted">open {formatAge(brk.ageHours)} · business date {brk.businessDate} · run {brk.runId}</span>
              </div>
            )}
          </div>
          <button ref={closeRef} type="button" className="button button--ghost" onClick={onClose} aria-label="Close">✕</button>
        </header>

        {loadError && <p className="alert" role="alert">{loadError}</p>}
        {!brk && !loadError && <p className="muted">Loading…</p>}
        {brk && <Details brk={brk} />}
        {brk && <Actions key={brk.id} brk={brk} onSaved={(b) => { setBrk(b); onChanged(); }} onStale={() => api.getBreak(breakId).then(setBrk)} />}
      </aside>
    </>
  );
}

function Details({ brk }: { brk: Break }) {
  return (
    <div className="drawer__body">
      {brk.field && (
        <div className="verdict" aria-label="Expected versus actual">
          <div className="verdict__field">{brk.field}</div>
          <div><span className="muted">expected</span> <strong className="mono">{brk.expected}</strong></div>
          <div><span className="muted">actual</span> <strong className="mono verdict__actual">{brk.actual}</strong></div>
        </div>
      )}
      {brk.type === "DUPLICATE_CONFIRM" && (
        <p className="verdict">Confirm <strong className="mono">{brk.actual}</strong> repeats <strong className="mono">{brk.expected}</strong>, which is already matched to this trade.</p>
      )}

      <table className="compare">
        <thead>
          <tr><th scope="col" /><th scope="col">Internal booking</th><th scope="col">External confirm</th></tr>
        </thead>
        <tbody>
          {ROWS.map((r) => (
            <tr key={r.label} className={r.field && r.field === brk.field ? "is-diff" : undefined}>
              <th scope="row">{r.label}</th>
              <td className="mono">{formatValue(r.trade(brk))}</td>
              <td className="mono">{formatValue(r.confirm(brk))}</td>
            </tr>
          ))}
        </tbody>
      </table>

      {brk.status === "RESOLVED" && (
        <section className="resolution">
          <h3>Resolved {formatTimestamp(brk.resolvedAt)}</h3>
          <p>{brk.resolutionReason}</p>
        </section>
      )}

      <section>
        <h3>Notes</h3>
        {brk.note ? (
          <ul className="notes">{brk.note.split("\n").map((line, i) => <li key={i}>{line}</li>)}</ul>
        ) : (
          <p className="muted">No notes yet.</p>
        )}
      </section>
    </div>
  );
}

interface ActionsProps {
  brk: Break;
  onSaved: (b: Break) => void;
  onStale: () => void;
}

/** Assign, note and resolve. Each sends the version it was looking at, so stale edits are refused. */
function Actions({ brk, onSaved, onStale }: ActionsProps) {
  const [owner, setOwner] = useState(brk.owner ?? "");
  const [note, setNote] = useState("");
  const [reason, setReason] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<{ message: string; field?: string; stale?: boolean }>();

  if (brk.status === "RESOLVED") return null;

  const submit = async (patch: BreakPatch, clear: () => void) => {
    setBusy(true);
    setError(undefined);
    try {
      onSaved(await api.patchBreak(brk.id, { ...patch, expectedVersion: brk.version }));
      clear();
    } catch (e) {
      if (e instanceof ApiError) {
        setError({ message: e.message, field: e.fieldErrors[0]?.field, stale: e.status === 409 });
      } else {
        setError({ message: e instanceof Error ? e.message : String(e) });
      }
    } finally {
      setBusy(false);
    }
  };

  const resolve = () => {
    // Checked here for a fast answer; the server enforces the same rule.
    if (!reason.trim()) {
      setError({ message: "A reason is required to resolve a break", field: "reason" });
      return;
    }
    void submit({ status: "RESOLVED", reason: reason.trim() }, () => setReason(""));
  };

  return (
    <div className="actions">
      {/* Errors about the reason field are shown under that field instead. */}
      {error && error.field !== "reason" && (
        <div className="alert" role="alert">
          {error.message}
          {error.stale && <button type="button" className="button button--ghost" onClick={() => { setError(undefined); onStale(); }}>Reload</button>}
        </div>
      )}
      <form onSubmit={(e) => { e.preventDefault(); if (owner.trim()) void submit({ owner: owner.trim() }, () => {}); }}>
        <label htmlFor="owner">Owner</label>
        <div className="inline">
          <input id="owner" value={owner} onChange={(e) => setOwner(e.target.value)} placeholder="e.g. j.smith" maxLength={100} />
          <button type="submit" className="button" disabled={busy || !owner.trim() || owner.trim() === brk.owner}>Assign</button>
        </div>
      </form>
      <form onSubmit={(e) => { e.preventDefault(); if (note.trim()) void submit({ note: note.trim() }, () => setNote("")); }}>
        <label htmlFor="note">Add a note</label>
        <textarea id="note" value={note} onChange={(e) => setNote(e.target.value)} rows={2} maxLength={2000} />
        <button type="submit" className="button" disabled={busy || !note.trim()}>Add note</button>
      </form>
      <form onSubmit={(e) => { e.preventDefault(); resolve(); }}>
        <label htmlFor="reason">Resolution reason</label>
        <textarea
          id="reason"
          value={reason}
          onChange={(e) => setReason(e.target.value)}
          rows={2}
          maxLength={2000}
          aria-invalid={error?.field === "reason"}
          aria-describedby={error?.field === "reason" ? "reason-error" : undefined}
        />
        {error?.field === "reason" && <p id="reason-error" className="field-error" role="alert">{error.message}</p>}
        <button type="submit" className="button button--primary" disabled={busy}>Resolve</button>
      </form>
    </div>
  );
}
