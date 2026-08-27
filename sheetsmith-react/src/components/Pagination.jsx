import Button from './Button.jsx';

const mono = "'JetBrains Mono', monospace";

/** Renders nothing at one page: controls that can only be disabled are noise. */
export default function Pagination({ page, totalPages, onChange }) {
  if (!totalPages || totalPages <= 1) return null;

  return (
    <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'center', gap: 14, marginTop: 20 }}>
      <Button onClick={() => onChange(Math.max(0, page - 1))} disabled={page === 0}>
        Previous
      </Button>
      <span style={{ fontFamily: mono, fontSize: 12, color: 'var(--text-faint)' }}>
        {page + 1} / {totalPages}
      </span>
      <Button onClick={() => onChange(Math.min(totalPages - 1, page + 1))} disabled={page >= totalPages - 1}>
        Next
      </Button>
    </div>
  );
}
