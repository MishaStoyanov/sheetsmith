import { useCallback, useEffect, useState } from 'react';
import Badge from './components/Badge.jsx';
import Button from './components/Button.jsx';
import DataTable from './components/DataTable.jsx';
import Field from './components/Field.jsx';
import Modal from './components/Modal.jsx';
import Note from './components/Note.jsx';
import Pagination from './components/Pagination.jsx';
import CatalogueDialog from './components/CatalogueDialog.jsx';
import { applyCatalogue, deletePrice, patchPrice, previewCatalogue, putPrice, searchPrices } from './pricesApi.js';

const mono = "'JetBrains Mono', monospace";

/** What a provider charges, per million tokens, in and out. */
function rate(value) {
  const n = Number(value);
  return `$${n.toFixed(n < 1 ? 4 : 2)}`;
}

function when(value) {
  if (!value) return '—';
  return new Date(value).toLocaleDateString(undefined, { day: 'numeric', month: 'short', year: 'numeric' });
}

/**
 * The price list.
 *
 * Its own screen rather than a tab inside settings, and the difference is not cosmetic: settings
 * are about which model this instance talks to right now, while prices are a reference table that
 * outlives any of that — the price of a model you stopped using still explains what last quarter
 * cost.
 *
 * Two rates, not one. A provider charges differently for what it read and what it wrote, and a
 * single blended figure cannot answer "why was that expensive".
 */
export default function PricesScreen() {
  const [keyword, setKeyword] = useState('');
  const [page, setPage] = useState(0);
  const [data, setData] = useState(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState(null);

  const [editing, setEditing] = useState(null);
  const [confirmDelete, setConfirmDelete] = useState(null);
  const [catalogue, setCatalogue] = useState(false);

  const load = useCallback(() => {
    setLoading(true);
    return searchPrices(keyword || null, page)
      .then(result => { setData(result); setError(null); })
      .catch(e => setError(e.message))
      .finally(() => setLoading(false));
  }, [keyword, page]);

  useEffect(() => {
    // The fetch flips a loading flag on its way out, which the rule reads as a cascading render.
    // eslint-disable-next-line react-hooks/set-state-in-effect
    load();
  }, [load]);

  const after = async (work) => {
    try {
      await work();
      setError(null);
      await load();
      return true;
    } catch (e) {
      setError(e.message);
      return false;
    }
  };

  const columns = [
    {
      key: 'model',
      header: 'Model',
      render: price => (
        <span style={{ display: 'inline-flex', alignItems: 'center', gap: 8 }}>
          <span style={{ fontFamily: mono, fontSize: 12.5, color: 'var(--text-dim)' }}>{price.provider}</span>
          <span style={{ color: 'var(--text-faint)' }}>/</span>
          <span style={{ fontFamily: mono, fontSize: 12.5 }}>{price.model}</span>
          {price.usedByCalls > 0 && (
            <Badge>{price.usedByCalls === 1 ? '1 call' : `${price.usedByCalls} calls`}</Badge>
          )}
        </span>
      ),
    },
    {
      key: 'in',
      header: 'Input / 1M',
      align: 'right',
      render: price => <span style={{ fontFamily: mono, fontSize: 12.5 }}>{rate(price.inputPerMillion)}</span>,
    },
    {
      key: 'out',
      header: 'Output / 1M',
      align: 'right',
      render: price => <span style={{ fontFamily: mono, fontSize: 12.5 }}>{rate(price.outputPerMillion)}</span>,
    },
    {
      key: 'updated',
      header: 'Updated',
      align: 'right',
      render: price => <span style={{ fontSize: 12.5, color: 'var(--text-faint)' }}>{when(price.updatedAt)}</span>,
    },
    {
      key: 'actions',
      header: '',
      align: 'right',
      render: price => (
        <span style={{ display: 'inline-flex', gap: 6, whiteSpace: 'nowrap' }}>
          <Button size="sm" variant="ghost" onClick={() => setEditing(price)}>Edit</Button>
          <Button size="sm" variant="ghost" onClick={() => setConfirmDelete(price)} style={{ color: 'var(--del)' }}>
            Delete
          </Button>
        </span>
      ),
    },
  ];

  return (
    <div style={{ maxWidth: 940, margin: '0 auto', padding: '40px 28px 100px' }}>
      <div style={{ display: 'flex', alignItems: 'flex-start', gap: 16, marginBottom: 6 }}>
        <div style={{ flex: 1 }}>
          <h1 style={{ fontSize: 22, fontWeight: 700, letterSpacing: '-0.01em', margin: '0 0 6px' }}>Prices</h1>
          <p style={{ fontSize: 14, color: 'var(--text-dim)', margin: 0 }}>
            What each model charges per million tokens. Analytics can only show money for the models
            listed here.
          </p>
        </div>
        <div style={{ display: 'flex', gap: 8, flexShrink: 0 }}>
          <Button onClick={() => setCatalogue(true)}>Update from catalogue</Button>
          <Button variant="primary" onClick={() => setEditing({})}>Add price</Button>
        </div>
      </div>

      <div style={{ margin: '22px 0 18px' }}>
        <input
          value={keyword}
          onChange={e => { setKeyword(e.target.value); setPage(0); }}
          placeholder="Search by provider or model"
          style={{ height: 34, width: 260, padding: '0 10px', borderRadius: 8, border: '1px solid var(--border-strong)', background: 'var(--surface-2)', color: 'var(--text)', fontFamily: 'inherit', fontSize: 13, boxSizing: 'border-box' }}
        />
      </div>

      {error && (
        <div style={{ padding: '10px 14px', borderRadius: 8, background: 'var(--del-bg)', color: 'var(--del)', border: '1px solid var(--del)', fontSize: 13.5, marginBottom: 18 }}>
          {error}
        </div>
      )}

      {/* Said once, at the top, rather than repeated beside every row: a local model has no price
          because it does not charge, and that is not a gap to be filled in. */}
      {!loading && !keyword && (data?.content ?? []).length > 0 && (
        <Note>
          Models you run locally do not belong here — they cost no money to call, so analytics counts
          them in tokens and leaves them out of spend.
        </Note>
      )}

      <DataTable
        columns={columns}
        rows={data?.content ?? []}
        loading={loading}
        filtered={!!keyword}
        empty={{
          icon: '⌗',
          title: 'No prices yet',
          hint: 'Add one and analytics can start answering in money as well as tokens.',
        }}
        emptyFiltered={{ icon: '⌗', title: 'Nothing by that name', hint: 'Try a shorter search.' }}
      />

      <Pagination page={page} totalPages={data?.totalPages} onChange={setPage} />

      {/* Keyed so it starts from the row it was opened on: remounting is what makes the fields
          derive from the target without an effect writing over what somebody is typing. */}
      <PriceDialog
        key={editing ? (editing.id ?? 'new') : 'closed'}
        target={editing}
        onClose={() => setEditing(null)}
        onSubmit={async (form) => {
          // Editing an existing row patches only the figures: the provider and the model are its
          // address, and changing those would move the price to a different model rather than
          // correct this one.
          if (editing?.id) {
            return after(() => patchPrice(editing.id, {
              inputPerMillion: form.inputPerMillion,
              outputPerMillion: form.outputPerMillion,
            }));
          }
          return after(() => putPrice(form));
        }}
      />

      <CatalogueDialog
        open={catalogue}
        onClose={() => setCatalogue(false)}
        onLoad={previewCatalogue}
        onApply={rows => after(() => applyCatalogue(rows))}
      />

      <Modal
        open={!!confirmDelete}
        title="Delete this price?"
        onClose={() => setConfirmDelete(null)}
        footer={
          <>
            <Button onClick={() => setConfirmDelete(null)}>Cancel</Button>
            <Button
              variant="danger"
              onClick={async () => {
                if (await after(() => deletePrice(confirmDelete.id, true))) setConfirmDelete(null);
              }}
            >
              Delete
            </Button>
          </>
        }
      >
        <p style={{ fontSize: 13.5, color: 'var(--text-dim)', lineHeight: 1.6, margin: '0 0 10px' }}>
          <span style={{ fontFamily: mono, fontSize: 12.5, color: 'var(--text)' }}>
            {confirmDelete?.provider} / {confirmDelete?.model}
          </span>
        </p>
        {confirmDelete?.usedByCalls > 0 ? (
          <p style={{ fontSize: 13.5, color: 'var(--text-dim)', lineHeight: 1.6, margin: 0 }}>
            {/* Spend is worked out from this table every time the screen is drawn, so removing a
                row changes charts that have already been looked at. Said before, not after. */}
            <strong style={{ color: 'var(--warn)' }}>
              {confirmDelete.usedByCalls === 1
                ? '1 recorded call is priced by this row.'
                : `${confirmDelete.usedByCalls} recorded calls are priced by this row.`}
            </strong>{' '}
            Spend is worked out from the price list as the screen is drawn, so those calls will stop
            counting towards money — analytics will report a smaller total than it does now, and say
            the model is unpriced.
          </p>
        ) : (
          <p style={{ fontSize: 13.5, color: 'var(--text-dim)', lineHeight: 1.6, margin: 0 }}>
            No recorded call is priced by this row, so nothing already on the analytics screen
            changes.
          </p>
        )}
      </Modal>
    </div>
  );
}

/**
 * Add or correct one price.
 *
 * The same dialog for both, because they are the same operation at the API: provider plus model is
 * the address, so setting a price for a model nobody has priced yet is putting a value at an
 * address that happens to be empty.
 */
function PriceDialog({ target, onClose, onSubmit }) {
  const editing = !!target?.id;
  const [provider, setProvider] = useState(target?.provider ?? '');
  const [model, setModel] = useState(target?.model ?? '');
  const [input, setInput] = useState(target?.inputPerMillion == null ? '' : String(target.inputPerMillion));
  const [output, setOutput] = useState(target?.outputPerMillion == null ? '' : String(target.outputPerMillion));
  const [busy, setBusy] = useState(false);

  const numbers = [input, output].map(value => Number(value));
  const wellFormed = numbers.every(n => Number.isFinite(n) && n >= 0)
    && input.trim() !== '' && output.trim() !== ''
    && (editing || (provider.trim() && model.trim()));

  const submit = async () => {
    setBusy(true);
    const ok = await onSubmit({
      provider: provider.trim().toUpperCase(),
      model: model.trim(),
      inputPerMillion: numbers[0],
      outputPerMillion: numbers[1],
    });
    setBusy(false);
    if (ok) onClose();
  };

  return (
    <Modal
      open={!!target}
      title={editing ? `Price for ${target.model}` : 'Add price'}
      onClose={onClose}
      width={460}
      footer={
        <>
          <Button onClick={onClose}>Cancel</Button>
          <Button variant="primary" disabled={busy || !wellFormed} onClick={submit}>
            {busy ? 'Saving…' : 'Save'}
          </Button>
        </>
      }
    >
      {!editing && (
        <>
          <Field
            label="Provider"
            value={provider}
            onChange={e => setProvider(e.target.value)}
            placeholder="OPENAI"
            monospace
            hint="Written as the audit records it — OPENAI, ANTHROPIC, GEMINI, OLLAMA."
          />
          <Field
            label="Model"
            value={model}
            onChange={e => setModel(e.target.value)}
            placeholder="gpt-4o"
            monospace
            hint="Exactly as the provider names it; this is matched against what the audit recorded."
          />
        </>
      )}

      <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 12 }}>
        <Field
          label="Input per 1M"
          value={input}
          onChange={e => setInput(e.target.value)}
          placeholder="2.50"
          inputMode="decimal"
          monospace
        />
        <Field
          label="Output per 1M"
          value={output}
          onChange={e => setOutput(e.target.value)}
          placeholder="10.00"
          inputMode="decimal"
          monospace
        />
      </div>

      <p style={{ fontSize: 12.5, color: 'var(--text-faint)', lineHeight: 1.55, margin: '2px 0 0' }}>
        In US dollars per million tokens, as the provider publishes them. Two rates rather than one:
        reading and writing are charged differently, and a single blended figure cannot say why a
        run was expensive.
      </p>
    </Modal>
  );
}
