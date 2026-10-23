import { render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { App } from "../App";
import { fakeApi, QUANTITY_BREAK } from "./fakeApi";

beforeEach(() => window.history.replaceState(null, "", "/"));
afterEach(() => vi.unstubAllGlobals());

async function renderApp() {
  const user = userEvent.setup();
  render(<App />);
  await screen.findByRole("row", { name: /Break 41/ });
  return user;
}

describe("summary tiles", () => {
  it("shows open breaks, oldest break age and STP rate", async () => {
    fakeApi();
    await renderApp();

    const tiles = screen.getByRole("region", { name: "Summary" });
    expect(within(tiles).getByText("300")).toBeInTheDocument();
    expect(within(tiles).getByText("3 assigned")).toBeInTheDocument();
    expect(within(tiles).getByText("4d 4h")).toBeInTheDocument();
    expect(within(tiles).getByText("97.34%")).toBeInTheDocument();
    expect(within(tiles).getByText("run 7 · business date 2026-01-15")).toBeInTheDocument();
  });
});

describe("break table", () => {
  it("asks for the oldest breaks first and lists them", async () => {
    const api = fakeApi();
    await renderApp();

    const first = api.listCalls()[0]!.url.searchParams;
    expect(first.get("sort")).toBe("openedAt");
    expect(first.get("page")).toBe("0");
    expect(first.get("size")).toBe("25");
    const row = screen.getByRole("row", { name: /Break 41/ });
    expect(within(row).getByText("1d 6h")).toBeInTheDocument();
    expect(within(row).getByText("TR-20260115-00000041")).toBeInTheDocument();
    expect(within(row).getByText(/4900/)).toHaveTextContent("4900 → 4870");
    expect(screen.getByRole("columnheader", { name: /Age/ })).toHaveAttribute("aria-sort", "descending");
  });

  it("sorts on the server when a header is clicked, and flips on a second click", async () => {
    const api = fakeApi();
    const user = await renderApp();

    await user.click(screen.getByRole("button", { name: /Type/ }));
    await waitFor(() => expect(api.listCalls().at(-1)!.url.searchParams.get("sort")).toBe("type"));
    expect(screen.getByRole("columnheader", { name: /Type/ })).toHaveAttribute("aria-sort", "ascending");

    await user.click(screen.getByRole("button", { name: /Type/ }));
    await waitFor(() => expect(api.listCalls().at(-1)!.url.searchParams.get("sort")).toBe("-type"));
    expect(window.location.search).toContain("sort=-type");
  });

  it("applies filters, resets to the first page and keeps them in the URL", async () => {
    window.history.replaceState(null, "", "/?page=3");
    const api = fakeApi();
    const user = await renderApp();
    expect(api.listCalls()[0]!.url.searchParams.get("page")).toBe("3");

    await user.selectOptions(screen.getByLabelText("Status"), "OPEN");
    await user.selectOptions(screen.getByLabelText("Age"), "Older than 3 days");
    await user.type(screen.getByLabelText("Counterparty"), "CP011{Enter}");

    await waitFor(() => {
      const params = api.listCalls().at(-1)!.url.searchParams;
      expect(params.get("status")).toBe("OPEN");
      expect(params.get("minAgeHours")).toBe("72");
      expect(params.get("counterparty")).toBe("CP011");
      expect(params.get("page")).toBe("0");
    });
    expect(window.location.search).toBe("?status=OPEN&counterparty=CP011&minAgeHours=72");
  });

  it("shows the open-count per type in the type filter", async () => {
    fakeApi();
    await renderApp();

    expect(screen.getByRole("option", { name: "Quantity mismatch (34 open)" })).toBeInTheDocument();
  });
});

describe("break drawer", () => {
  it("opens from a row and shows expected against actual, highlighting the broken field", async () => {
    fakeApi();
    const user = await renderApp();

    await user.click(screen.getByRole("row", { name: /Break 41/ }));

    const drawer = await screen.findByRole("dialog", { name: "Break 41" });
    const verdict = await within(drawer).findByLabelText("Expected versus actual");
    expect(verdict).toHaveTextContent("quantity");
    expect(verdict).toHaveTextContent("expected 4900");
    expect(verdict).toHaveTextContent("actual 4870");
    const quantityRow = within(drawer).getByRole("row", { name: /Quantity/ });
    expect(quantityRow).toHaveClass("is-diff");
    expect(within(quantityRow).getByText("4,900")).toBeInTheDocument();
    expect(within(quantityRow).getByText("4,870")).toBeInTheDocument();
    expect(within(drawer).getByRole("row", { name: /Price/ })).not.toHaveClass("is-diff");
    expect(window.location.search).toBe("?break=41");
  });

  it("closes on Escape", async () => {
    fakeApi();
    const user = await renderApp();
    await user.click(screen.getByRole("row", { name: /Break 41/ }));
    await screen.findByRole("dialog");

    await user.keyboard("{Escape}");

    expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
    expect(window.location.search).toBe("");
  });

  it("will not resolve without a reason, and never calls the API for it", async () => {
    const api = fakeApi();
    const user = await renderApp();
    await user.click(screen.getByRole("row", { name: /Break 41/ }));
    const drawer = await screen.findByRole("dialog");

    await user.click(await within(drawer).findByRole("button", { name: "Resolve" }));

    expect(within(drawer).getByText("A reason is required to resolve a break")).toBeInTheDocument();
    expect(within(drawer).getByLabelText("Resolution reason")).toHaveAttribute("aria-invalid", "true");
    expect(api.patchCalls()).toHaveLength(0);
  });

  it("resolves with a reason, sends the version it saw, and refreshes the queue", async () => {
    const resolved = { ...QUANTITY_BREAK, status: "RESOLVED", resolutionReason: "Broker corrected", resolvedAt: "2026-01-16T10:00:00Z", version: 4 };
    const api = fakeApi((call) => (call.method === "PATCH" ? { body: resolved } : undefined));
    const user = await renderApp();
    await user.click(screen.getByRole("row", { name: /Break 41/ }));
    const drawer = await screen.findByRole("dialog");
    const listCallsBefore = api.listCalls().length;

    await user.type(await within(drawer).findByLabelText("Resolution reason"), "Broker corrected");
    await user.click(within(drawer).getByRole("button", { name: "Resolve" }));

    await within(drawer).findByText("Broker corrected");
    expect(api.patchCalls()[0]!.body).toEqual({ status: "RESOLVED", reason: "Broker corrected", expectedVersion: 3 });
    expect(within(drawer).queryByRole("button", { name: "Resolve" })).not.toBeInTheDocument();
    await waitFor(() => expect(api.listCalls().length).toBeGreaterThan(listCallsBefore));
  });

  it("assigns an owner", async () => {
    const api = fakeApi((call) =>
      call.method === "PATCH" ? { body: { ...QUANTITY_BREAK, status: "ASSIGNED", owner: "j.smith", version: 4 } } : undefined);
    const user = await renderApp();
    await user.click(screen.getByRole("row", { name: /Break 41/ }));
    const drawer = await screen.findByRole("dialog");

    await user.type(await within(drawer).findByLabelText("Owner"), "j.smith");
    await user.click(within(drawer).getByRole("button", { name: "Assign" }));

    await waitFor(() => expect(within(drawer).getByText("Assigned")).toBeInTheDocument());
    expect(api.patchCalls()[0]!.body).toEqual({ owner: "j.smith", expectedVersion: 3 });
  });

  it("explains a conflicting edit and offers to reload", async () => {
    fakeApi((call) => call.method === "PATCH"
      ? { status: 409, body: { detail: "Break 41 is at version 4, not 3; it changed since you loaded it, so reload and try again" } }
      : undefined);
    const user = await renderApp();
    await user.click(screen.getByRole("row", { name: /Break 41/ }));
    const drawer = await screen.findByRole("dialog");

    await user.type(await within(drawer).findByLabelText("Add a note"), "Chasing broker");
    await user.click(within(drawer).getByRole("button", { name: "Add note" }));

    const alert = await within(drawer).findByRole("alert");
    expect(alert).toHaveTextContent("it changed since you loaded it");
    expect(within(alert).getByRole("button", { name: "Reload" })).toBeInTheDocument();
  });

  it("opens straight from a shared link", async () => {
    window.history.replaceState(null, "", "/?break=42");
    fakeApi();
    render(<App />);

    const drawer = await screen.findByRole("dialog", { name: "Break 42" });
    expect(await within(drawer).findByText(/business date 2026-01-15/)).toBeInTheDocument();
    expect(within(drawer).getByLabelText("Owner")).toHaveValue("a.jones");
  });
});

describe("errors", () => {
  it("shows the server's message when the queue can't load", async () => {
    fakeApi((call) => (call.url.pathname === "/api/breaks" ? { status: 400, body: { detail: "size must be less than or equal to 200" } } : undefined));
    render(<App />);

    expect(await screen.findByRole("alert")).toHaveTextContent("Could not load breaks: size must be less than or equal to 200");
  });
});
