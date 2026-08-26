// Adds the DOM matchers (toBeInTheDocument, toHaveTextContent, …) to Vitest's expect.
import '@testing-library/jest-dom/vitest';

// jsdom implements no layout at all, so it has no scrollIntoView. Without this, any component that
// scrolls to its newest message fails on a missing browser API rather than on anything of its own.
if (!Element.prototype.scrollIntoView) {
  Element.prototype.scrollIntoView = () => {};
}
