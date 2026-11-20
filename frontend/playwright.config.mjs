import { defineConfig } from '@playwright/test';

const live = process.env.IOT_BROWSER_URL;
const preview = 'http://127.0.0.1:14173';
export default defineConfig({
  testDir: './browser',
  outputDir: '../build/ci/browser-results',
  testMatch: '**/*.e2e.mjs',
  workers: 1,
  timeout: 60_000,
  use: { browserName: 'chromium', baseURL: live || preview, trace: 'off' },
  reporter: [
    ['list'],
    ...(process.env.IOT_BROWSER_REPORT ? [['junit', { outputFile: process.env.IOT_BROWSER_REPORT }]] : []),
  ],
  ...(live ? {} : {
    webServer: {
      command: 'npm run build && npm exec -- vite preview --host 127.0.0.1 --port 14173 --strictPort',
      url: preview,
      reuseExistingServer: false,
    },
  }),
});
