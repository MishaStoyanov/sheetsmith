/**
 * The vendors this instance can talk to, named exactly as the audit records them.
 *
 * One list, in one file, because the names are load-bearing beyond the settings screen: a price is
 * keyed on provider plus model, and the analytics screen matches it against what a run actually
 * wrote. A second copy of this list is a copy that will disagree, and the disagreement is silent —
 * a price nobody can match simply never appears in the money column.
 *
 * `keysUrl` is where that vendor mints an API key. It is here rather than beside the input because
 * this is the file that already knows one thing per vendor, and because these pages move — Anthropic's
 * console became platform.claude.com — so the day one of them rots there is a single line to change.
 *
 * `CLAUDE` rather than `ANTHROPIC` is the one that catches people. The key is the vendor's slot in
 * the settings, and that slot has always been called CLAUDE; the audit writes whatever the slot is
 * called, so that is what a price has to say too.
 */
export const CLOUD_PROVIDERS = [
  { key: 'OPENAI', label: 'OpenAI (GPT)', keysUrl: 'https://platform.openai.com/api-keys' },
  { key: 'GEMINI', label: 'Google Gemini', keysUrl: 'https://aistudio.google.com/apikey' },
  { key: 'CLAUDE', label: 'Anthropic Claude', keysUrl: 'https://platform.claude.com/settings/keys' },
  { key: 'DEEPSEEK', label: 'DeepSeek', keysUrl: 'https://platform.deepseek.com/api_keys' },
];

/** What a locally run model is recorded as. Named so it is never spelled by hand either. */
export const LOCAL_PROVIDER = 'OLLAMA';

/** For a `<Select>`: the cloud vendors, which are the only ones a price can apply to. */
export function cloudProviderOptions() {
  return CLOUD_PROVIDERS.map(provider => ({ value: provider.key, label: provider.label }));
}
