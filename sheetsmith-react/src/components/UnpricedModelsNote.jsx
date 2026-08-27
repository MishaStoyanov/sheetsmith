import Note from './Note.jsx';

const mono = "'JetBrains Mono', monospace";

/** How many names the sentence carries before it starts counting instead. */
const NAMED = 4;

/**
 * Which models were used and could not be priced.
 *
 * Said out loud rather than left to be inferred from a total that is quietly smaller than the token
 * count beside it.
 *
 * Written to survive the list changing size, because it will. Anyone running locally swaps models
 * the way other people change a setting, and every new one arrives with no price against it — so
 * this has to read as well at nine models as at one. A single model is named inside the sentence
 * instead of being announced and then listed after a colon; past four, the rest are counted rather
 * than printed, with the full list on hover, so the notice stays a sentence and does not become a
 * wall of model names.
 */
export default function UnpricedModelsNote({ models }) {
  if (!models.length) {
    return null;
  }

  if (models.length === 1) {
    return (
      <Note>
        <Name of={models[0]} /> has no price, so its calls are counted in tokens but not in money.
      </Note>
    );
  }

  const named = models.slice(0, NAMED);
  const rest = models.length - named.length;

  return (
    <Note>
      {models.length} models have no price, so their calls are counted in tokens but not in money:{' '}
      <span title={models.join('\n')}>
        {named.map((model, i) => (
          <span key={model}>
            {i > 0 && ', '}
            <Name of={model} />
          </span>
        ))}
        {rest > 0 && ` and ${rest} more`}
      </span>
    </Note>
  );
}

function Name({ of }) {
  return <span style={{ fontFamily: mono, fontSize: 12 }}>{of}</span>;
}
