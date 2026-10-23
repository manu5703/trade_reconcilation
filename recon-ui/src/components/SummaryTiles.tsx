import type { Summary } from "../api";
import { formatAge, formatPercent, formatTimestamp } from "../format";

interface Props {
  summary?: Summary;
}

/** The three numbers an operations lead checks first: queue size, oldest break, and STP. */
export function SummaryTiles({ summary }: Props) {
  const live = summary ? summary.open + summary.assigned : undefined;
  const run = summary?.latestRun;
  return (
    <section className="tiles" aria-label="Summary">
      <Tile
        label="Open breaks"
        value={live === undefined ? "—" : live.toLocaleString("en-US")}
        detail={summary ? `${summary.assigned.toLocaleString("en-US")} assigned` : undefined}
      />
      <Tile
        label="Oldest break"
        value={formatAge(summary?.oldestAgeHours)}
        detail={summary?.oldestOpenedAt ? `since ${formatTimestamp(summary.oldestOpenedAt)}` : "no open breaks"}
        tone={summary?.oldestAgeHours !== undefined && summary.oldestAgeHours >= 72 ? "warn" : undefined}
      />
      <Tile
        label="STP rate"
        value={formatPercent(run?.stpRate)}
        detail={run ? `run ${run.id} · business date ${run.businessDate}` : "no completed run yet"}
      />
    </section>
  );
}

function Tile({ label, value, detail, tone }: { label: string; value: string; detail?: string; tone?: "warn" }) {
  return (
    <div className={`tile${tone ? ` tile--${tone}` : ""}`}>
      <div className="tile__label">{label}</div>
      <div className="tile__value">{value}</div>
      {detail && <div className="tile__detail">{detail}</div>}
    </div>
  );
}
