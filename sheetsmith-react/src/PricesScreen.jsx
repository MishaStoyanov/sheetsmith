import { useCallback, useEffect, useState } from 'react';
import Badge from './components/Badge.jsx';
import Button from './components/Button.jsx';
import DataTable from './components/DataTable.jsx';
import Field from './components/Field.jsx';
import Modal from './components/Modal.jsx';
import Note from './components/Note.jsx';
import Pagination from './components/Pagination.jsx';
import CatalogueDialog from './components/CatalogueDialog.jsx';
import Select from './components/Select.jsx';
import { age } from './components/priceAge.js';
import { cloudProviderOptions } from './providers.js';
import { applyCatalogue, deletePrice, patchPrice, previewCatalogue, putPrice, searchPrices } from './pricesApi.js';

const mono = "'JetBrains Mono', monospace";

/** What a provider charges, per million tokens, in and out. */
function rate(value) {
  const n = Number(value);
  return `$${n.toFixed(n < 1 ? 4 : 2)}`;
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
export default function PricesScreen({ mayDelete }) {
  const [keyword, setKeyword] = useState('');
  const [page, setPage] = useState(0);
  const [data, setData] = useState(null);
  const [loadedAt, setLoadedAt] = useState(0);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState(null);

  const [editing, setEditing] = useState(null);
  const [confirmDelete, setConfirmDelete] = useState(null);
  const [catalogue, setCatalogue] = useState(false);

  const load = useCallback(() => {
    setLoading(true);
    return searchPrices(keyword || null, page)
      // Read here, not in render: an age measured against a moving clock is an age that changes
      // whenever the component redraws.
      .then(result => { setData(result); setLoadedAt(Date.now()); setError(null); })
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

  const staleCount = loadedAt === 0
    ? 0
    : (data?.content ?? []).filter(price => age(price.updatedAt, loadedAt).stale).length;

  const columns = [
    {
      key: 'model',
      header: 'Model',
      width: '38%',
      render: price => (
        // Wrapping is allowed between the name and the badge but never inside the name: a model
        // broken across three lines is what pushed this table into a sideways scroll.
        <span style={{ display: 'flex', alignItems: 'center', gap: 8, flexWrap: 'wrap' }}>
          <span style={{ fontFamily: mono, fontSize: 12.5, whiteSpace: 'nowrap' }}>
            <span style={{ color: 'var(--text-dim)' }}>{price.provider}</span>
            <span style={{ color: 'var(--text-faint)' }}> / </span>
            {price.model}
          </span>
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
      header: 'Checked',
      align: 'right',
      render: price => {
        const { text, stale } = age(price.updatedAt, loadedAt);
        return (
          <span
            title={stale ? 'Last confirmed a while ago — providers change their prices.' : undefined}
            style={{
              fontSize: 12.5, whiteSpace: 'nowrap',
              color: stale ? 'var(--warn)' : 'var(--text-faint)',
              textDecoration: stale ? 'underline dotted' : 'none',
              textUnderlineOffset: 3,
            }}
          >
            {text}
          </span>
        );
      },
    },
    {
      key: 'actions',
      header: '',
      align: 'right',
      render: price => (
        <span style={{ display: 'inline-flex', gap: 6, whiteSpace: 'nowrap' }}>
          <Button size="sm" variant="ghost" onClick={() => setEditing(price)}>Edit</Button>
          {/* Editing stays open to administrators — a wrong price shows up in the figures it
              produces, and the next one to look can put it back. Removing one takes the meaning of
              every call that used it, so it goes with the other deletions. */}
          {mayDelete && (
            <Button size="sm" variant="ghost" onClick={() => setConfirmDelete(price)} style={{ color: 'var(--del)' }}>
              Delete
            </Button>
          )}
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

      {/* The underline on a row says "this one"; this says "and here is what to do about it".
          Checking against the catalogue clears the mark even when nothing has changed. */}
      {staleCount > 0 && (
        <Note>
          {staleCount === 1 ? 'One price has' : `${staleCount} prices have`} not been checked in
          months, and providers move theirs. <strong>Update from catalogue</strong> compares them
          against published figures — confirming one that has not changed is enough to clear this.
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
  // Defaulted to the first option rather than empty: a dropdown shows its first entry whatever the
  // state says, and starting blank means an untouched field looks chosen and submits nothing.
  const [provider, setProvider] = useState(target?.provider ?? cloudProviderOptions()[0].value);
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
          {/* Chosen, not typed. The provider is half the key a recorded call is matched on, so a
              spelling nobody can see is wrong — ANTHROPIC where the audit writes CLAUDE — produces
              a price that silently never applies to anything. The list is the same one the settings
              screen uses, from the same file, so the two cannot drift apart.

              Local models are absent on purpose rather than by oversight: they charge nothing, and
              the note above this table says so. */}
          <Select
            label="Provider"
            value={provider}
            onChange={e => setProvider(e.target.value)}
            options={cloudProviderOptions()}
            style={{ marginBottom: 14 }}
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
