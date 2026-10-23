import type { Page } from "../api";

interface Props {
  page?: Page<unknown>;
  onPage: (page: number) => void;
}

export function Pagination({ page, onPage }: Props) {
  if (!page || page.totalItems === 0) return null;
  const first = page.page * page.size + 1;
  const last = Math.min(first + page.items.length - 1, page.totalItems);
  return (
    <nav className="pagination" aria-label="Pagination">
      <span>
        {first.toLocaleString("en-US")}–{last.toLocaleString("en-US")} of {page.totalItems.toLocaleString("en-US")}
      </span>
      <button type="button" className="button button--ghost" disabled={page.page === 0} onClick={() => onPage(page.page - 1)}>
        Previous
      </button>
      <button type="button" className="button button--ghost" disabled={page.page + 1 >= page.totalPages} onClick={() => onPage(page.page + 1)}>
        Next
      </button>
    </nav>
  );
}
