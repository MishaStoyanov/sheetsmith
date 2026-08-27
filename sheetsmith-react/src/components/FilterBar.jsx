import Button from './Button.jsx';

/**
 * The row of filters, with one thing the controls cannot say for themselves: how many are on.
 *
 * A screen that quietly shows a filtered list is the commonest way for someone to conclude their
 * data is gone. The count, and a way to clear it in one click, is what stops that.
 */
export default function FilterBar({ activeCount = 0, onClear, children }) {
  return (
    <div style={{ border: '1px solid var(--border)', borderRadius: 12, background: 'var(--surface)', padding: '14px 16px', marginBottom: 18 }}>
      <div style={{ display: 'flex', flexWrap: 'wrap', gap: 12, alignItems: 'flex-end' }}>
        {children}
      </div>

      {activeCount > 0 && (
        <div style={{ display: 'flex', alignItems: 'center', gap: 10, marginTop: 12, paddingTop: 12, borderTop: '1px solid var(--border)' }}>
          <span style={{ fontSize: 12.5, color: 'var(--text-dim)' }}>
            {activeCount === 1 ? '1 filter applied' : `${activeCount} filters applied`}
          </span>
          <Button size="sm" variant="ghost" onClick={onClear}>Clear all</Button>
        </div>
      )}
    </div>
  );
}
