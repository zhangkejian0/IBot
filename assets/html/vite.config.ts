import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

export default defineConfig({
  plugins: [react()],
  build: {
    outDir: '../desktop/default',
    emptyOutDir: true,
  },
  server: { host: true, port: 5173 },
});
