# 0007: Break-queue UI

**Status:** accepted

- **The server filters, sorts and pages.** The queue can hold far more breaks than one page, so
  sorting in the browser would only reorder the 25 rows on screen. Column headers send
  `sort=field` or `sort=-field`, and `aria-sort` reflects what the server was asked for. The
  default is oldest first, because the break that has waited longest is the most urgent.
- **The view lives in the URL** (`?status=OPEN&type=QUANTITY_MISMATCH&break=44`). A filtered
  queue or a single break can be shared as a link, and Back/Forward work. Values it doesn't
  recognise fall back to defaults instead of erroring.
- **The drawer shows the matcher's verdict; it doesn't recompute it.** It highlights the field
  that recon-core reported. Comparing values in the browser would be wrong: `" us0378331005"`
  and `"US0378331005"` are the same ISIN, an alias is the same counterparty, and a price inside
  tolerance isn't a difference. Knock-on differences, like net amount following quantity, stay
  unhighlighted, exactly as the matcher reports only the root cause.
- **Edits carry `expectedVersion`.** If a run or a colleague changed the break meanwhile, the
  service answers 409 and the drawer says so and offers a reload. The edit isn't silently lost
  or applied to data the user never saw.
- **A resolve without a reason is stopped in the browser**, for an instant answer. The server
  enforces the same rule; the UI check is a convenience, not the control.
- **One origin.** Vite's dev server (development) and nginx (Docker) proxy `/api` to the
  service, so there is no CORS configuration to get wrong.
- **Money is displayed, never computed.** Decimals arrive as JSON numbers and are only formatted,
  so the browser's floating point can't change an amount.
