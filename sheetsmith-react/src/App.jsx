import { useEffect, useState } from 'react';
import { themes } from './theme.js';
import AppShell from './AppShell.jsx';
import ImproveScreen from './ImproveScreen.jsx';
import HistoryScreen from './HistoryScreen.jsx';
import LoginScreen from './LoginScreen.jsx';
import NotFound from './NotFound.jsx';
import SettingsPanel from './SettingsPanel.jsx';
import { useHashRoute } from './useHashRoute.js';
import { getCapabilities, getSettings } from './settingsApi.js';
import { configureAuth, getCurrentUser, logout, restoreSession } from './authApi.js';

/**
 * The root: what this instance is, who is looking at it, and which screen that means. Everything
 * about a spreadsheet lives in ImproveScreen — the two were one component until there was more than
 * one screen to show.
 */
export default function App() {
  const [theme, setTheme] = useState(() => localStorage.getItem('ss-theme') ?? 'dark');
  const setThemeAndSave = (t) => { setTheme(t); localStorage.setItem('ss-theme', t); };

  const [settingsOpen, setSettingsOpen] = useState(false);
  const [providerMode, setProviderMode] = useState('LOCAL');
  const [route, go] = useHashRoute('improve');

  // An older build has no /api/capabilities; treating that as "chat on" keeps it working, and a
  // server that really has the chat off answers the question rather than staying silent.
  // authEnabled defaults false for the same reason read the other way round: a build that does not
  // report it has no authentication either, so the honest fallback is "nobody is asked to log in".
  const [capabilities, setCapabilities] = useState({ chatEnabled: true, suggestionsEnabled: true, sendsOnlyStructure: false, authEnabled: false });

  // Who is signed in, and whether that question has been answered yet. The third state matters:
  // rendering the login screen while the cookie is still being exchanged would flash it in front
  // of someone who is signed in perfectly well.
  const [user, setUser] = useState(() => getCurrentUser());
  const [authChecked, setAuthChecked] = useState(false);

  useEffect(() => {
    getSettings().then(s => setProviderMode(s.providerMode)).catch(() => {});
  }, []);

  useEffect(() => {
    getCapabilities()
      .then(async (caps) => {
        setCapabilities(caps);
        // Told once, so every API module can stay unaware of the difference.
        configureAuth(caps.authEnabled);
        if (caps.authEnabled) {
          // The access token never survives a reload — only the httpOnly cookie does, so the
          // session is restored by spending it rather than by reading anything back.
          const restored = await restoreSession();
          setUser(restored?.user ?? null);
        }
      })
      .catch(() => {})
      .finally(() => setAuthChecked(true));
  }, []);

  const handleCloseSettings = () => {
    setSettingsOpen(false);
    getSettings().then(s => setProviderMode(s.providerMode)).catch(() => {});
  };

  const handleSignOut = async () => {
    await logout();
    setUser(null);
    go('improve');
  };

  // Nothing is rendered until it is known whether a login is needed, so the app never flashes a
  // screen the user does not need.
  if (capabilities.authEnabled && !authChecked) {
    return <div style={{ ...themes[theme], minHeight: '100vh', background: 'var(--canvas)' }} />;
  }

  if (capabilities.authEnabled && !user) {
    return <LoginScreen theme={theme} onSignedIn={setUser} />;
  }

  // Built from what the instance has rather than hidden with CSS: an entry that leads somewhere
  // refusing to answer is worse than no entry at all.
  const tabs = [
    { route: 'improve', label: 'Improve', icon: '▦' },
    { route: 'history', label: 'History', icon: '⧗' },
  ];

  const screens = {
    improve: <ImproveScreen theme={theme} capabilities={capabilities} providerMode={providerMode} onOpenSettings={() => setSettingsOpen(true)} />,
    history: <HistoryScreen authEnabled={capabilities.authEnabled} />,
  };

  return (
    // The font and the text colour belong on the themed root, not only inside AppShell:
    // the settings panel is a sibling of the shell, and without them here it fell back to
    // the browser's default face and black text on the dark theme.
    <div style={{ ...themes[theme], height: '100vh', boxSizing: 'border-box', fontFamily: "'Instrument Sans', system-ui, sans-serif", color: 'var(--text)' }}>
      <AppShell
        theme={theme}
        onToggleTheme={() => setThemeAndSave(theme === 'light' ? 'dark' : 'light')}
        providerMode={providerMode}
        user={user}
        route={route}
        onNavigate={go}
        onOpenSettings={() => setSettingsOpen(true)}
        onSignOut={handleSignOut}
        tabs={tabs}
      >
        {screens[route] ?? <NotFound onHome={() => go('improve')} />}
      </AppShell>

      <SettingsPanel open={settingsOpen} onClose={handleCloseSettings} />
    </div>
  );
}
