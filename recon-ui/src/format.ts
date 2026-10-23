/** "3d 4h", "5h", "<1h": compact enough for a table cell, exact enough to triage by. */
export function formatAge(hours: number | undefined): string {
  if (hours === undefined) return "—";
  if (hours < 1) return "<1h";
  const days = Math.floor(hours / 24);
  const rest = hours % 24;
  if (days === 0) return `${rest}h`;
  return rest === 0 ? `${days}d` : `${days}d ${rest}h`;
}

export function formatPercent(ratio: number | undefined): string {
  return ratio === undefined ? "—" : `${(ratio * 100).toFixed(2)}%`;
}

const decimal = new Intl.NumberFormat("en-US", { maximumFractionDigits: 6 });

export function formatValue(value: string | number | undefined | null): string {
  if (value === undefined || value === null || value === "") return "—";
  return typeof value === "number" ? decimal.format(value) : value;
}

/** MISSING_CONFIRM → "Missing confirm". */
export function humanize(code: string): string {
  const words = code.toLowerCase().split("_");
  return words.map((w, i) => (i === 0 ? w.charAt(0).toUpperCase() + w.slice(1) : w)).join(" ");
}

export function formatTimestamp(iso: string | undefined): string {
  if (!iso) return "—";
  return new Date(iso).toLocaleString("en-US", {
    year: "numeric",
    month: "short",
    day: "numeric",
    hour: "2-digit",
    minute: "2-digit",
  });
}
