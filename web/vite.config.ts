import react from '@vitejs/plugin-react';
import { defineConfig, type Plugin } from 'vite';
import { APP_NAME } from './src/config.ts';

/**
 * The web app manifest, built from the same name constant as the rest of the app so a rename
 * reaches the home-screen label too. Served in development and emitted into the build.
 */
function manifest(): Plugin {
  const body = JSON.stringify(
    {
      name: APP_NAME,
      short_name: APP_NAME,
      description: 'A peer-to-peer wallet kept like a bahi-khata, on a double-entry ledger.',
      start_url: '/',
      scope: '/',
      display: 'standalone',
      background_color: '#7a181b',
      theme_color: '#7a181b',
      icons: [
        { src: '/icon-192.png', sizes: '192x192', type: 'image/png' },
        { src: '/icon-512.png', sizes: '512x512', type: 'image/png' },
        { src: '/icon-512.png', sizes: '512x512', type: 'image/png', purpose: 'maskable' },
      ],
    },
    null,
    2,
  );
  return {
    name: 'manifest',
    configureServer(server) {
      server.middlewares.use('/manifest.webmanifest', (_request, response) => {
        response.setHeader('Content-Type', 'application/manifest+json');
        response.end(body);
      });
    },
    generateBundle() {
      this.emitFile({ type: 'asset', fileName: 'manifest.webmanifest', source: body });
    },
  };
}

export default defineConfig({
  plugins: [
    react(),
    manifest(),
    {
      // The page title comes from the same constant as everything else, so renaming the app
      // never means hunting through HTML.
      name: 'app-name',
      transformIndexHtml: (html) => html.replaceAll('%APP_NAME%', APP_NAME),
    },
  ],
  server: {
    // Same origin in development as in production, where nginx forwards /api to the gateway.
    // The refresh cookie depends on it.
    proxy: {
      '/api': 'http://localhost:8080',
    },
  },
});
