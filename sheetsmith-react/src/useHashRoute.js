import { useEffect, useState } from 'react';

/**
 * The whole router. A hash rather than history routing because the back button and a reload have to
 * work, and `react-router` for three screens is a dependency nobody would ever take back out —
 * a hash needs no server-side rewrite rule either, which matters for something people self-host.
 */
export function useHashRoute(defaultRoute = 'improve') {
  const read = () => (window.location.hash.replace(/^#\/?/, '') || defaultRoute);
  const [route, setRoute] = useState(read);

  useEffect(() => {
    const onChange = () => setRoute(read());
    window.addEventListener('hashchange', onChange);
    return () => window.removeEventListener('hashchange', onChange);
  });

  // Assigning the hash rather than calling setRoute: the listener is then the single place the
  // route changes, so a link, a click and the back button all take the same path.
  const go = (next) => { window.location.hash = `/${next}`; };

  return [route, go];
}
