import { describe, expect, it } from "vitest";
import { breaksUrl } from "../api";
import { formatAge, formatPercent, humanize } from "../format";
import { DEFAULT_SORT, parseViewState, serializeViewState } from "../useUrlState";

describe("formatAge", () => {
  it.each([
    [undefined, "—"],
    [0, "<1h"],
    [5, "5h"],
    [24, "1d"],
    [100, "4d 4h"],
  ])("%s hours → %s", (hours, text) => {
    expect(formatAge(hours)).toBe(text);
  });
});

describe("formatting", () => {
  it("shows ratios as percentages with two decimals", () => {
    expect(formatPercent(0.9734)).toBe("97.34%");
    expect(formatPercent(undefined)).toBe("—");
  });

  it("turns enum codes into words", () => {
    expect(humanize("PRICE_OUT_OF_TOLERANCE")).toBe("Price out of tolerance");
  });
});

describe("URL state", () => {
  it("round-trips a full view", () => {
    const search = "?status=ASSIGNED&type=SIDE_MISMATCH&counterparty=CP007&minAgeHours=24&sort=-type&page=2&break=9";
    expect(serializeViewState(parseViewState(search))).toBe(search);
  });

  it("falls back to defaults for values it doesn't recognise", () => {
    const { query, selectedId } = parseViewState("?status=CLOSED&type=nope&page=-1&minAgeHours=x&break=abc");
    expect(query).toMatchObject({ status: undefined, type: undefined, page: 0, minAgeHours: undefined, sort: DEFAULT_SORT });
    expect(selectedId).toBeUndefined();
  });

  it("builds the API URL with only the filters that are set", () => {
    expect(breaksUrl({ sort: "openedAt", page: 0, size: 25, type: "DUPLICATE_CONFIRM" }))
      .toBe("/api/breaks?type=DUPLICATE_CONFIRM&sort=openedAt&page=0&size=25");
  });
});
