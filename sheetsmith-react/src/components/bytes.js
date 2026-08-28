/**
 * Disk sizes, written the way a person would say them.
 *
 * Powers of 1024, because that is what a file manager on every platform this runs on shows, and a
 * cap that read 1.05 GB in the settings and 1.00 GB in Explorer would be a cap somebody argues
 * with. One decimal above a megabyte and none below it: the difference between 512 B and 512.0 B is
 * noise, the difference between 1.4 GB and 1 GB is the answer.
 */
const KB = 1024;
const MB = KB * 1024;
const GB = MB * 1024;

export function formatBytes(bytes) {
  const n = Number(bytes ?? 0);
  if (!Number.isFinite(n) || n <= 0) return '0 B';
  if (n >= GB) return `${(n / GB).toFixed(1)} GB`;
  if (n >= MB) return `${(n / MB).toFixed(1)} MB`;
  if (n >= KB) return `${Math.round(n / KB)} KB`;
  return `${Math.round(n)} B`;
}

/**
 * The cap as a number and a unit, for the two controls that edit it.
 *
 * Gigabytes only when the figure stays whole in them: 2 GB is what somebody typed, 1536 MB is what
 * they would have to recognise if this rounded to 1.5 GB and handed the box a decimal.
 */
export function toSizeInput(bytes) {
  if (bytes == null) return { value: '', unit: 'MB' };
  const n = Number(bytes);
  if (n >= GB && n % GB === 0) return { value: String(n / GB), unit: 'GB' };
  return { value: String(Math.max(1, Math.round(n / MB))), unit: 'MB' };
}

/** Back to bytes. Empty is null — "no limit" — rather than zero, which would mean "keep nothing". */
export function fromSizeInput(value, unit) {
  const text = String(value ?? '').trim();
  if (text === '') return null;
  const n = Number(text);
  if (!Number.isFinite(n) || n <= 0) return NaN;
  return Math.round(n * (unit === 'GB' ? GB : MB));
}
