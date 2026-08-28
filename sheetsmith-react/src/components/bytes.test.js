import { describe, it, expect } from 'vitest';
import { formatBytes, fromSizeInput, toSizeInput } from './bytes.js';

describe('formatBytes', () => {
  it('writes each size in the unit somebody would say it in', () => {
    expect(formatBytes(512)).toBe('512 B');
    expect(formatBytes(2048)).toBe('2 KB');
    expect(formatBytes(5 * 1024 * 1024)).toBe('5.0 MB');
    expect(formatBytes(3 * 1024 * 1024 * 1024)).toBe('3.0 GB');
  });

  it('treats nothing at all as nothing, not as NaN', () => {
    // An instance that has never run anything reports null here, and "NaN B" would read as a fault.
    expect(formatBytes(null)).toBe('0 B');
    expect(formatBytes(0)).toBe('0 B');
  });
});

describe('the cap as two controls', () => {
  it('offers gigabytes only where the figure stays whole in them', () => {
    // 1.5 GB handed back as a decimal in the box is a number nobody typed; 1536 MB is.
    expect(toSizeInput(2 * 1024 * 1024 * 1024)).toEqual({ value: '2', unit: 'GB' });
    expect(toSizeInput(1536 * 1024 * 1024)).toEqual({ value: '1536', unit: 'MB' });
  });

  it('reads an empty box as no limit rather than as zero', () => {
    // Zero would mean "keep nothing" — a very different instruction to give by leaving a box alone.
    expect(toSizeInput(null)).toEqual({ value: '', unit: 'MB' });
    expect(fromSizeInput('', 'MB')).toBeNull();
    expect(fromSizeInput('   ', 'GB')).toBeNull();
  });

  it('converts what was typed, and reports what cannot be', () => {
    expect(fromSizeInput('2', 'GB')).toBe(2 * 1024 * 1024 * 1024);
    expect(fromSizeInput('250', 'MB')).toBe(250 * 1024 * 1024);
    expect(fromSizeInput('lots', 'MB')).toBeNaN();
    expect(fromSizeInput('0', 'MB')).toBeNaN();
    expect(fromSizeInput('-5', 'GB')).toBeNaN();
  });
});
