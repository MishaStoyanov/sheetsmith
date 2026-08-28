import { useEffect, useState } from 'react';
import { getSettings, updateSettings, getOllamaModels, getStorageSettings, updateStorageSettings } from './settingsApi.js';
import { formatBytes, fromSizeInput, toSizeInput } from './components/bytes.js';

const mono = "'JetBrains Mono', monospace";

import { CLOUD_PROVIDERS } from './providers.js';

const MODELS_CACHE_KEY = 'ss-ollama-models-cache';

function loadModelsCache() {
  try {
    return JSON.parse(localStorage.getItem(MODELS_CACHE_KEY) ?? '{}');
  } catch {
    return {};
  }
}

function saveModelsCache(baseUrl, models) {
  const cache = loadModelsCache();
  cache[baseUrl] = models;
  localStorage.setItem(MODELS_CACHE_KEY, JSON.stringify(cache));
}

/**
 * The instance's own configuration: which model it talks to, and where it keeps what it is given.
 *
 * Two tabs rather than one long form, and two saves rather than one. They are edited by different
 * people at different moments — the model is changed by whoever runs the thing day to day, the
 * archive by the one person allowed to move it — and a single Save carrying both would let a change
 * of model name relocate every file as a side effect.
 *
 * `maySetStorage` only hides the tab. The refusal itself is `@PreAuthorize` on the service, and it
 * holds for a request that never opened this panel.
 */
export default function SettingsPanel({ open, onClose, maySetStorage = false }) {
  const [settings, setSettings] = useState(null);
  const [loading, setLoading] = useState(false);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState(null);
  const [tab, setTab] = useState('model');

  const [ollamaModels, setOllamaModels] = useState([]);
  const [fetchingModels, setFetchingModels] = useState(false);
  const [modelsError, setModelsError] = useState(null);

  // What the server says is there right now, and the form over it. Kept apart so the usage figures
  // keep reading the disk rather than whatever has just been typed into the boxes above them.
  const [storage, setStorage] = useState(null);
  const [form, setForm] = useState({ rootDir: '', maxFiles: '', size: '', unit: 'MB' });

  /** Puts a saved answer back into the boxes, so the form always shows what the server holds. */
  const showStorage = (dto) => {
    setStorage(dto);
    const size = toSizeInput(dto.maxBytes);
    setForm({
      rootDir: dto.rootDir ?? '',
      maxFiles: dto.maxFiles == null ? '' : String(dto.maxFiles),
      size: size.value,
      unit: size.unit,
    });
  };

  useEffect(() => {
    if (!open) return;
    // The same reset-on-open shape as ChatPanel's, and the same reasoning: the fix is a key from the
    // parent, not a rewrite of the loading flags.
    // eslint-disable-next-line react-hooks/set-state-in-effect
    setLoading(true);
    setError(null);
    setModelsError(null);
    setTab('model');
    if (maySetStorage) {
      getStorageSettings().then(showStorage).catch(e => setError(e.message));
    }
    getSettings()
      .then(s => {
        setSettings(s);
        const cached = loadModelsCache()[s.local.baseUrl];
        if (cached) setOllamaModels(cached);
      })
      .catch(e => setError(e.message))
      .finally(() => setLoading(false));
  }, [open, maySetStorage]);

  const handleFetchOllamaModels = async () => {
    setFetchingModels(true);
    setModelsError(null);
    try {
      const models = await getOllamaModels(settings.local.baseUrl);
      setOllamaModels(models);
      saveModelsCache(settings.local.baseUrl, models);
    } catch (e) {
      setModelsError(e.message);
    } finally {
      setFetchingModels(false);
    }
  };

  if (!open) return null;

  const setMode = (mode) => setSettings(s => ({ ...s, providerMode: mode }));
  const setLocalField = (field, value) =>
    setSettings(s => ({ ...s, local: { ...s.local, [field]: value } }));
  const setCloudActive = (provider) =>
    setSettings(s => ({ ...s, cloud: { ...s.cloud, activeProvider: provider } }));
  const setCloudKey = (provider, value) =>
    setSettings(s => ({ ...s, cloud: { ...s.cloud, apiKeys: { ...s.cloud.apiKeys, [provider]: value } } }));
  const setCloudModel = (provider, value) =>
    setSettings(s => ({ ...s, cloud: { ...s.cloud, models: { ...s.cloud.models, [provider]: value } } }));

  const handleSave = async () => {
    setSaving(true);
    setError(null);
    try {
      if (tab === 'storage') {
        await saveStorage();
      } else {
        setSettings(await updateSettings(settings));
      }
      onClose();
    } catch (e) {
      setError(e.message);
    } finally {
      setSaving(false);
    }
  };

  /**
   * Empty boxes are the answer "no limit", not a missing one, so they are sent as null rather than
   * left out. A number that is not a number is caught here: the server refuses it too, but being
   * told before the request is the difference between a typo and a round trip.
   */
  const saveStorage = async () => {
    const maxFiles = form.maxFiles.trim() === '' ? null : Number(form.maxFiles);
    if (maxFiles !== null && (!Number.isInteger(maxFiles) || maxFiles < 1)) {
      throw new Error('Keep at most: a whole number of spreadsheets, or empty for no limit.');
    }
    const maxBytes = fromSizeInput(form.size, form.unit);
    if (Number.isNaN(maxBytes)) {
      throw new Error('Disk limit: a number above zero, or empty for no limit.');
    }
    showStorage(await updateStorageSettings({
      rootDir: form.rootDir.trim() === '' ? null : form.rootDir.trim(),
      maxFiles,
      maxBytes,
    }));
  };

  return (
    <div
      onClick={onClose}
      style={{ position: 'fixed', inset: 0, background: 'rgba(0,0,0,0.45)', display: 'flex', alignItems: 'center', justifyContent: 'center', zIndex: 100 }}
    >
      <div
        onClick={e => e.stopPropagation()}
        style={{ width: 560, maxHeight: '85vh', overflowY: 'auto', border: '1px solid var(--border-strong)', borderRadius: 16, background: 'var(--surface)', boxShadow: '0 20px 50px var(--shadow)' }}
      >
        {/* Header */}
        <div style={{ display: 'flex', alignItems: 'center', padding: '18px 22px', borderBottom: '1px solid var(--border)' }}>
          <span style={{ fontSize: 15, fontWeight: 700 }}>{maySetStorage ? 'Settings' : 'LLM settings'}</span>
          {maySetStorage && (
            <div style={{ display: 'flex', gap: 4, marginLeft: 16 }}>
              {[['model', 'Model'], ['storage', 'Storage']].map(([key, label]) => (
                <button
                  key={key}
                  onClick={() => setTab(key)}
                  aria-pressed={tab === key}
                  style={{
                    height: 28, padding: '0 12px', borderRadius: 8, cursor: 'pointer', fontFamily: 'inherit',
                    fontSize: 12.5, fontWeight: 600,
                    border: tab === key ? '1px solid var(--accent)' : '1px solid var(--border-strong)',
                    background: tab === key ? 'var(--accent-soft)' : 'transparent',
                    color: tab === key ? 'var(--accent-text)' : 'var(--text-dim)',
                  }}
                >
                  {label}
                </button>
              ))}
            </div>
          )}
          <button
            onClick={onClose}
            style={{ marginLeft: 'auto', width: 28, height: 28, borderRadius: 8, border: '1px solid var(--border-strong)', background: 'transparent', color: 'var(--text-dim)', fontSize: 14, cursor: 'pointer' }}
          >
            ✕
          </button>
        </div>

        {/* Body */}
        <div style={{ padding: '20px 22px' }}>
          {loading && <div style={{ fontSize: 13, color: 'var(--text-dim)' }}>Loading…</div>}

          {!loading && tab === 'model' && settings && (
            <>
              {/* Mode toggle */}
              <div style={{ display: 'flex', gap: 8, marginBottom: 20 }}>
                {['LOCAL', 'CLOUD'].map(mode => (
                  <button
                    key={mode}
                    onClick={() => setMode(mode)}
                    style={{
                      flex: 1, height: 38, borderRadius: 9, cursor: 'pointer', fontFamily: 'inherit', fontSize: 13, fontWeight: 600,
                      border: settings.providerMode === mode ? '1px solid var(--accent)' : '1px solid var(--border-strong)',
                      background: settings.providerMode === mode ? 'var(--accent-soft)' : 'transparent',
                      color: settings.providerMode === mode ? 'var(--accent-text)' : 'var(--text-dim)',
                    }}
                  >
                    {mode === 'LOCAL' ? 'Local model' : 'Cloud provider'}
                  </button>
                ))}
              </div>

              {settings.providerMode === 'LOCAL' && (
                <div style={{ display: 'flex', flexDirection: 'column', gap: 14 }}>
                  <Field label="Provider">
                    <select
                      value={settings.local.provider}
                      onChange={e => setLocalField('provider', e.target.value)}
                      style={selectStyle}
                    >
                      <option value="OLLAMA">Ollama</option>
                    </select>
                  </Field>
                  <Field label="Base URL">
                    <input
                      value={settings.local.baseUrl}
                      onChange={e => setLocalField('baseUrl', e.target.value)}
                      placeholder="http://host.docker.internal:11434"
                      style={inputStyle}
                    />
                    <div style={{ fontSize: 11.5, color: 'var(--text-faint)', marginTop: 6 }}>
                      If the backend runs in Docker (as in docker-compose.yml), use <code>host.docker.internal</code> instead of <code>localhost</code> — the container can’t reach the host machine via <code>localhost</code>.
                    </div>
                  </Field>
                  <Field label="Model">
                    {ollamaModels.length > 0 ? (
                      <div style={{ display: 'flex', gap: 8 }}>
                        <select
                          value={ollamaModels.includes(settings.local.model) ? settings.local.model : ''}
                          onChange={e => setLocalField('model', e.target.value)}
                          style={{ ...selectStyle, flex: 1 }}
                        >
                          <option value="" disabled>Pick a detected model…</option>
                          {ollamaModels.map(m => <option key={m} value={m}>{m}</option>)}
                        </select>
                        <button
                          onClick={handleFetchOllamaModels}
                          disabled={fetchingModels || !settings.local.baseUrl}
                          title="Refresh"
                          style={{ flexShrink: 0, width: 36, height: 36, borderRadius: 8, border: '1px solid var(--border-strong)', background: 'transparent', color: 'var(--text-dim)', fontFamily: 'inherit', fontSize: 13, cursor: fetchingModels ? 'default' : 'pointer' }}
                        >
                          {fetchingModels ? '…' : '↻'}
                        </button>
                      </div>
                    ) : (
                      <div style={{ display: 'flex', gap: 8 }}>
                        <input
                          value={settings.local.model}
                          onChange={e => setLocalField('model', e.target.value)}
                          placeholder="llama3.1"
                          style={{ ...inputStyle, flex: 1 }}
                        />
                        <button
                          onClick={handleFetchOllamaModels}
                          disabled={fetchingModels || !settings.local.baseUrl}
                          style={{ flexShrink: 0, height: 36, padding: '0 14px', borderRadius: 8, border: '1px solid var(--border-strong)', background: 'transparent', color: 'var(--text-dim)', fontFamily: 'inherit', fontSize: 12.5, fontWeight: 500, cursor: fetchingModels ? 'default' : 'pointer' }}
                        >
                          {fetchingModels ? 'Fetching…' : 'Fetch models'}
                        </button>
                      </div>
                    )}
                    {modelsError && (
                      <div style={{ fontSize: 11.5, color: 'var(--del)', marginTop: 6 }}>{modelsError}</div>
                    )}
                    {!modelsError && ollamaModels.length > 0 && (
                      <div style={{ fontSize: 11.5, color: 'var(--text-faint)', marginTop: 6 }}>
                        {ollamaModels.length} model{ollamaModels.length > 1 ? 's' : ''} found (cached — press ↻ to refresh).
                      </div>
                    )}
                  </Field>
                </div>
              )}

              {settings.providerMode === 'CLOUD' && (
                <div style={{ display: 'flex', flexDirection: 'column', gap: 14 }}>
                  <div style={{ padding: '10px 12px', borderRadius: 9, background: 'var(--warn-bg)', color: 'var(--warn)', fontSize: 12, lineHeight: 1.5 }}>
                    Privacy: with a cloud provider, no cell data is sent. Only the sheet schema
                    (column names, types) and sheet names are shared with the model to generate a plan.
                  </div>

                  <Field label="Active provider">
                    <select
                      value={settings.cloud.activeProvider}
                      onChange={e => setCloudActive(e.target.value)}
                      style={selectStyle}
                    >
                      {CLOUD_PROVIDERS.map(p => (
                        <option key={p.key} value={p.key}>{p.label}</option>
                      ))}
                    </select>
                  </Field>

                  {(() => {
                    const active = CLOUD_PROVIDERS.find(p => p.key === settings.cloud.activeProvider);
                    if (!active) return null;
                    return (
                      <div style={{ padding: '12px 14px', borderRadius: 10, border: '1px solid var(--border)', background: 'var(--surface-2)' }}>
                        <div style={{ fontSize: 12.5, fontWeight: 600, marginBottom: 8, color: 'var(--accent-text)' }}>
                          {active.label}
                        </div>
                        <div style={{ display: 'flex', flexDirection: 'column', gap: 8 }}>
                          <input
                            type="password"
                            value={settings.cloud.apiKeys[active.key] ?? ''}
                            onChange={e => setCloudKey(active.key, e.target.value)}
                            placeholder="API key"
                            style={inputStyle}
                          />
                          <input
                            value={settings.cloud.models[active.key] ?? ''}
                            onChange={e => setCloudModel(active.key, e.target.value)}
                            placeholder="Model name"
                            style={inputStyle}
                          />
                        </div>
                      </div>
                    );
                  })()}
                </div>
              )}
            </>
          )}

          {tab === 'storage' && storage && (
            <div style={{ display: 'flex', flexDirection: 'column', gap: 14 }}>
              {/* What is there now, measured off the disk rather than counted in the history: a
                  file left behind by a crash takes the space up either way. */}
              <div style={{ padding: '12px 14px', borderRadius: 10, border: '1px solid var(--border)', background: 'var(--surface-2)' }}>
                <div style={{ fontFamily: mono, fontSize: 11, textTransform: 'uppercase', letterSpacing: '0.06em', color: 'var(--text-faint)', marginBottom: 8 }}>
                  In use
                </div>
                <Gauge
                  label="Spreadsheets"
                  used={storage.fileCount}
                  limit={storage.maxFiles}
                  format={n => `${n}`}
                />
                <Gauge
                  label="Disk"
                  used={storage.bytesUsed}
                  limit={storage.maxBytes}
                  format={formatBytes}
                />
              </div>

              {storage.writable === false && (
                <div style={{ padding: '10px 12px', borderRadius: 9, background: 'var(--warn-bg)', color: 'var(--warn)', fontSize: 12, lineHeight: 1.5 }}>
                  The folder in use cannot be written to right now. Runs will fail on saving their
                  file until this is a folder the server may create files in.
                </div>
              )}

              <Field label="Folder">
                <input
                  value={form.rootDir}
                  onChange={e => setForm(f => ({ ...f, rootDir: e.target.value }))}
                  aria-label="Folder"
                  placeholder="Wherever the instance was started"
                  style={{ ...inputStyle, fontFamily: mono, fontSize: 12.5 }}
                />
                <div style={{ fontSize: 11.5, color: 'var(--text-faint)', marginTop: 6, lineHeight: 1.5 }}>
                  New files go here, in <code>uploads</code> and <code>results</code>. Files already
                  written stay where they are and are still downloadable — changing this moves
                  nothing, because a half-finished move leaves a history pointing at files that are
                  somewhere else.
                  <br />
                  Writing to <code>{storage.uploadDir}</code> now.
                </div>
              </Field>

              <Field label="Keep at most">
                <input
                  value={form.maxFiles}
                  onChange={e => setForm(f => ({ ...f, maxFiles: e.target.value }))}
                  aria-label="Keep at most"
                  placeholder="No limit"
                  inputMode="numeric"
                  style={inputStyle}
                />
                <div style={{ fontSize: 11.5, color: 'var(--text-faint)', marginTop: 6 }}>
                  Spreadsheets, counting each run’s input and result. Over it, the oldest finished
                  run goes first — a run still processing is never taken, however old it is.
                </div>
              </Field>

              <Field label="Disk limit">
                <div style={{ display: 'flex', gap: 8 }}>
                  <input
                    value={form.size}
                    onChange={e => setForm(f => ({ ...f, size: e.target.value }))}
                    aria-label="Disk limit"
                    placeholder="No limit"
                    inputMode="numeric"
                    style={{ ...inputStyle, flex: 1 }}
                  />
                  <select
                    value={form.unit}
                    onChange={e => setForm(f => ({ ...f, unit: e.target.value }))}
                    aria-label="Unit"
                    style={{ ...selectStyle, width: 90 }}
                  >
                    <option value="MB">MB</option>
                    <option value="GB">GB</option>
                  </select>
                </div>
                <div style={{ fontSize: 11.5, color: 'var(--text-faint)', marginTop: 6 }}>
                  Both limits hold at once, whichever is reached first. Leave either empty to keep
                  everything until the retention window takes it.
                </div>
              </Field>
            </div>
          )}

          {error && <div style={{ marginTop: 14, fontSize: 12.5, color: 'var(--del)' }}>{error}</div>}
        </div>

        {/* Footer */}
        <div style={{ display: 'flex', gap: 8, padding: '14px 22px', borderTop: '1px solid var(--border)' }}>
          <button
            onClick={onClose}
            style={{ height: 34, padding: '0 16px', borderRadius: 8, border: '1px solid var(--border-strong)', background: 'transparent', color: 'var(--text-dim)', fontFamily: 'inherit', fontSize: 13, fontWeight: 500, cursor: 'pointer' }}
          >
            Cancel
          </button>
          <button
            onClick={handleSave}
            disabled={saving || loading || (tab === 'storage' ? !storage : !settings)}
            style={{ marginLeft: 'auto', height: 34, padding: '0 18px', borderRadius: 8, border: 'none', background: 'var(--accent)', color: 'var(--on-accent)', fontFamily: 'inherit', fontSize: 13, fontWeight: 600, cursor: saving ? 'default' : 'pointer', opacity: saving ? 0.7 : 1 }}
          >
            {saving ? 'Saving…' : 'Save'}
          </button>
        </div>
      </div>
    </div>
  );
}

/**
 * How much of a limit is gone, or that there is no limit to be gone.
 *
 * The same rules as the spend bar: no ceiling is a state of its own rather than an empty track, the
 * fill stops at full while the figure keeps counting, and the percentage is written out so this is
 * readable without seeing colour.
 */
function Gauge({ label, used, limit, format }) {
  const percent = limit > 0 ? Math.round((used / limit) * 100) : 0;
  const tone = percent >= 100 ? 'var(--del)' : percent >= 80 ? 'var(--warn)' : 'var(--accent)';

  return (
    <div style={{ marginBottom: 10 }}>
      <div style={{ display: 'flex', alignItems: 'baseline', gap: 6, marginBottom: 4 }}>
        <span style={{ fontSize: 12, color: 'var(--text-dim)' }}>{label}</span>
        <span style={{ fontFamily: mono, fontSize: 12.5, color: 'var(--text)', marginLeft: 'auto' }}>
          {format(used)}
        </span>
        {limit == null ? (
          <span style={{ fontFamily: mono, fontSize: 12, color: 'var(--text-faint)' }}>· no limit</span>
        ) : (
          <>
            <span style={{ fontFamily: mono, fontSize: 12, color: 'var(--text-faint)' }}>/ {format(limit)}</span>
            <span style={{ fontFamily: mono, fontSize: 12, color: tone, width: 44, textAlign: 'right' }}>{percent}%</span>
          </>
        )}
      </div>
      {limit != null && (
        <div
          role="progressbar"
          aria-valuenow={percent}
          aria-valuemin={0}
          aria-valuemax={100}
          aria-label={`${percent}% of the ${label.toLowerCase()} limit used`}
          style={{ height: 5, borderRadius: 3, background: 'var(--surface)', overflow: 'hidden' }}
        >
          <div style={{ width: `${Math.min(100, percent)}%`, height: '100%', borderRadius: 3, background: tone, transition: 'width 0.2s' }} />
        </div>
      )}
    </div>
  );
}

function Field({ label, children }) {
  return (
    <label style={{ display: 'block' }}>
      <div style={{ fontFamily: mono, fontSize: 11, textTransform: 'uppercase', letterSpacing: '0.06em', color: 'var(--text-faint)', marginBottom: 6 }}>
        {label}
      </div>
      {children}
    </label>
  );
}

const inputStyle = {
  width: '100%', height: 36, padding: '0 12px', borderRadius: 8, border: '1px solid var(--border-strong)',
  background: 'var(--surface)', color: 'var(--text)', fontFamily: 'inherit', fontSize: 13, boxSizing: 'border-box',
};

const selectStyle = { ...inputStyle };
