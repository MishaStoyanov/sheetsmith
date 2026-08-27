/**
 * The vendors this instance can talk to, named exactly as the audit records them.
 *
 * One list, in one file, because the names are load-bearing beyond the settings screen: a price is
 * keyed on provider plus model, and the analytics screen matches it against what a run actually
 * wrote. A second copy of this list is a copy that will disagree, and the disagreement is silent —
 * a price nobody can match simply never appears in the money column.
 *
 * `CLAUDE` rather than `ANTHROPIC` is the one that catches people. The key is the vendor's slot in
 * the settings, and that slot has always been called CLAUDE; the audit writes whatever the slot is
 * called, so that is what a price has to say too.
 */
export const CLOUD_PROVIDERS = [
  { key: 'OPENAI', label: 'OpenAI (GPT)' },
  { key: 'GEMINI', label: 'Google Gemini' },
  { key: 'CLAUDE', label: 'Anthropic Claude' },
  { key: 'DEEPSEEK', label: 'DeepSeek' },
];

/** What a locally run model is recorded as. Named so it is never spelled by hand either. */
export const LOCAL_PROVIDER = 'OLLAMA';

/** For a `<Select>`: the cloud vendors, which are the only ones a price can apply to. */
export function cloudProviderOptions() {
  return CLOUD_PROVIDERS.map(provider => ({ value: provider.key, label: provider.label }));
}
