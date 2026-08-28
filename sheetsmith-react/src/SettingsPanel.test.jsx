import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';

vi.mock('./settingsApi.js', () => ({
  getSettings: vi.fn(),
  updateSettings: vi.fn(),
  getOllamaModels: vi.fn(),
  getStorageSettings: vi.fn(),
  updateStorageSettings: vi.fn(),
}));

import SettingsPanel from './SettingsPanel.jsx';
import { getSettings, getStorageSettings, updateSettings, updateStorageSettings } from './settingsApi.js';

/**
 * The storage tab: what it shows about the disk, and what it sends when somebody sets a cap.
 *
 * The refusal itself is not tested here — it is a `@PreAuthorize` on the server and has its own
 * test against a real request. What is tested here is that the screen does not offer the tab to
 * somebody the server would refuse, which is a different claim and the only one a screen can make.
 */

const llm = {
  providerMode: 'CLOUD',
  local: { provider: 'OLLAMA', baseUrl: 'http://localhost:11434', model: 'llama3.1' },
  // What the server actually answers now: no keys, only which providers have one.
  cloud: { activeProvider: 'OPENAI', apiKeys: {}, models: { OPENAI: 'gpt-4o' }, savedKeys: ['OPENAI'] },
};

const storage = {
  rootDir: null,
  maxFiles: 100,
  maxBytes: null,
  uploadDir: 'C:\\sheetsmith\\uploads',
  resultDir: 'C:\\sheetsmith\\results',
  fileCount: 42,
  bytesUsed: 5 * 1024 * 1024,
  writable: true,
};

beforeEach(() => {
  vi.clearAllMocks();
  getSettings.mockResolvedValue(llm);
  getStorageSettings.mockResolvedValue(storage);
  updateStorageSettings.mockImplementation(async (update) => ({ ...storage, ...update }));
  updateSettings.mockImplementation(async (dto) => dto);
});

const openStorage = async () => {
  render(<SettingsPanel open maySetStorage onClose={() => {}} />);
  await screen.findByRole('button', { name: 'Storage' });
  await userEvent.click(screen.getByRole('button', { name: 'Storage' }));
};

describe('SettingsPanel keys', () => {
  it('says a key is saved without showing it, and sends nothing when it is not touched', async () => {
    render(<SettingsPanel open maySetStorage onClose={() => {}} />);

    const box = await screen.findByPlaceholderText('Key saved — leave blank to keep it');
    expect(box).toHaveValue('');

    await userEvent.click(screen.getByRole('button', { name: 'Save' }));

    await waitFor(() => expect(updateSettings).toHaveBeenCalled());
    // Not named at all: naming it blank is how the server is told to remove it, and a save that
    // said that about every untouched provider would wipe the keys of anybody who opened this.
    expect(updateSettings.mock.calls[0][0].cloud.apiKeys).toEqual({});
  });

  it('clearing a typed key is the way to remove it, and says so', async () => {
    render(<SettingsPanel open maySetStorage onClose={() => {}} />);

    const box = await screen.findByPlaceholderText('Key saved — leave blank to keep it');
    await userEvent.type(box, 'sk-new');
    await userEvent.clear(box);

    expect(screen.getByText(/will be removed when you save/)).toBeInTheDocument();
    await userEvent.click(screen.getByRole('button', { name: 'Save' }));
    await waitFor(() => expect(updateSettings.mock.calls[0][0].cloud.apiKeys).toEqual({ OPENAI: '' }));
  });
});

describe('SettingsPanel storage', () => {
  it('offers nothing about storage to somebody who may not set it', async () => {
    render(<SettingsPanel open onClose={() => {}} />);

    await screen.findByText('LLM settings');
    expect(screen.queryByRole('button', { name: 'Storage' })).not.toBeInTheDocument();
    // Not asked for either: a panel that fetched it and hid the result would still be a request the
    // server has to refuse.
    expect(getStorageSettings).not.toHaveBeenCalled();
  });

  it('shows what is on the disk, and where it is being written', async () => {
    await openStorage();

    expect(screen.getByText('5.0 MB')).toBeInTheDocument();
    expect(screen.getByText('42')).toBeInTheDocument();
    expect(screen.getByText(/C:\\sheetsmith\\uploads/)).toBeInTheDocument();
  });

  it('says "no limit" rather than drawing an empty gauge', async () => {
    await openStorage();

    // The size cap is unset, the count cap is not: one is a sentence, the other a bar.
    expect(screen.getByText('· no limit')).toBeInTheDocument();
    expect(screen.getAllByRole('progressbar')).toHaveLength(1);
  });

  it('sends a size cap in bytes and an empty box as no limit', async () => {
    await openStorage();

    await userEvent.type(screen.getByLabelText('Disk limit'), '2');
    await userEvent.selectOptions(screen.getByLabelText('Unit'), 'GB');
    await userEvent.clear(screen.getByLabelText('Keep at most'));
    await userEvent.click(screen.getByRole('button', { name: 'Save' }));

    await waitFor(() => expect(updateStorageSettings).toHaveBeenCalledWith({
      rootDir: null,
      maxFiles: null,
      maxBytes: 2 * 1024 * 1024 * 1024,
    }));
  });

  it('refuses a cap that is not a number before it asks the server to', async () => {
    await openStorage();

    await userEvent.clear(screen.getByLabelText('Keep at most'));
    await userEvent.type(screen.getByLabelText('Keep at most'), 'lots');
    await userEvent.click(screen.getByRole('button', { name: 'Save' }));

    expect(await screen.findByText(/whole number of spreadsheets/)).toBeInTheDocument();
    expect(updateStorageSettings).not.toHaveBeenCalled();
  });
});
