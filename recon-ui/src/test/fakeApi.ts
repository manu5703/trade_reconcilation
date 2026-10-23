import { vi } from "vitest";
import type { Break, Page, Summary } from "../api";

export const SUMMARY: Summary = {
  open: 297,
  assigned: 3,
  oldestOpenedAt: "2026-01-11T09:00:00Z",
  oldestAgeHours: 100,
  byType: {
    MISSING_CONFIRM: 34, UNEXPECTED_CONFIRM: 34, QUANTITY_MISMATCH: 34, PRICE_OUT_OF_TOLERANCE: 33,
    SETTLE_DATE_MISMATCH: 33, SIDE_MISMATCH: 33, COUNTERPARTY_MISMATCH: 33, COMMISSION_DIFFERENCE: 33, DUPLICATE_CONFIRM: 33,
  },
  byCounterparty: { CP011: 14, CP001: 13 },
  byAge: [{ bucket: "<24h", count: 299 }, { bucket: "1-3d", count: 0 }, { bucket: "3-7d", count: 1 }, { bucket: ">7d", count: 0 }],
  latestRun: { id: 7, businessDate: "2026-01-15", finishedAt: "2026-01-15T18:00:00Z", matchRate: 0.9966, stpRate: 0.9734 },
};

export const QUANTITY_BREAK: Break = {
  id: 41,
  runId: 7,
  businessDate: "2026-01-15",
  type: "QUANTITY_MISMATCH",
  status: "OPEN",
  field: "quantity",
  expected: "4900",
  actual: "4870",
  counterparty: "CP011",
  openedAt: "2026-01-15T18:00:00Z",
  ageHours: 30,
  version: 3,
  trade: {
    tradeRef: "TR-20260115-00000041", account: "ACC00012", isin: "US0378331005", side: "BUY", quantity: 4900,
    price: 187.25, tradeDate: "2026-01-15", settleDate: "2026-01-16", counterparty: "CP011", netAmount: 917616.75,
  },
  confirm: {
    brokerRef: "KES-20260115-0000041", source: "BROKER", isin: "US0378331005", side: "BUY", quantity: 4870, price: 187.25,
    commission: 91.75, tradeDate: "2026-01-15", settleDate: "2026-01-16", counterparty: "CP011", netAmount: 911999.25,
  },
};

export const MISSING_BREAK: Break = {
  id: 42,
  runId: 7,
  businessDate: "2026-01-15",
  type: "MISSING_CONFIRM",
  status: "ASSIGNED",
  counterparty: "CP001",
  openedAt: "2026-01-11T09:00:00Z",
  ageHours: 100,
  owner: "a.jones",
  version: 1,
  trade: {
    tradeRef: "TR-20260115-00000042", account: "ACC00020", isin: "US5949181045", side: "SELL", quantity: 100,
    price: 410.5, tradeDate: "2026-01-15", settleDate: "2026-01-16", counterparty: "CP001", netAmount: 41045.9,
  },
};

export function page(items: Break[]): Page<Break> {
  return { items, page: 0, size: 25, totalItems: items.length, totalPages: 1 };
}

export interface Call {
  method: string;
  url: URL;
  body?: unknown;
}

type Handler = (call: Call) => { status?: number; body: unknown } | undefined;

/**
 * Stubs fetch with a tiny router. Default answers cover the summary, the list and break lookups;
 * `override` can intercept any call first (for PATCH outcomes or errors). Every call is recorded.
 */
export function fakeApi(override?: Handler) {
  const calls: Call[] = [];
  const breaks = new Map<number, Break>([[QUANTITY_BREAK.id, QUANTITY_BREAK], [MISSING_BREAK.id, MISSING_BREAK]]);
  const fetchMock = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    const call: Call = {
      method: init?.method ?? "GET",
      url: new URL(String(input), "http://localhost"),
      body: init?.body ? JSON.parse(String(init.body)) : undefined,
    };
    calls.push(call);
    const answer = override?.(call) ?? defaultAnswer(call, breaks);
    return new Response(JSON.stringify(answer.body), {
      status: answer.status ?? 200,
      headers: { "Content-Type": answer.status && answer.status >= 400 ? "application/problem+json" : "application/json" },
    });
  });
  vi.stubGlobal("fetch", fetchMock);
  return {
    calls,
    listCalls: () => calls.filter((c) => c.url.pathname === "/api/breaks"),
    patchCalls: () => calls.filter((c) => c.method === "PATCH"),
  };
}

function defaultAnswer(call: Call, breaks: Map<number, Break>): { status?: number; body: unknown } {
  const path = call.url.pathname;
  if (path === "/api/breaks/summary") return { body: SUMMARY };
  if (path === "/api/breaks") return { body: page([MISSING_BREAK, QUANTITY_BREAK]) };
  const id = Number(path.split("/").pop());
  const brk = breaks.get(id);
  if (brk && call.method === "GET") return { body: brk };
  return { status: 404, body: { detail: `Break ${id} does not exist` } };
}
