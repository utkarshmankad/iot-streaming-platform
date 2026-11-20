export type Resolution = '15m' | '1h' | '1d' | '1w';

export interface Tenant {
  tenantId: string;
  displayName: string;
  role: 'operator' | 'viewer';
}

export interface Session {
  username: string;
  activeTenantId: string;
  tenants: Tenant[];
}

export interface Sensor {
  sensorId: string;
  displayName: string;
  type: string;
  unit: string;
  samplingPeriodMs: number;
  desiredEnabled: boolean;
  confirmedEnabled: boolean | null;
  connectivity: 'online' | 'offline' | 'unknown';
  sampleStatus: 'fresh' | 'stale' | 'paused' | 'unknown';
  lastObservedAt: string | null;
  lastHeartbeatAt: string | null;
  latestCommand: CommandSummary | null;
}

export interface CommandSummary {
  commandId: string;
  version: number;
  requestedEnabled: boolean;
  status: 'pending' | 'applied' | 'failed' | 'expired' | 'superseded';
}

export interface CommandStatus extends CommandSummary {
  confirmedEnabled: boolean | null;
  reasonCode?: string;
}

export interface SensorPage {
  items: Sensor[];
  nextCursor: string | null;
}

export interface TrendBucket {
  start: string;
  end: string;
  count: number;
  min: number | null;
  max: number | null;
  mean: number | null;
  partial: boolean;
}

export interface Series {
  sensorId: string;
  type: string;
  unit: string;
  freshness: {
    state: 'unknown' | 'fresh' | 'stale' | 'repair_pending';
    lastObservedAt: string | null;
    recentRefreshedAt: string | null;
    materializedThrough: string | null;
  };
  buckets: TrendBucket[];
}

export interface Trends {
  from: string;
  to: string;
  bucket: Resolution;
  generatedAt: string;
  series: Series[];
}

export class ApiError extends Error {
  constructor(
    readonly status: number,
    readonly code: string,
    message: string,
    readonly retryAfterMs: number,
  ) {
    super(message);
  }
}

async function request<T>(path: string, init: RequestInit = {}): Promise<T> {
  const headers = new Headers(init.headers);
  headers.set('Accept', 'application/json');
  const deadline = AbortSignal.timeout(10_000);
  const signal = init.signal ? AbortSignal.any([deadline, init.signal]) : deadline;
  const response = await fetch(`/api/v1${path}`, {
    ...init,
    headers,
    signal,
    cache: 'no-store',
    credentials: 'same-origin',
  });
  if (!response.ok) {
    const problem = await response.json().catch(() => null);
    const retryAfter = Number(response.headers.get('Retry-After'));
    throw new ApiError(
      response.status,
      problem?.code ?? 'REQUEST_FAILED',
      typeof problem?.message === 'string' ? problem.message : 'The local API could not respond.',
      Number.isFinite(retryAfter) ? Math.max(0, retryAfter * 1000) : 0,
    );
  }
  return response.status === 204 ? undefined as T : response.json();
}

const sessionLock = 'iot-dashboard-session';
export const sessionChannel = 'iot-dashboard-session-updated';

export async function readSession(signal?: AbortSignal): Promise<Session | null> {
  try {
    return await request<Session>('/session', { signal });
  } catch (error) {
    if (error instanceof ApiError && (error.status === 401 || error.status === 403)) return null;
    throw error;
  }
}

export type SessionChange =
  | { kind: 'sign-in'; username: string; password: string }
  | { kind: 'switch'; tenantId: string }
  | { kind: 'sign-out' };

export async function changeSession(action: SessionChange): Promise<Session | null> {
  const change = async () => {
    const csrf = await request<{ headerName: string; token: string }>('/session/csrf');
    const headers = { 'Content-Type': 'application/json', [csrf.headerName]: csrf.token };
    switch (action.kind) {
      case 'sign-in':
        return request<Session>('/session', {
          method: 'POST', headers, body: JSON.stringify({ username: action.username, password: action.password }),
        });
      case 'switch':
        return request<Session>('/session/tenant', {
          method: 'PUT', headers, body: JSON.stringify({ tenantId: action.tenantId }),
        });
      case 'sign-out':
        await request<void>('/session', { method: 'DELETE', headers });
        return null;
    }
  };
  return navigator.locks ? navigator.locks.request(sessionLock, change) : change();
}

async function tenantRead<T>(tenantId: string, path: string, signal: AbortSignal): Promise<T> {
  // Cookies are shared across same-origin tabs. Confirm the server-side tenant while holding
  // a shared lock so a login, logout or tenant switch in another tab cannot race this fetch.
  const checkScope = async () => {
    const session = await readSession(signal);
    if (session?.activeTenantId !== tenantId) {
      throw new ApiError(409, 'SESSION_CHANGED', 'The browser session changed.', 0);
    }
  };
  const read = async () => {
    await checkScope();
    const result = await request<T>(path, { signal });
    if (!navigator.locks) await checkScope();
    return result;
  };
  return navigator.locks
    ? navigator.locks.request(sessionLock, { mode: 'shared', signal }, read)
    : read();
}

export function readSensors(tenantId: string, cursor: string | null, signal: AbortSignal) {
  const query = new URLSearchParams({ limit: '50' });
  if (cursor) query.set('cursor', cursor);
  return tenantRead<SensorPage>(tenantId, `/sensors?${query}`, signal);
}

export function readTrends(
  tenantId: string,
  sensorIds: string[],
  bucket: Resolution,
  from: string,
  to: string,
  signal: AbortSignal,
) {
  const query = new URLSearchParams({ bucket, from, to });
  for (const sensorId of sensorIds) query.append('sensorId', sensorId);
  return tenantRead<Trends>(tenantId, `/trends?${query}`, signal);
}

export function readCommand(tenantId: string, commandId: string, signal: AbortSignal) {
  return tenantRead<CommandStatus>(tenantId, `/commands/${encodeURIComponent(commandId)}`, signal);
}

export async function requestCommand(tenantId: string, sensorId: string, enabled: boolean, key: string) {
  const send = async () => {
    const csrf = await request<{ headerName: string; token: string }>('/session/csrf');
    return request<CommandSummary>(`/sensors/${encodeURIComponent(sensorId)}/commands`, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        'Idempotency-Key': key,
        'X-IoT-Tenant': tenantId,
        [csrf.headerName]: csrf.token,
      },
      body: JSON.stringify({ enabled }),
    });
  };
  return navigator.locks ? navigator.locks.request(sessionLock, { mode: 'shared' }, send) : send();
}

export function pollingInterval(error: Error | null, interval = 15_000) {
  if (error instanceof ApiError && (error.status === 401 || error.status === 403 || error.code === 'SESSION_CHANGED')) {
    return false;
  }
  if (error) return Math.max(30_000, error instanceof ApiError ? error.retryAfterMs : 0);
  return interval;
}
