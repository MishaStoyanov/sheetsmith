import SuggestionCard from './SuggestionCard.jsx';

const mono = "'JetBrains Mono', monospace";

// Only the visual taxonomy lives here. The sentence on the card comes from the backend, which
// narrates a step with the same describe() the chat uses — one wording, one place to change it.
const ACTION_META = {
  FORMAT_CELLS:           { mark: '◈', cat: 'Format',  ref: (p) => p.range },
  CREATE_CHART:           { mark: '▤', cat: 'Chart',   ref: (p) => p.sourceRange || 'Chart' },
  ADD_SHEET:              { mark: '⊞', cat: 'Sheet',   ref: (p) => p.name },
  ADD_FORMULA:            { mark: 'ƒ', cat: 'Formula', ref: (p) => p.cell },
  SORT_DATA:              { mark: '↕', cat: 'Sort',    ref: (p) => p.range },
  FILTER_DATA:            { mark: '⊟', cat: 'Filter',  ref: (p) => p.range },
  CONDITIONAL_FORMATTING: { mark: '◑', cat: 'Format',  ref: (p) => p.range },
  MERGE_CELLS:            { mark: '⊡', cat: 'Merge',   ref: (p) => p.range },
  CLEAR_CELLS:            { mark: '⌫', cat: 'Clear',   ref: (p) => p.range },
  RENAME_SHEET:           { mark: '✎', cat: 'Rename',  ref: (p) => p.newName },
  RENAME_COLUMN:          { mark: '✎', cat: 'Rename',  ref: (p) => p.cell },
  RENAME_CHART_TITLE:     { mark: '✎', cat: 'Chart',   ref: (p) => p.newTitle },
  RENAME_CHART_AXIS:      { mark: '✎', cat: 'Chart',   ref: (p) => p.axis === 'value' ? 'y-axis' : 'x-axis' },
};

// The engine grows faster than this table does, so an action missing from it must still read as
// something a person recognises. The category is derived from the name rather than shown raw —
// COLOR_SCALE reads "Color scale" — and the reference falls back to whichever of the usual keys the
// step happens to carry. A card is the only review step before a spreadsheet is changed; a raw enum
// on one is the frontend undoing what every describe() on the backend is careful about.
const FALLBACK_META = {
  mark: '•',
  ref: (p) => p.range || p.cell || p.name || p.sourceRange || p.dataRange || p.targetSheet,
};

function humanise(type) {
  if (!type) return 'Action';
  const words = String(type).replace(/_/g, ' ').trim().toLowerCase();
  return words.charAt(0).toUpperCase() + words.slice(1);
}

function resolveStepMeta(step) {
  const p = step.properties || {};
  const type = step.type?.toUpperCase();
  const known = ACTION_META[type];
  const meta = known ?? FALLBACK_META;
  return {
    mark: meta.mark,
    cat: known ? meta.cat : humanise(step.type),
    title: step.description || humanise(step.type) || 'Unknown action',
    ref: meta.ref(p) || '—',
  };
}

export default function SuggestionsPanel({ steps, onToggleStep, onEditStep, onCommitEdit, onApply }) {
  const activeCount = steps.filter(s => s.status !== 'dismissed').length;

  return (
    <div>
      <div style={{ display: 'flex', alignItems: 'center', gap: 12, marginBottom: 14 }}>
        <span style={{ fontFamily: mono, fontSize: 11, textTransform: 'uppercase', letterSpacing: '0.07em', color: 'var(--text-faint)', whiteSpace: 'nowrap' }}>
          Review suggested changes
        </span>
        <div style={{ flex: 1, height: 1, background: 'var(--border)' }} />
        <span style={{ fontFamily: mono, fontSize: 11, color: 'var(--text-faint)', whiteSpace: 'nowrap' }}>
          {activeCount} of {steps.length} selected
        </span>
      </div>

      <div style={{ display: 'grid', gridTemplateColumns: 'repeat(2, 1fr)', gap: 10 }}>
        {steps.map(step => {
          const meta = resolveStepMeta(step);
          return (
            <SuggestionCard
              key={step.index}
              item={{ ...meta, status: step.status, type: step.type }}
              properties={step.properties || {}}
              onToggle={() => onToggleStep(step.index, step.status === 'dismissed' ? 'applied' : 'dismissed')}
              onEdit={(key, value) => onEditStep(step.index, key, value)}
              onCommitEdit={onCommitEdit}
            />
          );
        })}
      </div>

      <div style={{ marginTop: 20, display: 'flex', justifyContent: 'flex-end', gap: 10 }}>
        {activeCount < steps.length && (
          <button
            onClick={() => steps.forEach(s => onToggleStep(s.index, 'applied'))}
            style={{ height: 36, padding: '0 16px', borderRadius: 9, border: '1px solid var(--border-strong)', background: 'transparent', color: 'var(--text-dim)', fontFamily: 'inherit', fontSize: 13, fontWeight: 500, cursor: 'pointer' }}
          >
            Select all
          </button>
        )}
        <button
          onClick={onApply}
          disabled={activeCount === 0}
          style={{ height: 36, padding: '0 22px', borderRadius: 9, border: 'none', background: activeCount > 0 ? 'var(--accent)' : 'var(--border)', color: activeCount > 0 ? 'var(--on-accent)' : 'var(--text-faint)', fontFamily: 'inherit', fontSize: 13.5, fontWeight: 600, cursor: activeCount > 0 ? 'pointer' : 'default', transition: 'background 0.2s, color 0.2s' }}
        >
          Apply {activeCount} change{activeCount !== 1 ? 's' : ''} →
        </button>
      </div>
    </div>
  );
}
