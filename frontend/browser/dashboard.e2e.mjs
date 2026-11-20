import { expect, test } from '@playwright/test';

const a = 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa';
const b = 'bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb';
const shared = '00000000-0000-4000-8000-000000000001';
const second = '00000000-0000-4000-8000-000000000002';
const light = '00000000-0000-4000-8000-000000000003';
const stamp = '2026-11-20T08:15:00Z';
const tenants = [
  { tenantId: a, displayName: 'Test North', role: 'operator' },
  { tenantId: b, displayName: 'Test South', role: 'viewer' },
];

async function mockDashboard(context, { signedIn = a } = {}) {
  let active = signedIn;
  let mode = 'normal';
  const calls = [];
  await context.route('**/api/v1/**', async (route) => {
    const request = route.request();
    const path = new URL(request.url());
    const json = (status, response) => route.fulfill({ status, json: response });
    const session = () => ({ username: 'operator-a', activeTenantId: active, tenants });
    const problem = (status, code, message) => json(status, { code, message, requestId: 'fixture-request' });
    if (path.pathname.endsWith('/session/csrf')) {
      return json(200, { headerName: 'X-CSRF-TOKEN', token: 'test-csrf' });
    }
    if (path.pathname.endsWith('/session') && request.method() === 'POST') {
      if (request.headers()['x-csrf-token'] !== 'test-csrf') return problem(403, 'INVALID_CSRF', 'Invalid token.');
      if (request.postDataJSON().username !== 'operator-a') return problem(401, 'AUTHENTICATION_REQUIRED', 'Sign in to continue.');
      active = a;
      return json(200, session());
    }
    if (!active) return problem(401, 'AUTHENTICATION_REQUIRED', 'Sign in to continue.');
    if (path.pathname.endsWith('/session/tenant')) {
      if (request.headers()['x-csrf-token'] !== 'test-csrf') return problem(403, 'INVALID_CSRF', 'Invalid token.');
      active = request.postDataJSON().tenantId;
      return json(200, session());
    }
    if (path.pathname.endsWith('/session') && request.method() === 'DELETE') {
      active = null;
      return route.fulfill({ status: 204 });
    }
    if (path.pathname.endsWith('/session')) return json(200, session());
    const name = active === a ? 'North' : 'South';
    const sensor = (sensorId, type, displayName) => ({
      sensorId, displayName: name + ' ' + displayName, type,
      unit: type === 'light' ? 'lux' : '°C', samplingPeriodMs: 1000,
      desiredEnabled: true, confirmedEnabled: null, connectivity: 'unknown',
      sampleStatus: 'fresh', lastObservedAt: stamp, lastHeartbeatAt: null,
    });
    if (path.pathname.endsWith('/sensors')) {
      return path.searchParams.has('cursor')
        ? json(200, { items: [sensor(second, 'temperature', 'outdoor')], nextCursor: null })
        : json(200, { items: [sensor(shared, 'temperature', 'indoor'), sensor(light, 'light', 'light')], nextCursor: 'fixture-page' });
    }
    if (path.pathname.endsWith('/trends')) {
      calls.push({ tenant: active, query: path.searchParams });
      if (mode === 'error') return problem(503, 'SERVICE_UNAVAILABLE', 'Telemetry is unavailable.');
      const bucket = path.searchParams.get('bucket');
      const ids = path.searchParams.getAll('sensorId');
      const from = path.searchParams.get('from');
      const to = path.searchParams.get('to');
      const step = { '15m': 900_000, '1h': 3_600_000, '1d': 86_400_000, '1w': 604_800_000 }[bucket];
      const bounds = [Date.parse(to) - 2 * step, Date.parse(to) - step].filter((start) => start >= Date.parse(from));
      const series = ids.map((sensorId) => ({
        sensorId, type: sensorId === light ? 'light' : 'temperature', unit: sensorId === light ? 'lux' : '°C',
        freshness: { state: mode === 'repair' ? 'repair_pending' : 'fresh', lastObservedAt: stamp, recentRefreshedAt: null, materializedThrough: null },
        buckets: bounds.map((start, index) => {
          const count = mode === 'empty' || index < bounds.length - 1 ? 0 : 22;
          const mean = active === a ? 21 : 41;
          return {
            start: new Date(start).toISOString(), end: new Date(start + step).toISOString(),
            count, min: count ? mean - 1 : null, mean: count ? mean : null, max: count ? mean + 1 : null,
            partial: index === bounds.length - 1,
          };
        }),
      }));
      return json(200, { from, to, bucket, generatedAt: stamp, series });
    }
    return route.abort();
  });
  return { calls, setMode: (value) => { mode = value; }, expireSession: () => { active = null; } };
}

async function signIn(page, username, password) {
  await page.getByLabel('Username').fill(username);
  // The disposable stack password is passed to the page without adding it to action logs.
  await page.getByLabel('Password').evaluate((field, credential) => { field.value = credential; }, password);
  await page.getByRole('button', { name: 'Sign in', exact: true }).click();
}

test('works through login, sensor filters, gap and partial buckets, and the four resolutions', async ({ page, context }) => {
  const mock = await mockDashboard(context, { signedIn: null });
  await page.goto('/');
  await signIn(page, 'unknown', 'fixture');
  await expect(page.getByRole('alert')).toContainText('Sign in to continue');
  await signIn(page, 'operator-a', 'fixture');
  await expect(page.getByRole('heading', { name: 'Sensor trends' })).toBeVisible();
  await expect(page.getByRole('img', { name: 'Sensor trend chart' })).toBeVisible();
  await expect(page.locator('.chart canvas').first()).toBeVisible();
  await expect(page.getByText('Partial bucket', { exact: true })).toBeVisible();
  await page.getByText('View bucket values').click();
  const values = page.getByRole('table', { name: 'North indoor · °C' });
  await expect(values.getByRole('cell', { name: 'Gap', exact: true })).toBeVisible();
  await expect(values.getByRole('cell', { name: 'Partial', exact: true })).toBeVisible();
  await expect(values.getByRole('cell', { name: '22', exact: true }).first()).toBeVisible();

  for (const [label, value] of [['Hourly', '1h'], ['Daily', '1d'], ['Weekly', '1w'], ['15 minutes', '15m']]) {
    const received = page.waitForResponse((response) => {
      const url = new URL(response.url());
      return url.pathname.endsWith('/trends') && url.searchParams.get('bucket') === value;
    });
    await page.getByText(label, { exact: true }).click();
    expect((await received).ok()).toBe(true);
    await expect(page.getByRole('radio', { name: label })).toBeChecked();
  }
  const weekly = mock.calls.find((call) => call.query.get('bucket') === '1w');
  expect(new Date(weekly.query.get('from')).getUTCDay()).toBe(1);
  expect(new Date(weekly.query.get('to')).getUTCDay()).toBe(1);

  await page.getByRole('button', { name: 'Load more sensors' }).click();
  await expect(page.getByRole('row', { name: /North outdoor/ })).toBeVisible();
  await page.getByRole('checkbox', { name: 'Plot North outdoor' }).check();
  await expect.poll(() => mock.calls.at(-1).query.getAll('sensorId')).toEqual([shared, second]);
  await page.getByLabel('Filter sensors').fill('indoor');
  await expect(page.getByRole('row', { name: /North outdoor/ })).toHaveCount(0);
  await page.getByLabel('Metric', { exact: true }).selectOption('light');
  await expect(page.getByRole('heading', { name: 'Ambient light' })).toBeVisible();
  await expect.poll(() => mock.calls.at(-1).query.getAll('sensorId')).toEqual([light]);
  await page.getByRole('checkbox', { name: 'Plot North light' }).uncheck();
  await expect(page.getByText('Select a sensor in the registry')).toBeVisible();
  await page.getByRole('button', { name: 'Sign out' }).click();
  await expect(page.getByRole('heading', { name: 'Sign in', exact: true })).toBeVisible();
  await expect(page.getByRole('heading', { name: 'Sensor trends' })).toHaveCount(0);
});

test('clears cached results in both tabs when switching tenants with overlapping sensor IDs', async ({ page, context }) => {
  const mock = await mockDashboard(context);
  const secondTab = await context.newPage();
  await page.goto('/');
  await secondTab.goto('/');
  await expect(page.getByRole('row', { name: /North indoor/ })).toBeVisible();
  await expect(secondTab.getByRole('row', { name: /North indoor/ })).toBeVisible();
  await page.getByText('View bucket values').click();
  await expect(page.getByRole('cell', { name: '21', exact: true })).toBeVisible();
  await secondTab.getByLabel('Tenant', { exact: true }).selectOption(b);
  for (const tab of [secondTab, page]) {
    await expect(tab.getByRole('row', { name: /South indoor/ })).toBeVisible();
    await expect(tab.getByRole('row', { name: /North indoor/ })).toHaveCount(0);
    await tab.getByText('View bucket values').click();
    await expect(tab.getByRole('cell', { name: '41', exact: true })).toBeVisible();
    await expect(tab.getByRole('cell', { name: '21', exact: true })).toHaveCount(0);
  }
  expect(mock.calls.some(({ tenant, query }) => tenant === b && query.get('sensorId') === shared)).toBe(true);
});

test('polls live data, backs off an outage and shows freshness and no-data states', async ({ page, context }) => {
  const mock = await mockDashboard(context);
  await page.clock.install({ time: new Date('2026-11-20T08:15:03Z') });
  await page.goto('/');
  await expect(page.getByRole('img', { name: 'Sensor trend chart' })).toBeVisible();
  mock.setMode('error');
  await page.clock.fastForward(16_000);
  await expect(page.getByRole('alert')).toContainText('Showing the last successful result');
  await expect(page.getByRole('img', { name: 'Sensor trend chart' })).toBeVisible();
  const calls = mock.calls.length;
  await page.clock.fastForward(15_000);
  expect(mock.calls.length).toBe(calls);
  mock.setMode('repair');
  await page.getByRole('button', { name: 'Retry trends' }).click();
  await expect(page.getByText('Historical data is being recalculated')).toBeVisible();
  mock.setMode('empty');
  await page.clock.fastForward(16_000);
  await expect(page.getByText('No readings in this range.')).toBeVisible();
  mock.expireSession();
  await page.clock.fastForward(16_000);
  await expect(page.getByRole('heading', { name: 'Sign in', exact: true })).toBeVisible();
  await expect(page.getByRole('heading', { name: 'Sensor trends' })).toHaveCount(0);
});

test('renders fresh Compose telemetry through Nginx at every resolution', async ({ page }) => {
  test.skip(!process.env.IOT_BROWSER_URL, 'requires the disposable Compose stack');
  const password = process.env.IOT_BROWSER_OPERATOR_PASSWORD;
  expect(Boolean(password)).toBe(true);
  await page.goto('/');
  await signIn(page, 'operator-a', password);
  await expect(page.getByRole('heading', { name: 'Sensor registry' })).toBeVisible();
  await expect(page.getByRole('img', { name: 'Sensor trend chart' })).toBeVisible({ timeout: 30_000 });
  const live = await page.request.get('/api/v1/session');
  expect(live.ok()).toBe(true);
  expect((await live.json()).activeTenantId).toBe(a);
  for (const [label, value] of [['Hourly', '1h'], ['Daily', '1d'], ['Weekly', '1w'], ['15 minutes', '15m']]) {
    const received = page.waitForResponse((response) => {
      const url = new URL(response.url());
      return url.pathname.endsWith('/trends') && url.searchParams.get('bucket') === value;
    });
    await page.getByText(label, { exact: true }).click();
    const response = await received;
    expect(response.ok()).toBe(true);
    const trend = await response.json();
    expect(trend.series.some((series) => series.buckets.some((bucket) => bucket.count > 0))).toBe(true);
    await expect(page.getByRole('img', { name: 'Sensor trend chart' })).toBeVisible();
  }
  const started = Date.now();
  await page.waitForResponse(async (response) => {
    const url = new URL(response.url());
    if (!response.ok() || !url.pathname.endsWith('/trends') || url.searchParams.get('bucket') !== '15m') return false;
    const trend = await response.json();
    return trend.series.some((series) => Date.parse(series.freshness.lastObservedAt) > started);
  }, { timeout: 30_000 });
});
