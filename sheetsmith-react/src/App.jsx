import { useState, useRef, useEffect } from 'react';
import { themes } from './theme.js';
import SheetGrid from './SheetGrid.jsx';
import SuggestionsPanel from './SuggestionsPanel.jsx';
import SettingsPanel from './SettingsPanel.jsx';
import LoginScreen from './LoginScreen.jsx';
import ChatPanel, { useChatPanelLayout } from './ChatPanel.jsx';
import { generatePlan, applyPlan, describeSteps, getJobStatus, suggestPlan } from './api.js';
import { getSettings, getCapabilities } from './settingsApi.js';
import { configureAuth, getCurrentUser, logout, restoreSession } from './authApi.js';
import { createChatSession, deleteChatSession, getChatFile, getChatSession, revertChatSession, saveChatEdits } from './chatApi.js';
import { parseWorkbook, applyEditsToBuffer } from './parseSheet.js';

const mono = "'JetBrains Mono', monospace";

// stage: 'upload' | 'analysing' | 'planning' | 'processing' | 'preview' | 'error'

export default function App() {
  const [theme, setTheme] = useState(() => localStorage.getItem('ss-theme') ?? 'dark');
  const setThemeAndSave = (t) => { setTheme(t); localStorage.setItem('ss-theme', t); };
  const [settingsOpen, setSettingsOpen] = useState(false);
  const [providerMode, setProviderMode] = useState('LOCAL');

  useEffect(() => {
    getSettings().then(s => setProviderMode(s.providerMode)).catch(() => {});
  }, []);

  // An older build has no /api/capabilities; treating that as "chat on" keeps it working, and a
  // server that really has the chat off answers the question rather than staying silent.
  // authEnabled defaults false for the same reason read the other way round: a build that does not
  // report it has no authentication either, so the honest fallback is "nobody is asked to log in".
  const [capabilities, setCapabilities] = useState({ chatEnabled: true, suggestionsEnabled: true, sendsOnlyStructure: false, authEnabled: false });

  // Who is signed in, and whether that question has been answered yet. The third state matters:
  // rendering the login screen while the cookie is still being exchanged would flash it in front
  // of someone who is signed in perfectly well.
  const [user, setUser] = useState(() => getCurrentUser());
  const [authChecked, setAuthChecked] = useState(false);

  useEffect(() => {
    getCapabilities()
      .then(async (caps) => {
        setCapabilities(caps);
        // Told once, so every API module can stay unaware of the difference.
        configureAuth(caps.authEnabled);
        if (caps.authEnabled) {
          // The access token never survives a reload — only the httpOnly cookie does, so the
          // session is restored by spending it rather than by reading anything back.
          const restored = await restoreSession();
          setUser(restored?.user ?? null);
        }
      })
      .catch(() => {})
      .finally(() => setAuthChecked(true));
  }, []);

  const handleSignOut = async () => {
    await logout();
    setUser(null);
    clearSheetAndChat();
    setFile(null);
    setStage('upload');
  };

  const handleCloseSettings = () => {
    setSettingsOpen(false);
    getSettings().then(s => setProviderMode(s.providerMode)).catch(() => {});
  };

  const [file, setFile] = useState(null);
  const [prompt, setPrompt] = useState('');
  const [stage, setStage] = useState('upload');
  const [dragging, setDragging] = useState(false);

  // planning stage
  const [planToken, setPlanToken] = useState(null);
  const [planSteps, setPlanSteps] = useState([]); // [{index, type, properties, status: 'applied'|'dismissed'}]

  // processing stage
  const [jobId, setJobId] = useState(null);
  const [jobStatus, setJobStatus] = useState(null);

  // preview stage — resultBuffer is only ever the bytes "Export .xlsx" writes out
  const [parsedSheet, setParsedSheet] = useState(null);
  // The charts the sheet actually has, as the server read them out of the current revision —
  // SheetJS cannot see embedded charts, so the browser has no way of knowing on its own.
  const [charts, setCharts] = useState([]);
  const [resultBuffer, setResultBuffer] = useState(null);
  const [cellEdits, setCellEdits] = useState([]);
  const [sheetRenames, setSheetRenames] = useState({});
  const [followUpPrompt, setFollowUpPrompt] = useState('');

  const [error, setError] = useState(null);
  const fileRef = useRef();
  const pollRef = useRef(null);

  // ── Session — the single home of the sheet, required by BOTH flows ─────────
  const chatLayout = useChatPanelLayout();
  const [chatSessionId, setChatSessionId] = useState(null);
  const [chatStarting, setChatStarting] = useState(false);
  const [chatError, setChatError] = useState(null);
  const [revision, setRevision] = useState(null);

  // Undo of the last improve run (preview stage only). runRevision is the revision that run
  // committed — once a chat turn moves the chain past it, "revision - 1" is no longer this run.
  const [runRevision, setRunRevision] = useState(null);
  const [runUndone, setRunUndone] = useState(false);
  const [undoingRun, setUndoingRun] = useState(false);
  const [undoError, setUndoError] = useState(null);

  // Manual grid edits are local until flushed to the session as a revision.
  const [savingEdits, setSavingEdits] = useState(false);
  const [editsError, setEditsError] = useState(null);
  const [suggesting, setSuggesting] = useState(false);

  // Everything the session says about where the chain stands, in one place — the chart list has
  // to move with the revision, or an undone chart would keep showing.
  const applySession = (session) => {
    setRevision(session.revision);
    setCharts(session.charts ?? []);
    return session.revision;
  };

  const startChatSession = async (f) => {
    setChatStarting(true);
    setChatError(null);
    try {
      const session = await createChatSession(f);
      setChatSessionId(session.sessionId);
      applySession(session);
    } catch (e) {
      setChatError(e.message);
    } finally {
      setChatStarting(false);
    }
  };

  // Show the sheet from the moment it is uploaded, before the session has answered.
  const loadLocalSheet = async (f) => {
    try {
      const buffer = await f.arrayBuffer();
      setResultBuffer(buffer);
      setParsedSheet(parseWorkbook(buffer));
      setCellEdits([]);
      setSheetRenames({});
    } catch {
      setParsedSheet(null);
    }
  };

  const clearSheetAndChat = () => {
    if (chatSessionId) deleteChatSession(chatSessionId).catch(() => {});
    setChatSessionId(null);
    setChatStarting(false);
    setChatError(null);
    setRevision(null);
    setRunRevision(null);
    setRunUndone(false);
    setUndoError(null);
    setParsedSheet(null);
    setCharts([]);
    setResultBuffer(null);
    setCellEdits([]);
    setSheetRenames({});
  };

  // The server's revision chain moved (chat turn, improve run, undo) → pull the current
  // revision, re-parse it, and re-read where the chain now stands.
  const refreshFromSession = async (sessionId = chatSessionId) => {
    if (!sessionId) return;
    const buffer = await getChatFile(sessionId);
    setResultBuffer(buffer);
    setParsedSheet(parseWorkbook(buffer));
    setCellEdits([]);
    setSheetRenames({});
    setRunUndone(false);
    return applySession(await getChatSession(sessionId));
  };

  // A chat turn changed the sheet → same path the improve flow takes.
  const handleChatMutation = async () => {
    await refreshFromSession();
  };

  const handleFile = (f) => {
    if (!f) return;
    setFile(f);
    loadLocalSheet(f);
    startChatSession(f);
  };

  // No session, no plan: /api/excel/plan works against the session's current revision.
  const sessionFailed = !!chatError && !chatSessionId;
  const sessionPreparing = !!file && chatStarting && !chatSessionId;
  const canAnalyse = !!file && !!chatSessionId && prompt.trim().length > 3;
  // Suggestions need no instruction — that is the whole point of the button.
  const canSuggest = !!file && !!chatSessionId && !suggesting;

  // ── Step 1: Analyse → generate plan ──────────────────────────────────────

  // No instruction needed: the assistant inspects the sheet and proposes the steps itself.
  const handleSuggest = async () => {
    if (!canSuggest || suggesting) return;
    if (!(await flushEdits()).ok) return;
    setSuggesting(true);
    setStage('analysing');
    setError(null);
    try {
      const { planToken: token, steps } = await suggestPlan(chatSessionId);
      setPlanToken(token);
      setPlanSteps(steps.map(s => ({ ...s, status: 'applied' })));
      setPrompt(prompt.trim() || 'What would you improve?');
      setStage('planning');
    } catch (e) {
      setError(e.message);
      setStage('error');
    } finally {
      setSuggesting(false);
    }
  };

  const handleAnalyse = async () => {
    if (!canAnalyse) return;
    if (!(await flushEdits()).ok) return;
    setStage('analysing');
    setError(null);
    try {
      const { planToken: token, steps } = await generatePlan(chatSessionId, prompt);
      setPlanToken(token);
      setPlanSteps(steps.map(s => ({ ...s, status: 'applied' })));
      setStage('planning');
    } catch (e) {
      setError(e.message);
      setStage('error');
    }
  };

  // ── Step 2: Apply selected steps ──────────────────────────────────────────

  const handleApply = async () => {
    const selectedSteps = planSteps.filter(s => s.status !== 'dismissed');
    if (selectedSteps.length === 0) return;
    setStage('processing');
    setJobStatus('PROCESSING');
    try {
      const id = await applyPlan(planToken, selectedSteps.map(({ index, type, properties }) => ({ index, type, properties })));
      setJobId(id);
    } catch (e) {
      setError(e.message);
      setStage('error');
    }
  };

  const handleToggleStep = (index, newStatus) => {
    setPlanSteps(prev => prev.map(s => s.index === index ? { ...s, status: newStatus } : s));
  };

  const handleEditStep = (index, key, value) => {
    setPlanSteps(prev => prev.map(s =>
      s.index === index ? { ...s, properties: { ...s.properties, [key]: value } } : s
    ));
  };

  // An edited step must not keep describing the range it no longer targets — re-narrate on blur.
  const handleCommitEdit = async () => {
    try {
      const described = await describeSteps(
        planSteps.map(({ index, type, properties }) => ({ index, type, properties }))
      );
      setPlanSteps(prev => prev.map(s => {
        const fresh = described.find(d => d.index === s.index);
        return fresh ? { ...s, description: fresh.description } : s;
      }));
    } catch {
      // keep the previous wording rather than blanking the card
    }
  };

  // ── Poll job status ───────────────────────────────────────────────────────

  useEffect(() => {
    if (!jobId || stage !== 'processing') return;

    pollRef.current = setInterval(async () => {
      try {
        const job = await getJobStatus(jobId);
        setJobStatus(job.status);

        if (job.status === 'COMPLETED' || job.status === 'PARTIAL') {
          clearInterval(pollRef.current);
          // The job wrote the session's next revision — read it back from there, no download.
          // A chart it created comes back with the session, same as one that was there all along.
          const committed = await refreshFromSession();
          setRunRevision(committed ?? null);
          setRunUndone(false);
          setUndoError(null);
          setStage('preview');
        } else if (job.status === 'FAILED') {
          clearInterval(pollRef.current);
          setError(job.errorMessage || 'The AI could not process this file. Try rephrasing the instruction.');
          setStage('error');
        }
      } catch (e) {
        clearInterval(pollRef.current);
        setError(e.message);
        setStage('error');
      }
    }, 3000);

    return () => clearInterval(pollRef.current);
  }, [jobId, stage]);

  // ── Reset ─────────────────────────────────────────────────────────────────

  const handleCellEdit = (sheetIdx, ri, ci, value) => {
    setCellEdits((prev) => {
      const filtered = prev.filter((e) => !(e.sheetIdx === sheetIdx && e.ri === ri && e.ci === ci));
      return [...filtered, { sheetIdx, ri, ci, value }];
    });
  };

  const handleRenameSheet = (sheetIdx, newName) => {
    const trimmed = newName.trim();
    if (!trimmed) return;
    setSheetRenames((prev) => ({ ...prev, [sheetIdx]: trimmed }));
  };

  // Manual edits live in the browser until this runs. Everything that reads the sheet
  // server-side — analyse, follow-up, a chat message, export — flushes them first, otherwise
  // the next refresh would drop them without a trace.
  // Returns { ok, buffer } — buffer is the committed bytes, since local state is stale until
  // React re-renders and callers need the fresh sheet immediately.
  const flushEdits = async () => {
    if (!chatSessionId || (cellEdits.length === 0 && Object.keys(sheetRenames).length === 0)) {
      return { ok: true, buffer: resultBuffer };
    }
    // Re-entry would post the same edits twice and, on a big sheet, queue a second slow write
    // behind the first — which is exactly how one edit became three revisions.
    if (savingEdits) {
      return { ok: false, buffer: resultBuffer };
    }
    setSavingEdits(true);
    setEditsError(null);
    try {
      const { revision: next } = await saveChatEdits(chatSessionId, cellEdits, sheetRenames);
      const buffer = await getChatFile(chatSessionId);
      setResultBuffer(buffer);
      setParsedSheet(parseWorkbook(buffer));
      setCellEdits([]);
      setSheetRenames({});
      setRevision(next);
      return { ok: true, buffer };
    } catch (e) {
      // Keep the edits — losing them silently is the bug this exists to prevent.
      setEditsError(e.message);
      return { ok: false, buffer: resultBuffer };
    } finally {
      setSavingEdits(false);
    }
  };

  const handleExport = async () => {
    const flushed = await flushEdits();
    if (!flushed.ok) return;
    // After a flush the server's bytes already carry the edits; the local patch is only for the
    // sessionless fallback.
    const patched = chatSessionId ? flushed.buffer : applyEditsToBuffer(resultBuffer, cellEdits, sheetRenames);
    const blob = new Blob([patched], { type: 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet' });
    const url = URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = url;
    a.download = file?.name?.replace(/\.xlsx$/i, '_improved.xlsx') ?? 'improved.xlsx';
    a.click();
    URL.revokeObjectURL(url);
  };

  const handleReset = () => {
    clearInterval(pollRef.current);
    clearSheetAndChat();
    setStage('upload');
    setFile(null);
    setPrompt('');
    setPlanToken(null);
    setPlanSteps([]);
    setJobId(null);
    setJobStatus(null);
    setParsedSheet(null);
    setResultBuffer(null);
    setCellEdits([]);
    setSheetRenames({});
    setFollowUpPrompt('');
    setError(null);
  };

  // ── Follow-up: refine the current result with another prompt ──────────────
  // No file changes hands — the session's current revision already IS the result.

  const handleFollowUp = async () => {
    const instruction = followUpPrompt.trim();
    if (!instruction || !chatSessionId) return;
    if (!(await flushEdits()).ok) return;

    setPrompt(instruction);
    setFollowUpPrompt('');
    setStage('analysing');
    setError(null);
    try {
      const { planToken: token, steps } = await generatePlan(chatSessionId, instruction);
      setPlanToken(token);
      setPlanSteps(steps.map(s => ({ ...s, status: 'applied' })));
      setStage('planning');
    } catch (e) {
      setError(e.message);
      setStage('error');
    }
  };

  // ── Undo the last improve run ─────────────────────────────────────────────
  // Same append-only chain as the chat's per-message undo: reverting to revision - 1
  // copies that older revision forward as a new one.

  // Only while the chain still ends at what this run committed — otherwise revision - 1
  // would undo whatever came after it instead.
  const canUndoRun = !!chatSessionId && !runUndone && revision != null && revision > 0
    && runRevision != null && revision === runRevision;
  const canFollowUp = !!chatSessionId && followUpPrompt.trim().length > 0;
  const hasLocalEdits = cellEdits.length > 0 || Object.keys(sheetRenames).length > 0;
  // Derived, never latched: undo takes a chart away again, and the flag has to follow.
  const hasChart = charts.length > 0;

  const handleUndoRun = async () => {
    if (!canUndoRun || undoingRun) return;
    setUndoingRun(true);
    setUndoError(null);
    try {
      await revertChatSession(chatSessionId, revision - 1);
      // Same path as any other move of the chain, so a chart the run added disappears with it.
      await refreshFromSession();
      setRunUndone(true);
    } catch (e) {
      setUndoError(e.message);
    } finally {
      setUndoingRun(false);
    }
  };

  const handleBackToPlanning = () => {
    setStage('planning');
    setError(null);
  };

  // Changed your mind about a set of suggestions: back to the file you already loaded, not a reset.
  // The session and the sheet stay exactly as they are; only the discarded plan goes.
  const handleBackToUpload = () => {
    setStage('upload');
    setPlanToken(null);
    setPlanSteps([]);
    setError(null);
  };

  // ── Render ────────────────────────────────────────────────────────────────

  // Nothing is rendered until it is known whether a login is needed, so the app never flashes a
  // screen the user does not need.
  if (capabilities.authEnabled && !authChecked) {
    return <div style={{ ...themes[theme], minHeight: '100vh', background: 'var(--canvas)' }} />;
  }

  if (capabilities.authEnabled && !user) {
    return <LoginScreen theme={theme} onSignedIn={setUser} />;
  }

  return (
    <div style={{ ...themes[theme], minHeight: '100vh', background: 'var(--canvas)', fontFamily: "'Instrument Sans', system-ui, sans-serif", color: 'var(--text)', boxSizing: 'border-box' }}>

      {/* Header */}
      <div style={{ height: 54, display: 'flex', alignItems: 'center', gap: 12, padding: '0 28px', background: 'var(--surface)', borderBottom: '1px solid var(--border)' }}>
        <div style={{ width: 22, height: 22, borderRadius: 6, background: 'var(--accent)', flexShrink: 0 }} />
        <span style={{ fontSize: 15, fontWeight: 700, letterSpacing: '-0.01em' }}>SheetSmith</span>
        <div style={{ flex: 1 }} />
        <span style={{ fontSize: 12, color: 'var(--text-faint)', display: 'flex', alignItems: 'center', gap: 5 }}>
          <span style={{ color: 'var(--accent)' }}>⟡</span> {providerMode === 'CLOUD' ? 'Cloud mode' : 'Local mode'}
        </span>
        <button
          onClick={() => setSettingsOpen(true)}
          title="LLM settings"
          style={{ marginLeft: 8, width: 32, height: 32, borderRadius: 8, border: '1px solid var(--border-strong)', background: 'transparent', color: 'var(--text-dim)', fontFamily: 'inherit', fontSize: 14, cursor: 'pointer' }}
        >
          ⚙
        </button>
        <button
          onClick={() => setThemeAndSave(theme === 'light' ? 'dark' : 'light')}
          style={{ marginLeft: 8, height: 32, padding: '0 14px', borderRadius: 8, border: '1px solid var(--border-strong)', background: 'transparent', color: 'var(--text-dim)', fontFamily: 'inherit', fontSize: 13, fontWeight: 500, cursor: 'pointer' }}
        >
          {theme === 'light' ? 'Dark' : 'Light'}
        </button>
        {user && (
          <button
            onClick={handleSignOut}
            title={`Signed in as ${user.name}`}
            style={{ marginLeft: 8, height: 32, padding: '0 14px', borderRadius: 8, border: '1px solid var(--border-strong)', background: 'transparent', color: 'var(--text-dim)', fontFamily: 'inherit', fontSize: 13, fontWeight: 500, cursor: 'pointer' }}
          >
            Sign out
          </button>
        )}
      </div>

      {/* Told to the person who has signed in, never to a stranger through /api/capabilities:
          "this instance still has its default password" is not a sentence to hand an anonymous
          caller. */}
      {user?.mustChangePassword && (
        <div style={{ padding: '10px 28px', background: 'var(--warn-bg)', color: 'var(--warn)', fontSize: 13, borderBottom: '1px solid var(--border)' }}>
          This account still has its default password. Change it before anyone else uses this instance.
        </div>
      )}

      <SettingsPanel open={settingsOpen} onClose={handleCloseSettings} />

      {/* Page — shrinks when the chat panel is docked on a wide screen. `--page-w` is what is left
          over, so a wide sheet preview knows how far out of the reading column it may spill. */}
      <div style={{ paddingRight: chatLayout.pageOffset, transition: 'padding-right 0.2s ease', '--page-w': `calc(100vw - ${chatLayout.pageOffset}px)` }}>
      <div style={{ maxWidth: stage === 'planning' ? 1100 : 760, margin: '0 auto', padding: '52px 28px 100px' }}>

        {/* Hero */}
        {stage === 'upload' && !file && (
          <div style={{ textAlign: 'center', marginBottom: 40 }}>
            <h1 style={{ fontSize: 34, fontWeight: 700, letterSpacing: '-0.02em', lineHeight: 1.15, margin: '0 0 14px' }}>
              Improve any spreadsheet,<br />right on your machine.
            </h1>
            <p style={{ fontSize: 15, color: 'var(--text-dim)', margin: 0, lineHeight: 1.6 }}>
              Drop in an .xlsx, describe what you want in plain words,<br />and review every change before it’s applied.
            </p>
          </div>
        )}

        {/* Upload + Prompt card */}
        {(stage === 'upload' || stage === 'analysing') && (
          <div style={{ border: '1px solid var(--border-strong)', borderRadius: 16, background: 'var(--surface)', boxShadow: '0 10px 30px var(--shadow)', overflow: 'hidden' }}>

            {/* File zone */}
            {!file ? (
              <div
                onDragOver={e => { e.preventDefault(); setDragging(true); }}
                onDragLeave={() => setDragging(false)}
                onDrop={e => { e.preventDefault(); setDragging(false); handleFile(e.dataTransfer.files[0]); }}
                onClick={() => fileRef.current?.click()}
                style={{ padding: '36px 24px', textAlign: 'center', cursor: 'pointer', borderBottom: '1px solid var(--border)', background: dragging ? 'var(--accent-soft)' : 'var(--surface-2)', transition: 'background 0.15s', userSelect: 'none' }}
              >
                <div style={{ fontSize: 30, marginBottom: 10, color: dragging ? 'var(--accent-text)' : 'var(--text-faint)' }}>▦</div>
                <div style={{ fontSize: 14, fontWeight: 600, color: dragging ? 'var(--accent-text)' : 'var(--text)' }}>
                  {dragging ? 'Drop it!' : 'Drop your .xlsx here'}
                </div>
                <div style={{ fontSize: 12.5, color: 'var(--text-faint)', marginTop: 4 }}>or click to browse · .xlsx files only</div>
                <input ref={fileRef} type="file" accept=".xlsx" style={{ display: 'none' }} onChange={e => handleFile(e.target.files[0])} />
              </div>
            ) : (
              <div style={{ display: 'flex', alignItems: 'center', gap: 12, padding: '14px 18px', background: 'var(--surface-2)', borderBottom: '1px solid var(--border)' }}>
                <div style={{ width: 36, height: 36, borderRadius: 9, background: 'var(--accent-soft)', color: 'var(--accent-text)', display: 'flex', alignItems: 'center', justifyContent: 'center', fontSize: 16, flexShrink: 0 }}>▦</div>
                <div style={{ minWidth: 0 }}>
                  <div style={{ fontFamily: mono, fontSize: 13, color: 'var(--text)', fontWeight: 500, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>{file.name}</div>
                  <div style={{ fontFamily: mono, fontSize: 11, color: 'var(--text-faint)', marginTop: 2 }}>{(file.size / 1024).toFixed(0)} KB</div>
                </div>
                {stage === 'upload' && (
                  <button
                    onClick={() => { setFile(null); setPrompt(''); clearSheetAndChat(); }}
                    style={{ marginLeft: 'auto', flexShrink: 0, height: 30, padding: '0 12px', borderRadius: 8, border: '1px solid var(--border-strong)', background: 'transparent', color: 'var(--text-dim)', fontFamily: 'inherit', fontSize: 12.5, fontWeight: 500, cursor: 'pointer' }}
                  >
                    Replace
                  </button>
                )}
              </div>
            )}

            {/* Prompt */}
            <div style={{ padding: '16px 18px 12px' }}>
              <textarea
                placeholder={!file ? 'Drop a file first, then describe what to improve…' : 'Describe what to improve — e.g. fix broken formulas, add quarterly totals, format as currency, add a bar chart…'}
                value={prompt}
                onChange={e => setPrompt(e.target.value)}
                disabled={!file || stage === 'analysing'}
                rows={3}
                style={{ width: '100%', resize: 'vertical', border: 'none', outline: 'none', background: 'transparent', fontFamily: 'inherit', fontSize: 14, color: 'var(--text)', lineHeight: 1.6, opacity: !file ? 0.35 : 1, minHeight: 72, boxSizing: 'border-box' }}
              />
            </div>

            {/* Footer */}
            <div style={{ display: 'flex', alignItems: 'center', gap: 12, padding: '10px 18px 16px', borderTop: '1px solid var(--border)' }}>
              <div style={{ fontSize: 11.5, color: sessionFailed ? 'var(--del)' : 'var(--text-faint)', display: 'flex', alignItems: 'center', gap: 5, minWidth: 0 }}>
                <span style={{ color: sessionFailed ? 'var(--del)' : 'var(--accent)', flexShrink: 0 }}>⟡</span>
                <span>
                  {sessionFailed
                    ? `Couldn't prepare this file — ${chatError}. Replace the file to try again.`
                    : sessionPreparing
                      ? 'Preparing your sheet…'
                      : providerMode === 'CLOUD'
                        ? 'Cloud mode — only the sheet schema is shared, never your cell data'
                        : 'Local mode — your file never leaves this machine'}
                </span>
              </div>
              {capabilities.suggestionsEnabled && (
              <button
                onClick={handleSuggest}
                disabled={!canSuggest || stage === 'analysing'}
                title="Let the assistant inspect the sheet and propose changes"
                style={{ marginLeft: 'auto', flexShrink: 0, height: 36, padding: '0 14px', borderRadius: 9, border: '1px solid var(--border-strong)', background: 'transparent', color: canSuggest ? 'var(--text-dim)' : 'var(--text-faint)', fontFamily: 'inherit', fontSize: 13, fontWeight: 500, cursor: canSuggest && stage !== 'analysing' ? 'pointer' : 'default', marginRight: 8 }}
              >
                {suggesting ? 'Looking…' : 'What would you improve?'}
              </button>)}
              <button
                onClick={handleAnalyse}
                disabled={!canAnalyse || stage === 'analysing'}
                style={{ flexShrink: 0, height: 36, padding: '0 20px', borderRadius: 9, border: 'none', background: canAnalyse && stage !== 'analysing' ? 'var(--accent)' : 'var(--border)', color: canAnalyse && stage !== 'analysing' ? 'var(--on-accent)' : 'var(--text-faint)', fontFamily: 'inherit', fontSize: 13.5, fontWeight: 600, cursor: canAnalyse && stage !== 'analysing' ? 'pointer' : 'default', transition: 'background 0.2s, color 0.2s' }}
              >
                {stage === 'analysing' ? 'Analysing…' : sessionPreparing ? 'Preparing…' : 'Analyse →'}
              </button>
            </div>
          </div>
        )}

        {/* Your sheet — visible from upload on, so chat edits are actually seen */}
        {stage === 'upload' && file && parsedSheet && (
          <div style={{ marginTop: 28 }}>
            <SectionLabel>Your sheet{cellEdits.length > 0 ? ` · ${cellEdits.length} unsaved edit${cellEdits.length > 1 ? 's' : ''}` : ''}</SectionLabel>
            <SheetGrid
              sheets={parsedSheet.sheets}
              charts={charts}
              hasChart={hasChart}
              theme={theme}
              cellEdits={cellEdits}
              onCellEdit={handleCellEdit}
              sheetRenames={sheetRenames}
              onRenameSheet={handleRenameSheet}
            />
            <div style={{ display: 'flex', alignItems: 'center', gap: 10, marginTop: 12 }}>
              <span style={{ fontSize: 11.5, color: 'var(--text-faint)' }}>
                Describe an improvement above, or ask the assistant on the right for a change right now.
              </span>
              <button
                onClick={handleExport}
                disabled={!resultBuffer}
                style={{ marginLeft: 'auto', flexShrink: 0, height: 30, padding: '0 14px', borderRadius: 7, border: '1px solid var(--border-strong)', background: 'transparent', color: 'var(--text-dim)', fontFamily: 'inherit', fontSize: 12.5, fontWeight: 500, cursor: resultBuffer ? 'pointer' : 'default' }}
              >
                Export .xlsx
              </button>
            </div>
          </div>
        )}

        {/* Analysing spinner */}
        {stage === 'analysing' && (
          <div style={{ marginTop: 20, padding: '22px 24px', border: '1px solid var(--border)', borderRadius: 14, background: 'var(--surface)', display: 'flex', alignItems: 'center', gap: 16 }}>
            <ProcessingDots />
            <div>
              <div style={{ fontSize: 13, fontWeight: 600, color: 'var(--text)' }}>Generating improvement plan…</div>
              <div style={{ fontSize: 12, color: 'var(--text-faint)', marginTop: 3 }}>AI is reading your spreadsheet</div>
            </div>
          </div>
        )}

        {/* Planning stage — suggestions panel */}
        {stage === 'planning' && (
          <>
            {/* File strip */}
            <div style={{ display: 'flex', alignItems: 'center', gap: 12, marginBottom: 28, padding: '10px 14px', border: '1px solid var(--border)', borderRadius: 10, background: 'var(--surface)' }}>
              <div style={{ width: 32, height: 32, borderRadius: 8, background: 'var(--accent-soft)', color: 'var(--accent-text)', display: 'flex', alignItems: 'center', justifyContent: 'center', fontSize: 15, flexShrink: 0 }}>▦</div>
              <div style={{ minWidth: 0 }}>
                <div style={{ fontFamily: mono, fontSize: 12.5, color: 'var(--text)', overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>{file?.name}</div>
                <div style={{ fontFamily: mono, fontSize: 11, color: 'var(--text-faint)', marginTop: 2, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>{prompt}</div>
              </div>
              <button onClick={handleBackToUpload} title="Keep the file, drop these suggestions" style={{ marginLeft: 'auto', flexShrink: 0, height: 30, padding: '0 12px', borderRadius: 7, border: '1px solid var(--border-strong)', background: 'transparent', color: 'var(--text-dim)', fontFamily: 'inherit', fontSize: 12.5, fontWeight: 500, cursor: 'pointer' }}>
                ← Back
              </button>
              <button onClick={handleReset} style={{ flexShrink: 0, height: 30, padding: '0 12px', borderRadius: 7, border: '1px solid var(--border-strong)', background: 'transparent', color: 'var(--text-dim)', fontFamily: 'inherit', fontSize: 12.5, fontWeight: 500, cursor: 'pointer', marginLeft: 8 }}>
                New file
              </button>
            </div>

            <SuggestionsPanel
              steps={planSteps}
              onToggleStep={handleToggleStep}
              onEditStep={handleEditStep}
              onCommitEdit={handleCommitEdit}
              onApply={handleApply}
            />
          </>
        )}

        {/* Processing status */}
        {stage === 'processing' && (
          <div style={{ marginTop: 20, padding: '22px 24px', border: '1px solid var(--border)', borderRadius: 14, background: 'var(--surface)', display: 'flex', alignItems: 'center', gap: 16 }}>
            <ProcessingDots />
            <div>
              <div style={{ fontSize: 13, fontWeight: 600, color: 'var(--text)' }}>Applying changes…</div>
              <div style={{ fontSize: 12, color: 'var(--text-faint)', marginTop: 3, fontFamily: mono }}>
                {jobId ? `job #${jobId}` : 'submitting…'}
                {jobStatus && ` · ${jobStatus.toLowerCase()}`}
              </div>
            </div>
          </div>
        )}

        {/* Error */}
        {stage === 'error' && (
          <div style={{ marginTop: 24, padding: '20px 24px', border: '1px solid var(--del-bg)', borderRadius: 14, background: 'var(--del-bg)' }}>
            <div style={{ fontSize: 13, fontWeight: 600, color: 'var(--del)' }}>Something went wrong</div>
            <div style={{ fontSize: 12.5, color: 'var(--del)', marginTop: 4, opacity: 0.8 }}>{error}</div>
            <div style={{ fontSize: 11.5, color: 'var(--del)', marginTop: 8, opacity: 0.65 }}>
              Often caused by the LLM provider — a hit rate limit, an expired quota, or a missing/wrong API key. Check Provider settings.
            </div>
            <div style={{ display: 'flex', gap: 8, marginTop: 14 }}>
              {planSteps.length > 0 && (
                <button onClick={handleBackToPlanning} style={{ height: 32, padding: '0 14px', borderRadius: 8, border: '1px solid var(--del)', background: 'transparent', color: 'var(--del)', fontFamily: 'inherit', fontSize: 12.5, fontWeight: 500, cursor: 'pointer' }}>
                  Back to suggestions
                </button>
              )}
              <button onClick={handleReset} style={{ height: 32, padding: '0 14px', borderRadius: 8, border: '1px solid var(--del)', background: 'transparent', color: 'var(--del)', fontFamily: 'inherit', fontSize: 12.5, fontWeight: 500, cursor: 'pointer' }}>
                Try again
              </button>
              <button onClick={() => setSettingsOpen(true)} style={{ height: 32, padding: '0 14px', borderRadius: 8, border: '1px solid var(--del)', background: 'transparent', color: 'var(--del)', fontFamily: 'inherit', fontSize: 12.5, fontWeight: 500, cursor: 'pointer' }}>
                Provider settings
              </button>
            </div>
          </div>
        )}

        {/* Preview */}
        {stage === 'preview' && parsedSheet && (
          <>
            {/* File strip */}
            <div style={{ display: 'flex', alignItems: 'center', gap: 12, marginBottom: 28, padding: '10px 14px', border: '1px solid var(--border)', borderRadius: 10, background: 'var(--surface)' }}>
              <div style={{ width: 32, height: 32, borderRadius: 8, background: 'var(--accent-soft)', color: 'var(--accent-text)', display: 'flex', alignItems: 'center', justifyContent: 'center', fontSize: 15, flexShrink: 0 }}>▦</div>
              <div style={{ fontFamily: mono, fontSize: 12.5, color: 'var(--text)', overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap', minWidth: 0 }}>{file?.name}</div>
              <div style={{ marginLeft: 'auto', display: 'flex', alignItems: 'center', gap: 8, flexShrink: 0 }}>
                <button onClick={handleReset} style={{ height: 30, padding: '0 12px', borderRadius: 7, border: '1px solid var(--border-strong)', background: 'transparent', color: 'var(--text-dim)', fontFamily: 'inherit', fontSize: 12.5, fontWeight: 500, cursor: 'pointer' }}>New file</button>
                {runUndone ? (
                  <span style={{ fontFamily: mono, fontSize: 11, color: 'var(--text-faint)' }}>run undone</span>
                ) : (
                  <button
                    onClick={handleUndoRun}
                    disabled={!canUndoRun || undoingRun}
                    title="Roll the sheet back to the revision before this run"
                    style={{ height: 30, padding: '0 12px', borderRadius: 7, border: '1px solid var(--border-strong)', background: 'transparent', color: 'var(--text-dim)', fontFamily: 'inherit', fontSize: 12.5, fontWeight: 500, cursor: canUndoRun && !undoingRun ? 'pointer' : 'default', opacity: canUndoRun ? 1 : 0.5 }}
                  >
                    {undoingRun ? 'Undoing…' : 'Undo this run'}
                  </button>
                )}
                {hasLocalEdits && (
                  <button
                    onClick={flushEdits}
                    disabled={savingEdits}
                    title="Commit your manual edits to the sheet"
                    style={{ height: 30, padding: '0 12px', borderRadius: 7, border: '1px solid var(--accent)', background: 'transparent', color: 'var(--accent-text)', fontFamily: 'inherit', fontSize: 12.5, fontWeight: 500, cursor: savingEdits ? 'default' : 'pointer' }}
                  >
                    {savingEdits ? 'Saving…' : `Save ${cellEdits.length || ''} edit${cellEdits.length === 1 ? '' : 's'}`.replace('  ', ' ')}
                  </button>
                )}
                <button onClick={handleExport} style={{ height: 30, padding: '0 14px', borderRadius: 7, border: 'none', background: 'var(--accent)', color: 'var(--on-accent)', fontFamily: 'inherit', fontSize: 12.5, fontWeight: 600, cursor: 'pointer', display: 'inline-flex', alignItems: 'center' }}>
                  Export .xlsx
                </button>
              </div>
            </div>

            {editsError && (
              <div style={{ marginTop: -16, marginBottom: 22, fontSize: 11.5, color: 'var(--del)' }}>
                Couldn’t save your edits — {editsError}. They are still here; try again.
              </div>
            )}

            {undoError && (
              <div style={{ marginTop: -16, marginBottom: 22, fontSize: 11.5, color: 'var(--del)' }}>Undo failed — {undoError}</div>
            )}

            <SectionLabel>Preview · improved sheet{cellEdits.length > 0 ? ` · ${cellEdits.length} unsaved edit${cellEdits.length > 1 ? 's' : ''}` : ''}</SectionLabel>
            <SheetGrid sheets={parsedSheet.sheets} charts={charts} hasChart={hasChart} theme={theme} cellEdits={cellEdits} onCellEdit={handleCellEdit} sheetRenames={sheetRenames} onRenameSheet={handleRenameSheet} />

            {/* Follow-up refinement */}
            <div style={{ marginTop: 20, border: '1px solid var(--border-strong)', borderRadius: 14, background: 'var(--surface)', overflow: 'hidden' }}>
              <div style={{ padding: '14px 16px 8px' }}>
                <textarea
                  placeholder="Not quite right? Describe another change — e.g. make headers bold, fix the totals row…"
                  value={followUpPrompt}
                  onChange={e => setFollowUpPrompt(e.target.value)}
                  disabled={stage !== 'preview'}
                  rows={2}
                  style={{ width: '100%', resize: 'vertical', border: 'none', outline: 'none', background: 'transparent', fontFamily: 'inherit', fontSize: 13.5, color: 'var(--text)', lineHeight: 1.6, minHeight: 48, boxSizing: 'border-box' }}
                />
              </div>
              <div style={{ display: 'flex', alignItems: 'center', gap: 12, padding: '8px 16px 14px', borderTop: '1px solid var(--border)' }}>
                <div style={{ fontSize: 11, color: 'var(--text-faint)', minWidth: 0 }}>
                  Runs another pass on this result — you’ll review the new suggestions before they’re applied.
                  {hasLocalEdits && ' Your manual cell edits stay local and are not part of it.'}
                </div>
                <button
                  onClick={handleFollowUp}
                  disabled={!canFollowUp}
                  style={{ marginLeft: 'auto', flexShrink: 0, height: 32, padding: '0 16px', borderRadius: 8, border: 'none', background: canFollowUp ? 'var(--accent)' : 'var(--border)', color: canFollowUp ? 'var(--on-accent)' : 'var(--text-faint)', fontFamily: 'inherit', fontSize: 12.5, fontWeight: 600, cursor: canFollowUp ? 'pointer' : 'default' }}
                >
                  Improve further →
                </button>
              </div>
            </div>
          </>
        )}
      </div>
      </div>

      {/* Chat assistant — available at every stage, from upload on, unless this instance has none */}
      {capabilities.chatEnabled && <ChatPanel
        open={chatLayout.open}
        onOpenChange={chatLayout.setOpen}
        narrow={chatLayout.narrow}
        sessionId={chatSessionId}
        sessionStarting={chatStarting}
        sessionError={chatError}
        hasFile={!!file}
        onMutated={handleChatMutation}
        onBeforeSend={async () => (await flushEdits()).ok}
      />}
    </div>
  );
}

function SectionLabel({ children }) {
  return (
    <div style={{ display: 'flex', alignItems: 'center', gap: 12, marginBottom: 14 }}>
      <span style={{ fontFamily: mono, fontSize: 11, textTransform: 'uppercase', letterSpacing: '0.07em', color: 'var(--text-faint)', whiteSpace: 'nowrap' }}>{children}</span>
      <div style={{ flex: 1, height: 1, background: 'var(--border)' }} />
    </div>
  );
}

function ProcessingDots() {
  return (
    <div style={{ display: 'flex', gap: 5, alignItems: 'center', flexShrink: 0 }}>
      {[0, 1, 2].map(i => (
        <div key={i} style={{ width: 7, height: 7, borderRadius: '50%', background: 'var(--accent)', animation: `pulse 1.2s ease-in-out ${i * 0.2}s infinite` }} />
      ))}
      <style>{`@keyframes pulse{0%,80%,100%{opacity:.2;transform:scale(.8)}40%{opacity:1;transform:scale(1)}}`}</style>
    </div>
  );
}
