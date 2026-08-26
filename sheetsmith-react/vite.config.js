import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

// The dev server (`npm run dev`) and the packaged app must look identical to the frontend code:
// both serve the API from the page's own origin under /api. In dev that is achieved by proxying;
// in production Spring Boot serves the built bundle itself, so nothing is proxied.
const apiTarget = process.env.VITE_API_PROXY_TARGET || 'http://localhost:8080';

export default defineConfig({
  plugins: [react()],
  server: {
    proxy: {
      '/api': { target: apiTarget, changeOrigin: true },
    },
  },
  // Tests run against the same config the app is built with, so a module that resolves in the
  // browser resolves in a test too.
  test: {
    environment: 'jsdom',
    globals: true,
    setupFiles: './src/test/setup.js',
    include: ['src/**/*.test.{js,jsx}'],
  },
  build: {
    // Build straight into Spring Boot's static resources so the jar (and the Docker image)
    // ships the UI. Generated output — git-ignored, never edited by hand.
    outDir: '../sheetsmith-java/src/main/resources/static',
    emptyOutDir: true,
  },
});
