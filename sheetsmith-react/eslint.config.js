import js from '@eslint/js';
import globals from 'globals';
import react from 'eslint-plugin-react';
import reactHooks from 'eslint-plugin-react-hooks';
import reactRefresh from 'eslint-plugin-react-refresh';
import prettier from 'eslint-config-prettier';

// There is no CI here by design, so this is meant to be run by hand: `npm run lint`.
// The rules worth having are the hooks ones — App.jsx carries the session state, and the classes of
// bug that plugin catches (a stale closure, an effect missing a dependency) are exactly the ones
// that show up as a sheet the user thinks is saved and is not.
export default [
  { ignores: ['dist/**', 'node_modules/**'] },

  js.configs.recommended,

  {
    files: ['**/*.{js,jsx}'],
    languageOptions: {
      ecmaVersion: 2022,
      sourceType: 'module',
      globals: { ...globals.browser, ...globals.es2021 },
      parserOptions: { ecmaFeatures: { jsx: true } },
    },
    settings: { react: { version: 'detect' } },
    plugins: { react, 'react-hooks': reactHooks, 'react-refresh': reactRefresh },
    rules: {
      ...react.configs.flat.recommended.rules,
      ...reactHooks.configs.recommended.rules,
      // The JSX transform is automatic under Vite, so neither of these is a real problem.
      'react/react-in-jsx-scope': 'off',
      'react/jsx-uses-react': 'off',
      // Types are documented in the code rather than declared; prop-types would be noise.
      'react/prop-types': 'off',
      'react-refresh/only-export-components': ['warn', { allowConstantExport: true }],
      'no-unused-vars': ['error', { argsIgnorePattern: '^_', varsIgnorePattern: '^_' }],
    },
  },

  {
    // Build configuration runs in Node, not in a browser.
    files: ['vite.config.js', 'eslint.config.js'],
    languageOptions: { globals: { ...globals.node } },
  },

  {
    files: ['**/*.test.{js,jsx}', 'src/test/**'],
    languageOptions: { globals: { ...globals.node, ...globals.vitest } },
  },

  // Formatting is Prettier's job; keep ESLint out of that argument.
  prettier,
];
