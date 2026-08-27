/**
 * Past this, a price is worth a second look rather than a warning.
 *
 * Three months, because that is roughly how often the providers move: often enough that a year-old
 * figure is probably wrong, rarely enough that flagging anything younger would mark every row on a
 * healthy instance and teach people to ignore the mark.
 */
export const STALE_AFTER_DAYS = 90;

const DAY = 86_400_000;

/**
 * How long ago a price was last confirmed, and whether that is long enough to mention.
 *
 * An age rather than a date, for two reasons. A date has to be read and subtracted before it means
 * anything, where "7 months ago" is the answer already. And a localised date is long — it was
 * wrapping the column onto three lines and pushing the table into a sideways scroll.
 *
 * The clock it measures against is `updatedAt`, which any save sets — including confirming against
 * a catalogue that found no change. That is deliberate: the question is "when did somebody last
 * check", not "when did the number last move", and a price checked yesterday and unchanged for a
 * year is a current price.
 *
 * `now` is passed in rather than read here so the answer is fixed for a given render. An age
 * measured against a moving clock changes whenever the component happens to redraw.
 */
export function age(value, now) {
  // Never checked is the oldest a price can be, not the newest — a row with no date is exactly the
  // one somebody should look at.
  if (!value) return { text: 'never', stale: true };

  const days = Math.floor((now - new Date(value).getTime()) / DAY);
  const stale = days >= STALE_AFTER_DAYS;

  if (days <= 0) return { text: 'today', stale };
  if (days === 1) return { text: 'yesterday', stale };
  if (days < 30) return { text: `${days} days ago`, stale };

  const months = Math.max(1, Math.round(days / 30));
  if (months < 12) return { text: months === 1 ? 'a month ago' : `${months} months ago`, stale };

  const years = Math.floor(months / 12);
  return { text: years === 1 ? 'over a year ago' : `over ${years} years ago`, stale };
}
