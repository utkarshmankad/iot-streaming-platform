import type { Resolution } from './api';

const hour = 60 * 60 * 1000;
const day = 24 * hour;
const retained = 30 * day;

export const resolutions: { value: Resolution; label: string }[] = [
  { value: '15m', label: '15 minutes' },
  { value: '1h', label: 'Hourly' },
  { value: '1d', label: 'Daily' },
  { value: '1w', label: 'Weekly' },
];

export const periods = {
  '15m': 15 * 60 * 1000,
  '1h': hour,
  '1d': day,
  '1w': 7 * day,
};

export const ranges = [
  { value: 1, label: 'Last hour' },
  { value: 6, label: 'Last 6 hours' },
  { value: 24, label: 'Last 24 hours' },
  { value: 7 * 24, label: 'Last 7 days' },
  { value: 14 * 24, label: 'Last 14 days' },
  { value: 30 * 24, label: 'Last 30 days' },
];

export function alignedRange(bucket: Resolution, hours: number, now: number) {
  const step = periods[bucket];
  const origin = bucket === '1w' ? 4 * day : 0; // 1970-01-05 was a Monday.
  const floor = (stamp: number) => Math.floor((stamp - origin) / step) * step + origin;
  const end = floor(now) + step;
  const firstRetained = floor(now - retained + step - 1);
  const start = Math.max(floor(now - hours * hour), firstRetained, end - Math.floor(retained / step) * step);
  return { from: new Date(start).toISOString(), to: new Date(end).toISOString() };
}

export const timestamp = new Intl.DateTimeFormat(undefined, {
  day: '2-digit', month: 'short', year: 'numeric', hour: '2-digit', minute: '2-digit',
  timeZone: 'UTC', timeZoneName: 'short', hourCycle: 'h23',
});

export const axisTime = new Intl.DateTimeFormat(undefined, {
  month: 'short', day: 'numeric', hour: '2-digit', minute: '2-digit', timeZone: 'UTC', hourCycle: 'h23',
});

export const metricNames: Record<string, string> = {
  temperature: 'Temperature',
  humidity: 'Humidity',
  pressure: 'Air pressure',
  pm25: 'Particulate matter (PM2.5)',
  light: 'Ambient light',
  oxygen: 'Oxygen concentration',
};
