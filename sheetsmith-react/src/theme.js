// Theme tokens — applied as CSS custom properties on the root wrapper.
// Components reference them via `var(--token)` in their inline styles.
export const themes = {
  light: {
    '--canvas': '#eae8e1', '--bg': '#faf9f6', '--surface': '#ffffff', '--surface-2': '#f4f2ec',
    '--border': '#e6e3da', '--border-strong': '#d4d0c5',
    '--text': '#1b1a16', '--text-dim': '#6c6960', '--text-faint': '#9b978d',
    '--accent': '#1a7f57', '--accent-soft': '#e8f1eb', '--accent-text': '#0f5e3c',
    '--del': '#b23b2e', '--del-bg': '#fbeae7', '--warn': '#9a6a16', '--warn-bg': '#f7efdc',
    '--shadow': 'rgba(40,38,30,0.10)', '--on-accent': '#ffffff',
  },
  dark: {
    '--canvas': '#100f0b', '--bg': '#181712', '--surface': '#201e19', '--surface-2': '#2a2721',
    '--border': '#322e27', '--border-strong': '#433e35',
    '--text': '#f2efe7', '--text-dim': '#a7a297', '--text-faint': '#75716a',
    '--accent': '#4ec98c', '--accent-soft': '#1c2c23', '--accent-text': '#74d8a3',
    '--del': '#e2877d', '--del-bg': '#2c1a17', '--warn': '#d49a44', '--warn-bg': '#2a2113',
    '--shadow': 'rgba(0,0,0,0.45)', '--on-accent': '#0e1c14',
  },
};
