import { renderToStaticMarkup } from 'react-dom/server';
import { expect, test } from 'vitest';
import { App } from './App';
import { ApiError, pollingInterval } from './api';
import { alignedRange, periods } from './ranges';

test('renders the application and session bootstrap', () => {
  const page = renderToStaticMarkup(<App />);
  expect(page).toContain('Environmental IoT');
  expect(page).toContain('<main');
  expect(page).toContain('Checking your session');
});

test('aligns week bounds to Monday UTC and limits full weeks to retained history', () => {
  const now = Date.parse('2026-11-20T08:00:15Z');
  expect(alignedRange('1w', 720, now)).toEqual({
    from: '2026-10-26T00:00:00.000Z',
    to: '2026-11-23T00:00:00.000Z',
  });
  expect(alignedRange('1w', 168, now)).toEqual({
    from: '2026-11-09T00:00:00.000Z',
    to: '2026-11-23T00:00:00.000Z',
  });
});

test('includes the live bucket and clips the oldest incomplete bucket for each resolution', () => {
  const now = Date.parse('2026-11-20T08:03:07Z');
  expect(alignedRange('15m', 1, now)).toEqual({
    from: '2026-11-20T07:00:00.000Z',
    to: '2026-11-20T08:15:00.000Z',
  });
  for (const bucket of ['15m', '1h', '1d', '1w'] as const) {
    const range = alignedRange(bucket, 720, now);
    expect(Date.parse(range.from)).toBeGreaterThanOrEqual(now - 720 * 3_600_000);
    expect(Date.parse(range.to)).toBeGreaterThan(now);
    expect((Date.parse(range.to) - Date.parse(range.from)) % periods[bucket]).toBe(0);
    expect(Date.parse(range.to) - Date.parse(range.from)).toBeLessThanOrEqual(720 * 3_600_000);
  }
});

test('backs off transient failures and ignores polling under an invalid session', () => {
  expect(pollingInterval(null)).toBe(15_000);
  expect(pollingInterval(new ApiError(429, 'TREND_RATE_LIMIT', '', 60_000))).toBe(60_000);
  expect(pollingInterval(new ApiError(503, 'SERVICE_UNAVAILABLE', '', 0))).toBe(30_000);
  expect(pollingInterval(new ApiError(409, 'SESSION_CHANGED', '', 0))).toBe(false);
});
