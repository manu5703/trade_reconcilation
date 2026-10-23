import { useCallback, useEffect, useState } from "react";
import { BREAK_STATUSES, BREAK_TYPES, type BreakQuery, type BreakStatus, type BreakType } from "./api";

export const PAGE_SIZE = 25;
/** Oldest first: the break that has waited longest is the most urgent. */
export const DEFAULT_SORT = "openedAt";

/** Everything that defines what the screen shows: the query plus the break open in the drawer. */
export interface ViewState {
  query: BreakQuery;
  selectedId?: number;
}

function oneOf<T extends string>(values: readonly T[], raw: string | null): T | undefined {
  return values.find((v) => v === raw);
}

function nonNegativeInt(raw: string | null): number | undefined {
  if (raw === null || raw.trim() === "") return undefined;
  const n = Number(raw);
  return Number.isInteger(n) && n >= 0 ? n : undefined;
}

/** Parses the address bar. Anything unrecognised falls back to a default instead of failing. */
export function parseViewState(search: string): ViewState {
  const p = new URLSearchParams(search);
  return {
    query: {
      status: oneOf<BreakStatus>(BREAK_STATUSES, p.get("status")),
      type: oneOf<BreakType>(BREAK_TYPES, p.get("type")),
      counterparty: p.get("counterparty") || undefined,
      minAgeHours: nonNegativeInt(p.get("minAgeHours")),
      sort: p.get("sort") || DEFAULT_SORT,
      page: nonNegativeInt(p.get("page")) ?? 0,
      size: PAGE_SIZE,
    },
    selectedId: nonNegativeInt(p.get("break")),
  };
}

export function serializeViewState({ query, selectedId }: ViewState): string {
  const p = new URLSearchParams();
  if (query.status) p.set("status", query.status);
  if (query.type) p.set("type", query.type);
  if (query.counterparty) p.set("counterparty", query.counterparty);
  if (query.minAgeHours !== undefined) p.set("minAgeHours", String(query.minAgeHours));
  if (query.sort !== DEFAULT_SORT) p.set("sort", query.sort);
  if (query.page > 0) p.set("page", String(query.page));
  if (selectedId !== undefined) p.set("break", String(selectedId));
  const s = p.toString();
  return s ? `?${s}` : "";
}

/**
 * Keeps the view in the URL, so a filtered queue or an open break can be shared as a link and
 * the browser's back button works.
 */
export function useUrlState(): [ViewState, (next: ViewState) => void] {
  const [state, setState] = useState(() => parseViewState(window.location.search));

  useEffect(() => {
    const onPop = () => setState(parseViewState(window.location.search));
    window.addEventListener("popstate", onPop);
    return () => window.removeEventListener("popstate", onPop);
  }, []);

  const update = useCallback((next: ViewState) => {
    const search = serializeViewState(next);
    if (search !== window.location.search) {
      window.history.pushState(null, "", `${window.location.pathname}${search}`);
    }
    setState(next);
  }, []);

  return [state, update];
}
