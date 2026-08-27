import { describe, expect, it } from 'vitest';
import { age, STALE_AFTER_DAYS } from './priceAge.js';

const NOW = new Date('2026-08-27T12:00:00Z').getTime();
const daysAgo = n => new Date(NOW - n * 86_400_000).toISOString();

describe('age', () => {
  it('reads as an answer rather than a date to subtract', () => {
    expect(age(daysAgo(0), NOW).text).toBe('today');
    expect(age(daysAgo(1), NOW).text).toBe('yesterday');
    expect(age(daysAgo(6), NOW).text).toBe('6 days ago');
    expect(age(daysAgo(31), NOW).text).toBe('a month ago');
    expect(age(daysAgo(210), NOW).text).toBe('7 months ago');
    expect(age(daysAgo(400), NOW).text).toBe('over a year ago');
  });

  it('marks a price stale only once it is old enough to be worth re-checking', () => {
    expect(age(daysAgo(STALE_AFTER_DAYS - 1), NOW).stale).toBe(false);
    expect(age(daysAgo(STALE_AFTER_DAYS), NOW).stale).toBe(true);
  });

  it('treats a price with no date as the oldest there is', () => {
    // A row nobody has ever confirmed is exactly the one to look at, so it must not read as fresh.
    expect(age(null, NOW)).toEqual({ text: 'never', stale: true });
  });

  it('answers against the moment it is given, not the moment it is called', () => {
    // The whole reason `now` is a parameter: an age computed from a moving clock changes on every
    // redraw, and React rightly refuses to have that in a render.
    const value = daysAgo(100);

    expect(age(value, NOW).stale).toBe(true);
    expect(age(value, NOW - 20 * 86_400_000).stale).toBe(false);
  });
});
