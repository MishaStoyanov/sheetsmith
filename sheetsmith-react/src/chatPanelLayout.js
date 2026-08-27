import { useCallback, useEffect, useState } from 'react';

export const CHAT_WIDTH = 380;
export const CHAT_BREAKPOINT = 1100;

const OPEN_KEY = 'ss-chat-open';

/**
 * The assistant panel's collapsed/expanded state and the narrow-screen breakpoint, so the page can
 * shrink by `pageOffset` while the panel is docked beside it.
 *
 * It lives here rather than beside the component because a file that exports both a component and
 * a hook cannot be hot-reloaded reliably — every edit to the panel remounted its own state, which
 * is a strange way to spend an afternoon.
 *
 * The stored value is only consulted when there is one: a first visit on a wide screen opens the
 * panel and on a narrow one leaves it shut, which is the right default in both cases and neither
 * is a preference anybody expressed.
 */
export function useChatPanelLayout() {
  const [open, setOpenState] = useState(() => {
    // localStorage is unavailable in a private window and in some embedded contexts, and a
    // preference nobody can read is not worth failing a render over.
    try {
      const stored = localStorage.getItem(OPEN_KEY);
      if (stored === null) return window.innerWidth >= CHAT_BREAKPOINT;
      return stored === 'true';
    } catch {
      return window.innerWidth >= CHAT_BREAKPOINT;
    }
  });

  const [narrow, setNarrow] = useState(() => window.innerWidth < CHAT_BREAKPOINT);

  useEffect(() => {
    const onResize = () => setNarrow(window.innerWidth < CHAT_BREAKPOINT);
    window.addEventListener('resize', onResize);
    return () => window.removeEventListener('resize', onResize);
  }, []);

  const setOpen = useCallback((next) => {
    setOpenState(next);
    try {
      localStorage.setItem(OPEN_KEY, String(next));
    } catch {
      // Remembering is a convenience; failing to remember must not fail the click.
    }
  }, []);

  return {
    open,
    narrow,
    setOpen,
    // Docked and wide enough to sit beside the page: the page gives up that much width. Over a
    // narrow screen the panel covers the page instead, and the page gives up nothing.
    pageOffset: open && !narrow ? CHAT_WIDTH : 0,
  };
}
