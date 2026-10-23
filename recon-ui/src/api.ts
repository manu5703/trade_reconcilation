// Typed client for recon-service. The types mirror the Java records (BreakView, PageView,
// BreakSummary, RunView) field for field, so a renamed field is a compile error here.

export const BREAK_TYPES = [
  "MISSING_CONFIRM",
  "UNEXPECTED_CONFIRM",
  "QUANTITY_MISMATCH",
  "PRICE_OUT_OF_TOLERANCE",
  "SETTLE_DATE_MISMATCH",
  "SIDE_MISMATCH",
  "COUNTERPARTY_MISMATCH",
  "COMMISSION_DIFFERENCE",
  "DUPLICATE_CONFIRM",
] as const;
export type BreakType = (typeof BREAK_TYPES)[number];

export const BREAK_STATUSES = ["OPEN", "ASSIGNED", "RESOLVED"] as const;
export type BreakStatus = (typeof BREAK_STATUSES)[number];

/** Server-side sortable fields; a leading "-" means descending. */
export type SortField = "openedAt" | "type" | "counterparty" | "status" | "businessDate";

// Decimals arrive as JSON numbers (BigDecimal on the server). They are only displayed, never
// used in arithmetic, so the browser's doubles can't introduce money errors here.
export interface TradeRecord {
  tradeRef: string;
  blockRef?: string;
  account: string;
  isin: string;
  side: string;
  quantity: number;
  price: number;
  tradeDate: string;
  settleDate: string;
  counterparty: string;
  netAmount: number;
}

export interface ConfirmRecord {
  brokerRef: string;
  source: string;
  isin: string;
  side: string;
  quantity: number;
  price: number;
  commission: number;
  tradeDate: string;
  settleDate: string;
  counterparty: string;
  netAmount: number;
}

export interface Break {
  id: number;
  runId: number;
  businessDate: string;
  type: BreakType;
  status: BreakStatus;
  field?: string;
  expected?: string;
  actual?: string;
  counterparty?: string;
  openedAt: string;
  ageHours: number;
  resolvedAt?: string;
  owner?: string;
  note?: string;
  resolutionReason?: string;
  version: number;
  trade?: TradeRecord;
  confirm?: ConfirmRecord;
}

export interface Page<T> {
  items: T[];
  page: number;
  size: number;
  totalItems: number;
  totalPages: number;
}

export interface Summary {
  open: number;
  assigned: number;
  oldestOpenedAt?: string;
  oldestAgeHours?: number;
  byType: Record<BreakType, number>;
  byCounterparty: Record<string, number>;
  byAge: { bucket: string; count: number }[];
  latestRun?: { id: number; businessDate: string; finishedAt: string; matchRate?: number; stpRate?: number };
}

export interface BreakQuery {
  status?: BreakStatus;
  type?: BreakType;
  counterparty?: string;
  minAgeHours?: number;
  sort: string;
  page: number;
  size: number;
}

export interface BreakPatch {
  owner?: string;
  note?: string;
  status?: "RESOLVED";
  reason?: string;
  expectedVersion?: number;
}

/** An RFC 7807 problem detail from the service, kept whole so forms can show field errors. */
export class ApiError extends Error {
  constructor(
    readonly status: number,
    message: string,
    readonly fieldErrors: { field: string; message: string }[] = [],
  ) {
    super(message);
    this.name = "ApiError";
  }
}

async function request<T>(path: string, init?: RequestInit): Promise<T> {
  const response = await fetch(path, {
    ...init,
    headers: { Accept: "application/json", ...(init?.body ? { "Content-Type": "application/json" } : {}) },
  });
  if (!response.ok) {
    let detail = `${response.status} ${response.statusText}`;
    let errors: { field: string; message: string }[] = [];
    try {
      const problem = await response.json();
      detail = problem.detail ?? detail;
      errors = problem.errors ?? [];
    } catch {
      // Not a problem-detail body (e.g. a proxy error page); keep the status line.
    }
    throw new ApiError(response.status, detail, errors);
  }
  return response.json() as Promise<T>;
}

export function breaksUrl(q: BreakQuery): string {
  const params = new URLSearchParams();
  if (q.status) params.set("status", q.status);
  if (q.type) params.set("type", q.type);
  if (q.counterparty) params.set("counterparty", q.counterparty);
  if (q.minAgeHours !== undefined) params.set("minAgeHours", String(q.minAgeHours));
  params.set("sort", q.sort);
  params.set("page", String(q.page));
  params.set("size", String(q.size));
  return `/api/breaks?${params}`;
}

export const api = {
  listBreaks: (q: BreakQuery) => request<Page<Break>>(breaksUrl(q)),
  getBreak: (id: number) => request<Break>(`/api/breaks/${id}`),
  patchBreak: (id: number, patch: BreakPatch) =>
    request<Break>(`/api/breaks/${id}`, { method: "PATCH", body: JSON.stringify(patch) }),
  summary: () => request<Summary>("/api/breaks/summary"),
};
