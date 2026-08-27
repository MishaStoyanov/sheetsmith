/**
 * The categorical palette, one fixed order, never cycled.
 *
 * Both sets were run through the validator that ships with the dataviz guidance rather than chosen
 * by eye, against this app's own surfaces (#ffffff light, #201e19 dark). All six checks pass in
 * both: lightness band, chroma floor, colour-vision-deficiency separation, the normal-vision floor,
 * and contrast.
 *
 * That mattered — the palette already in ChartPreview failed two of them. Its yellow sat outside
 * the lightness band and landed 14.8 from its neighbouring orange on the normal-vision scale, below
 * the floor of 15: a pair that people with ordinary colour vision struggle to tell apart, never
 * mind anyone else. Yellow is gone from the order for that reason.
 *
 * Dark is its own set of steps from the same hues rather than the light one reused. Reused, violet
 * came out at 2.92:1 against the dark surface — under the 3:1 floor, so it read as a smudge.
 *
 * Six slots, not eight. There are five providers; a seventh series would fold into "Other" rather
 * than invent a hue, which is what keeps a palette validatable at all.
 */
const LIGHT = ['#15803d', '#2563eb', '#ea580c', '#7c3aed', '#db2777', '#0891b2'];
const DARK = ['#16a34a', '#3b82f6', '#ea580c', '#8b5cf6', '#ec4899', '#0891b2'];

/**
 * The colour for one series.
 *
 * Keyed by the entity's own name rather than by its position, so a filter that removes a provider
 * does not repaint the ones that remain — the colour follows the thing, not its rank.
 */
export function seriesColor(key, allKeys, theme) {
  const palette = theme === 'light' ? LIGHT : DARK;
  const index = allKeys.indexOf(key);
  return palette[(index < 0 ? 0 : index) % palette.length];
}

export function palette(theme) {
  return theme === 'light' ? LIGHT : DARK;
}
