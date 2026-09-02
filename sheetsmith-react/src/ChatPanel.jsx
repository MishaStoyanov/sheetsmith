import { useEffect, useRef, useState } from 'react';
import { CHAT_WIDTH } from './chatPanelLayout.js';
import PromptRecall from './components/PromptRecall.jsx';
import { getFrequentPrompts } from './api.js';
import {
  getChatMessages,
  revertChatSession,
  sendChatMessage,
  streamChatMessage,
  StreamUnavailable,
} from './chatApi.js';

const mono = "'JetBrains Mono', monospace";

// Says what is enforced rather than what sounds strongest. The old wording — "never sees your
// sheet" — was true of every step and untrue of a turn: one READ_RANGE over the data range handed
// the model the whole table and reported it as a step's result. No read may do that now, so the
// line can promise it.
const PRIVACY_LINE = 'The model gets only what each step returns, never the whole sheet — and every step is listed.';

// ── Panel ───────────────────────────────────────────────────────────────────

export default function ChatPanel({
  open,
  onOpenChange,
  narrow,
  sessionId,
  sessionStarting,
  sessionError,
  hasFile,
  onMutated,
  onBeforeSend,
}) {
  const [messages, setMessages] = useState([]);
  const [input, setInput] = useState('');
  const [pastPrompts, setPastPrompts] = useState([]);
  const [sending, setSending] = useState(false);
  const [liveSteps, setLiveSteps] = useState([]);
  const [turnError, setTurnError] = useState(null);
  const [reverting, setReverting] = useState(null);
  const bottomRef = useRef(null);

  // A new session starts empty; reload history if one is handed back to us.
  useEffect(() => {
    // Reset-on-prop-change. The tidy fix is for App to give this component a key of the session id
    // so React remounts it, which is a change to how the panel is composed rather than to this file;
    // suppressed here deliberately rather than rewritten without a way to try the result.
    // eslint-disable-next-line react-hooks/set-state-in-effect
    setMessages([]);
    setTurnError(null);
    if (!sessionId) return;
    let cancelled = false;
    getChatMessages(sessionId)
      .then(history => { if (!cancelled && Array.isArray(history)) setMessages(history); })
      .catch(() => {});
    return () => { cancelled = true; };
  }, [sessionId]);

  useEffect(() => {
    bottomRef.current?.scrollIntoView({ block: 'end', behavior: 'smooth' });
  }, [messages.length, sending, liveSteps.length]);

  // Re-read after each turn, so a phrasing that has just become a habit turns up straight away.
  // Swallowed on failure like the improve side: this is a convenience, and an error over it would
  // be louder than the feature.
  useEffect(() => {
    if (!sessionId) return undefined;
    let live = true;
    getFrequentPrompts('CHAT', 4)
      .then(list => { if (live) setPastPrompts(list); })
      .catch(() => { if (live) setPastPrompts([]); });
    return () => { live = false; };
  }, [sessionId, messages.length]);

  const handleSend = async () => {
    const text = input.trim();
    if (!text || !sessionId || sending) return;

    // Claim the turn BEFORE the first await. Flushing edits on a large sheet takes seconds, and
    // while it ran `sending` was still false — every further Enter started another turn.
    setSending(true);
    setInput('');
    setTurnError(null);

    // The assistant reads the sheet server-side, so the user's uncommitted grid edits have to
    // land first — otherwise it answers about a sheet they can see but the server cannot.
    if (onBeforeSend && !(await onBeforeSend())) {
      setTurnError('Your unsaved edits could not be saved, so the assistant was not asked.');
      setInput(text);
      setSending(false);
      return;
    }

    setLiveSteps([]);
    setMessages(prev => [...prev, { id: `local-${Date.now()}`, role: 'user', content: text }]);
    try {
      let res;
      try {
        res = await streamChatMessage(sessionId, text, step => setLiveSteps(prev => [...prev, step]));
      } catch (e) {
        // Streaming is a nicety; a chat that still answers is the thing. Degrade without a word.
        if (!(e instanceof StreamUnavailable)) throw e;
        res = await sendChatMessage(sessionId, text);
      }
      if (res?.message) setMessages(prev => [...prev, res.message]);
      if (res?.mutated) await onMutated?.();
    } catch (e) {
      setTurnError(e.message);
    } finally {
      setSending(false);
      setLiveSteps([]);
    }
  };

  const handleUndo = async (msg) => {
    if (!sessionId || msg.revisionAfter == null) return;
    setReverting(msg.id);
    setTurnError(null);
    try {
      await revertChatSession(sessionId, msg.revisionAfter - 1);
      setMessages(prev => prev.map(m => (m.id === msg.id ? { ...m, reverted: true } : m)));
      await onMutated?.();
    } catch (e) {
      setTurnError(e.message);
    } finally {
      setReverting(null);
    }
  };

  if (!open) return <CollapsedTab onOpen={() => onOpenChange(true)} />;

  const composerDisabled = !sessionId || sending;

  return (
    <>
      {narrow && (
        <div
          onClick={() => onOpenChange(false)}
          style={{ position: 'fixed', top: 54, left: 0, right: 0, bottom: 0, background: 'rgba(0,0,0,0.45)', zIndex: 58 }}
        />
      )}

      <aside
        style={{
          position: 'fixed', top: 54, right: 0, bottom: 0, zIndex: 60,
          width: narrow ? 'min(380px, 100vw)' : CHAT_WIDTH,
          display: 'flex', flexDirection: 'column',
          background: 'var(--surface)', borderLeft: '1px solid var(--border-strong)',
          boxShadow: '-14px 0 36px var(--shadow)',
        }}
      >
        <PanelHeader onCollapse={() => onOpenChange(false)} />

        <div style={{ flex: 1, minHeight: 0, overflowY: 'auto', padding: '14px 14px 6px', display: 'flex', flexDirection: 'column', gap: 10 }}>
          {!hasFile && <EmptyState />}

          {hasFile && sessionStarting && !sessionId && (
            <Note tone="dim">Starting the assistant for this file…</Note>
          )}

          {sessionError && (
            <Note tone="del">
              Chat is unavailable — {sessionError}
              <div style={{ marginTop: 4, opacity: 0.75 }}>This session holds the sheet both flows work on, so improving is on hold too — drop the file again to retry.</div>
            </Note>
          )}

          {hasFile && sessionId && messages.length === 0 && !sending && <ReadyHint />}

          {messages.map(msg =>
            msg.role === 'user'
              ? <UserBubble key={msg.id} text={msg.content} />
              : <AssistantMessage
                  key={msg.id}
                  msg={msg}
                  onUndo={() => handleUndo(msg)}
                  reverting={reverting === msg.id}
                />
          )}

          {sending && <LiveTurn steps={liveSteps} />}
          {turnError && <Note tone="del">{turnError}</Note>}

          <div ref={bottomRef} />
        </div>

        {/* The same phrasings the improve field offers, asked of the chat side of the record.
            Only above an empty composer: once somebody is typing, a row of alternatives is
            something to dismiss rather than something to use. */}
        {sessionId && !input && pastPrompts.length > 0 && (
          <div style={{ padding: '0 14px 8px' }}>
            <PromptRecall prompts={pastPrompts} onPick={setInput} disabled={composerDisabled} />
          </div>
        )}

        <Composer
          value={input}
          onChange={setInput}
          onSend={handleSend}
          disabled={composerDisabled}
          placeholder={sessionId ? 'Ask about the sheet, or tell it what to change…' : 'Upload a spreadsheet to start…'}
        />
      </aside>
    </>
  );
}

// ── Chrome ──────────────────────────────────────────────────────────────────

function CollapsedTab({ onOpen }) {
  return (
    <button
      onClick={onOpen}
      title="Open the assistant"
      style={{
        position: 'fixed', right: 0, top: '50%', transform: 'translateY(-50%)', zIndex: 60,
        padding: '16px 9px', border: '1px solid var(--border-strong)', borderRight: 'none',
        borderRadius: '10px 0 0 10px', background: 'var(--surface)', color: 'var(--text-dim)',
        fontFamily: 'inherit', fontSize: 12, fontWeight: 600, letterSpacing: '0.04em',
        cursor: 'pointer', boxShadow: '-6px 0 18px var(--shadow)',
        display: 'flex', flexDirection: 'column', alignItems: 'center', gap: 8,
      }}
    >
      <span style={{ color: 'var(--accent)', fontSize: 13 }}>⟡</span>
      <span style={{ writingMode: 'vertical-rl' }}>Assistant</span>
      <span style={{ color: 'var(--text-faint)', fontSize: 11 }}>‹</span>
    </button>
  );
}

function PanelHeader({ onCollapse }) {
  return (
    <div style={{ padding: '13px 14px', borderBottom: '1px solid var(--border)', flexShrink: 0 }}>
      <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
        <span style={{ fontSize: 13.5, fontWeight: 700, letterSpacing: '-0.01em' }}>Assistant</span>
        <span style={{ fontFamily: mono, fontSize: 10.5, color: 'var(--text-faint)', textTransform: 'uppercase', letterSpacing: '0.07em' }}>
          applies as it goes
        </span>
        <button
          onClick={onCollapse}
          title="Collapse"
          style={{ marginLeft: 'auto', width: 26, height: 26, borderRadius: 7, border: '1px solid var(--border-strong)', background: 'transparent', color: 'var(--text-dim)', fontFamily: 'inherit', fontSize: 13, cursor: 'pointer', flexShrink: 0 }}
        >
          ›
        </button>
      </div>
      <div style={{ marginTop: 9, padding: '8px 10px', borderRadius: 9, background: 'var(--accent-soft)', color: 'var(--accent-text)', fontSize: 11, lineHeight: 1.5, display: 'flex', gap: 6 }}>
        <span style={{ color: 'var(--accent)', flexShrink: 0 }}>⟡</span>
        <span>{PRIVACY_LINE}</span>
      </div>
    </div>
  );
}

function Note({ tone, children }) {
  const del = tone === 'del';
  return (
    <div style={{
      padding: '9px 11px', borderRadius: 9, fontSize: 12, lineHeight: 1.55,
      background: del ? 'var(--del-bg)' : 'var(--surface-2)',
      border: `1px solid ${del ? 'var(--del-bg)' : 'var(--border)'}`,
      color: del ? 'var(--del)' : 'var(--text-dim)',
    }}>
      {children}
    </div>
  );
}

function EmptyState() {
  return (
    <div style={{ padding: '18px 14px', textAlign: 'center', color: 'var(--text-faint)' }}>
      <div style={{ fontSize: 24, marginBottom: 10, color: 'var(--text-faint)' }}>▦</div>
      <div style={{ fontSize: 12.5, fontWeight: 600, color: 'var(--text-dim)' }}>Chat unlocks after upload</div>
      <div style={{ fontSize: 11.5, lineHeight: 1.6, marginTop: 6 }}>
        Drop an .xlsx into the page and the assistant can read, calculate and edit it —
        at any point in the improve flow.
      </div>
    </div>
  );
}

function ReadyHint() {
  const examples = ['What is the total of column C?', 'Fill the empty cells in D with 0', 'Sort by revenue, highest first'];
  return (
    <div style={{ color: 'var(--text-faint)' }}>
      <div style={{ fontSize: 11.5, lineHeight: 1.6, marginBottom: 8 }}>
        Ask a question or ask for a change — edits are applied straight away, and every
        answer shows the steps behind it.
      </div>
      {examples.map(e => (
        <div key={e} style={{ fontFamily: mono, fontSize: 11, color: 'var(--text-faint)', padding: '4px 0' }}>· {e}</div>
      ))}
    </div>
  );
}

// ── Messages ────────────────────────────────────────────────────────────────

function UserBubble({ text }) {
  return (
    <div style={{
      alignSelf: 'flex-end', maxWidth: '88%', padding: '8px 11px',
      borderRadius: '12px 12px 4px 12px', background: 'var(--accent-soft)',
      border: '1px solid var(--border)', color: 'var(--text)',
      fontSize: 13, lineHeight: 1.55, whiteSpace: 'pre-wrap', wordBreak: 'break-word',
    }}>
      {text}
    </div>
  );
}

function AssistantMessage({ msg, onUndo, reverting }) {
  const steps = msg.steps ?? [];
  return (
    <div style={{ alignSelf: 'flex-start', maxWidth: '95%', width: '100%' }}>
      <div style={{
        padding: '9px 11px', borderRadius: '12px 12px 12px 4px',
        background: 'var(--surface-2)', border: '1px solid var(--border)',
        color: 'var(--text)', fontSize: 13, lineHeight: 1.6,
        whiteSpace: 'pre-wrap', wordBreak: 'break-word',
      }}>
        {msg.content}
      </div>

      {steps.length > 0 && <StepsDisclosure steps={steps} />}

      {msg.revisionAfter != null && (
        <ChangedRow reverted={msg.reverted} reverting={reverting} onUndo={onUndo} />
      )}
    </div>
  );
}

function ChangedRow({ reverted, reverting, onUndo }) {
  return (
    <div style={{ display: 'flex', alignItems: 'center', gap: 8, marginTop: 6, paddingLeft: 2, fontSize: 11 }}>
      <span style={{ color: 'var(--accent)' }}>⟡</span>
      <span style={{ color: 'var(--text-dim)' }}>
        {reverted ? 'Change undone' : 'Changed the sheet'}
      </span>
      {!reverted && (
        <button
          onClick={onUndo}
          disabled={reverting}
          style={{
            height: 22, padding: '0 9px', borderRadius: 7, border: '1px solid var(--border-strong)',
            background: 'transparent', color: 'var(--text-dim)', fontFamily: 'inherit',
            fontSize: 11, fontWeight: 500, cursor: reverting ? 'default' : 'pointer',
          }}
        >
          {reverting ? 'Undoing…' : 'Undo'}
        </button>
      )}
    </div>
  );
}

function StepsDisclosure({ steps }) {
  const [open, setOpen] = useState(false);
  const [raw, setRaw] = useState(false);
  const failed = steps.some(s => s.success === false);

  return (
    <div style={{ marginTop: 6 }}>
      <button
        onClick={() => setOpen(o => !o)}
        style={{
          display: 'inline-flex', alignItems: 'center', gap: 6, padding: '3px 8px 3px 6px',
          borderRadius: 7, border: '1px solid var(--border)', background: 'transparent',
          color: failed ? 'var(--del)' : 'var(--text-dim)', fontFamily: 'inherit',
          fontSize: 11, fontWeight: 500, cursor: 'pointer',
        }}
      >
        <span style={{ fontSize: 9, color: 'var(--text-faint)' }}>{open ? '▾' : '▸'}</span>
        How I got this · {steps.length} step{steps.length === 1 ? '' : 's'}
        {failed && <span style={{ color: 'var(--del)' }}>· 1 failed</span>}
      </button>

      {open && (
        <div style={{ marginTop: 6, padding: '8px 10px', border: '1px solid var(--border)', borderRadius: 9, background: 'var(--surface)' }}>
          {steps.map((step, i) => <StepRow key={step.order ?? i} step={step} index={i} raw={raw} />)}
          <button
            onClick={() => setRaw(r => !r)}
            title="Show the underlying tool calls"
            style={{
              marginTop: 6, padding: '2px 6px', borderRadius: 6, border: '1px solid var(--border)',
              background: 'transparent', color: 'var(--text-faint)', fontFamily: mono,
              fontSize: 10, cursor: 'pointer',
            }}
          >
            {raw ? 'hide raw' : 'raw'}
          </button>
        </div>
      )}
    </div>
  );
}

function StepRow({ step, index, raw }) {
  const failed = step.success === false;
  return (
    <div style={{ display: 'flex', gap: 8, padding: '5px 0', borderTop: index === 0 ? 'none' : '1px solid var(--border)' }}>
      <span style={{ fontFamily: mono, fontSize: 10.5, color: 'var(--text-faint)', flexShrink: 0, paddingTop: 1 }}>
        {step.order ?? index + 1}
      </span>
      <div style={{ minWidth: 0, flex: 1 }}>
        <div style={{ fontSize: 12, lineHeight: 1.5, color: failed ? 'var(--del)' : 'var(--text)' }}>
          {step.text}
          {step.mutating && (
            <span style={{ fontFamily: mono, fontSize: 9.5, color: 'var(--accent-text)', marginLeft: 6, textTransform: 'uppercase', letterSpacing: '0.06em' }}>
              edit
            </span>
          )}
        </div>
        {step.resultPreview && (
          <div style={{ fontFamily: mono, fontSize: 10.5, lineHeight: 1.5, color: 'var(--text-faint)', marginTop: 2, whiteSpace: 'pre-wrap', wordBreak: 'break-word' }}>
            {step.resultPreview}
          </div>
        )}
        {failed && step.error && (
          <div style={{ fontSize: 11, lineHeight: 1.5, color: 'var(--del)', marginTop: 2 }}>{step.error}</div>
        )}
        {raw && (
          <pre style={{ fontFamily: mono, fontSize: 10, lineHeight: 1.45, color: 'var(--text-faint)', background: 'var(--surface-2)', border: '1px solid var(--border)', borderRadius: 7, padding: '6px 8px', margin: '6px 0 0', whiteSpace: 'pre-wrap', wordBreak: 'break-word', overflowX: 'auto' }}>
            {step.tool}
            {step.args ? `\n${safeJson(step.args)}` : ''}
          </pre>
        )}
      </div>
    </div>
  );
}

function safeJson(value) {
  try {
    return typeof value === 'string' ? value : JSON.stringify(value, null, 2);
  } catch {
    return String(value);
  }
}

// ── Composer ────────────────────────────────────────────────────────────────

function Composer({ value, onChange, onSend, disabled, placeholder }) {
  const handleKeyDown = (e) => {
    if (e.key === 'Enter' && !e.shiftKey) {
      e.preventDefault();
      if (!disabled) onSend();
    }
  };

  const canSend = !disabled && value.trim().length > 0;

  return (
    <div style={{ flexShrink: 0, borderTop: '1px solid var(--border)', background: 'var(--surface)', padding: '10px 12px 12px' }}>
      <div style={{ border: '1px solid var(--border-strong)', borderRadius: 11, background: 'var(--surface-2)', padding: '8px 10px' }}>
        <textarea
          value={value}
          onChange={e => onChange(e.target.value)}
          onKeyDown={handleKeyDown}
          disabled={disabled}
          rows={2}
          placeholder={placeholder}
          style={{
            width: '100%', resize: 'none', border: 'none', outline: 'none', background: 'transparent',
            fontFamily: 'inherit', fontSize: 13, color: 'var(--text)', lineHeight: 1.55,
            maxHeight: 140, opacity: disabled ? 0.45 : 1,
          }}
        />
        <div style={{ display: 'flex', alignItems: 'center', gap: 8, marginTop: 2 }}>
          <span style={{ fontSize: 10.5, color: 'var(--text-faint)' }}>Enter to send · Shift+Enter for a new line</span>
          <button
            onClick={onSend}
            disabled={!canSend}
            style={{
              marginLeft: 'auto', height: 28, padding: '0 14px', borderRadius: 8, border: 'none',
              background: canSend ? 'var(--accent)' : 'var(--border)',
              color: canSend ? 'var(--on-accent)' : 'var(--text-faint)',
              fontFamily: 'inherit', fontSize: 12.5, fontWeight: 600,
              cursor: canSend ? 'pointer' : 'default', transition: 'background 0.2s, color 0.2s',
            }}
          >
            Send
          </button>
        </div>
      </div>
    </div>
  );
}

/**
 * The turn in progress. Deliberately the same rows as the finished "How I got this" disclosure:
 * the live view and the message it becomes should read as one thing, not two features.
 */
function LiveTurn({ steps }) {
  return (
    <div style={{ alignSelf: 'flex-start', width: '100%' }}>
      <div style={{ display: 'flex', alignItems: 'center', gap: 10, padding: '4px 2px' }}>
        <ChatDots />
        <span style={{ fontSize: 12, color: 'var(--text-dim)' }}>
          {steps.length === 0
            ? 'Working through it…'
            : `Working through it… · ${steps.length} step${steps.length === 1 ? '' : 's'}`}
        </span>
      </div>

      {steps.length > 0 && (
        <div style={{ marginTop: 6, padding: '8px 10px', border: '1px solid var(--border)', borderRadius: 9, background: 'var(--surface)' }}>
          {steps.map((step, i) => <StepRow key={step.order ?? i} step={step} index={i} raw={false} />)}
        </div>
      )}
    </div>
  );
}

// Same look as the ProcessingDots in App.jsx, kept local to avoid an import cycle.
function ChatDots() {
  return (
    <div style={{ display: 'flex', gap: 4, alignItems: 'center', flexShrink: 0 }}>
      {[0, 1, 2].map(i => (
        <div key={i} style={{ width: 6, height: 6, borderRadius: '50%', background: 'var(--accent)', animation: `ss-chat-pulse 1.2s ease-in-out ${i * 0.2}s infinite` }} />
      ))}
      <style>{`@keyframes ss-chat-pulse{0%,80%,100%{opacity:.2;transform:scale(.8)}40%{opacity:1;transform:scale(1)}}`}</style>
    </div>
  );
}
