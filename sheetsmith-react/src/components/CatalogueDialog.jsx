import { useEffect, useState } from 'react';
import Badge from './Badge.jsx';
import Button from './Button.jsx';
import Checkbox from './Checkbox.jsx';
import Modal from './Modal.jsx';

const mono = "'JetBrains Mono', monospace";

const TONE = {
  NEW: 'good',
  CHANGED: 'warn',
  UNCHANGED: 'neutral',
  NOT_IN_CATALOGUE: 'neutral',
};

const LABEL = {
  NEW: 'new',
  CHANGED: 'changed',
  UNCHANGED: 'still correct',
  NOT_IN_CATALOGUE: 'not listed',
};

/**
 * Rows a person can act on.
 *
 * `UNCHANGED` is in here, which looks odd until you ask what the Prices screen needs. It marks a
 * price as stale by how long ago it was last checked, and a price the catalogue has just confirmed
 * *has* been checked — even though the number did not move. Saving it writes the same figures back
 * and refreshes the date, which is exactly the record that ought to exist.
 *
 * `NOT_IN_CATALOGUE` is not here: there is nothing to compare it against, so there is nothing to
 * confirm either.
 */
const ACTIONABLE = ['NEW', 'CHANGED', 'UNCHANGED'];

function rate(value) {
  if (value == null) return '—';
  const n = Number(value);
  return `$${n.toFixed(n < 1 ? 4 : 2)}`;
}

function rowKey(proposal) {
  return `${proposal.provider} ${proposal.model}`;
}

/**
 * What a published catalogue would change, offered for confirmation.
 *
 * The point of the whole dialog is that it is a proposal. Prices feed a screen about money, and a
 * figure that changed on its own is a figure nobody can account for — so nothing is written until
 * somebody has seen both numbers side by side and said yes.
 *
 * Rows that need no change are listed rather than filtered out. A refresh that showed only
 * differences would leave the reader unsure whether the rest were checked or quietly skipped, which
 * is the same doubt the feature exists to remove.
 */
export default function CatalogueDialog({ open, onClose, onLoad, onApply }) {
  const [state, setState] = useState({ status: 'loading' });
  const [chosen, setChosen] = useState(() => new Set());
  const [busy, setBusy] = useState(false);

  useEffect(() => {
    if (!open) return undefined;

    let live = true;
    // Opening the dialog is what starts the request, and the request reports its own progress —
    // which the rule reads as a cascading render. The `live` flag is the part that matters: a
    // dialog closed mid-fetch must not write into a component nobody is looking at.
    // eslint-disable-next-line react-hooks/set-state-in-effect
    setState({ status: 'loading' });
    onLoad()
      .then(data => {
        if (!live) return;
        setState({ status: 'ready', data });
        // Everything that would change starts ticked: the common case is accepting the lot, and
        // the dialog's job is to make that a decision rather than to make it laborious.
        setChosen(new Set(data.proposals.filter(p => ACTIONABLE.includes(p.status)).map(rowKey)));
      })
      .catch(e => { if (live) setState({ status: 'failed', message: e.message }); });

    return () => { live = false; };
  }, [open, onLoad]);

  const proposals = state.data?.proposals ?? [];
  const actionable = proposals.filter(p => ACTIONABLE.includes(p.status));
  const picked = actionable.filter(p => chosen.has(rowKey(p)));

  // What the button is about to do, which is not always "save a price". Confirming a set of rows
  // that all already agree changes no figure at all — it records that they were checked — and a
  // button reading "Save 4 prices" for that would be describing something else.
  const onlyConfirming = picked.length > 0 && picked.every(p => p.status === 'UNCHANGED');

  const toggle = (proposal) => {
    setChosen(previous => {
      const next = new Set(previous);
      const key = rowKey(proposal);
      if (next.has(key)) next.delete(key); else next.add(key);
      return next;
    });
  };

  const apply = async () => {
    setBusy(true);
    const ok = await onApply(picked.map(p => ({
      provider: p.provider,
      model: p.model,
      inputPerMillion: p.proposedInputPerMillion,
      outputPerMillion: p.proposedOutputPerMillion,
    })));
    setBusy(false);
    if (ok) onClose();
  };

  return (
    <Modal
      open={open}
      title="Published prices"
      onClose={onClose}
      width={620}
      footer={
        <>
          <Button onClick={onClose}>Cancel</Button>
          <Button
            variant="primary"
            disabled={busy || state.status !== 'ready' || picked.length === 0}
            onClick={apply}
          >
            {busy
              ? 'Saving…'
              : onlyConfirming
                ? (picked.length === 1 ? 'Confirm 1 price' : `Confirm ${picked.length} prices`)
                : (picked.length === 1 ? 'Save 1 price' : `Save ${picked.length} prices`)}
          </Button>
        </>
      }
    >
      {state.status === 'loading' && (
        <p style={{ fontSize: 13.5, color: 'var(--text-dim)', margin: 0 }}>Reading the catalogue…</p>
      )}

      {state.status === 'failed' && (
        <div style={{ padding: '10px 14px', borderRadius: 8, background: 'var(--del-bg)', color: 'var(--del)', border: '1px solid var(--del)', fontSize: 13.5 }}>
          {state.message}
        </div>
      )}

      {state.status === 'ready' && (
        <>
          {/* The host is named, because this is the one action here that leaves the machine. */}
          <p style={{ fontSize: 12.5, color: 'var(--text-faint)', lineHeight: 1.55, margin: '0 0 14px' }}>
            Read from <span style={{ fontFamily: mono }}>{state.data.source}</span>. Nothing is saved
            until you choose it. Confirming a price that has not changed records that it was checked
            today, which is what clears the stale mark on the list.
          </p>

          {proposals.length === 0 && (
            <p style={{ fontSize: 13.5, color: 'var(--text-dim)', margin: 0 }}>
              No cloud models have been used or priced here yet, so there is nothing to compare.
            </p>
          )}

          <div style={{ display: 'flex', flexDirection: 'column', gap: 2, maxHeight: 380, overflowY: 'auto' }}>
            {proposals.map(proposal => {
              const canApply = ACTIONABLE.includes(proposal.status);
              return (
                <div
                  key={rowKey(proposal)}
                  style={{
                    display: 'flex', alignItems: 'center', gap: 10,
                    padding: '8px 2px', borderBottom: '1px solid var(--border)',
                    opacity: canApply ? 1 : 0.6,
                  }}
                >
                  <span style={{ width: 20, flexShrink: 0 }}>
                    {canApply && (
                      <Checkbox checked={chosen.has(rowKey(proposal))} onChange={() => toggle(proposal)} />
                    )}
                  </span>

                  <span style={{ flex: 1, minWidth: 0 }}>
                    <span style={{ display: 'flex', alignItems: 'center', gap: 7, flexWrap: 'wrap' }}>
                      <span style={{ fontFamily: mono, fontSize: 12, color: 'var(--text-dim)' }}>
                        {proposal.provider}
                      </span>
                      <span style={{ fontFamily: mono, fontSize: 12 }}>{proposal.model}</span>
                      <Badge tone={TONE[proposal.status]}>{LABEL[proposal.status]}</Badge>
                    </span>
                    {/* An inexact match is spelled out rather than hidden behind the word "matched":
                        a dated snapshot is priced as the model it is a snapshot of, and that is a
                        judgement somebody should be able to overrule. */}
                    {proposal.catalogueModel && (
                      <span style={{ display: 'block', fontSize: 11.5, color: 'var(--text-faint)', marginTop: 2 }}>
                        priced as <span style={{ fontFamily: mono }}>{proposal.catalogueModel}</span>
                      </span>
                    )}
                  </span>

                  <span style={{ fontFamily: mono, fontSize: 11.5, whiteSpace: 'nowrap', textAlign: 'right' }}>
                    {proposal.status === 'NOT_IN_CATALOGUE' ? (
                      <span style={{ color: 'var(--text-faint)' }}>
                        {rate(proposal.currentInputPerMillion)} / {rate(proposal.currentOutputPerMillion)}
                      </span>
                    ) : proposal.status === 'NEW' ? (
                      <span style={{ color: 'var(--accent-text)' }}>
                        {rate(proposal.proposedInputPerMillion)} / {rate(proposal.proposedOutputPerMillion)}
                      </span>
                    ) : (
                      <>
                        <span style={{ color: 'var(--text-faint)', textDecoration: proposal.status === 'CHANGED' ? 'line-through' : 'none' }}>
                          {rate(proposal.currentInputPerMillion)} / {rate(proposal.currentOutputPerMillion)}
                        </span>
                        {proposal.status === 'CHANGED' && (
                          <span style={{ color: 'var(--text)' }}>
                            {' → '}
                            {rate(proposal.proposedInputPerMillion)} / {rate(proposal.proposedOutputPerMillion)}
                          </span>
                        )}
                      </>
                    )}
                  </span>
                </div>
              );
            })}
          </div>
        </>
      )}
    </Modal>
  );
}
