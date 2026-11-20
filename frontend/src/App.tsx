import { QueryClient, QueryClientProvider, queryOptions, useQuery, useQueryClient } from '@tanstack/react-query';
import { type FormEvent, useCallback, useEffect, useRef, useState } from 'react';
import { ApiError, changeSession, readSession, sessionChannel, type SessionChange } from './api';
import { Dashboard } from './Dashboard';
import './dashboard.css';

const sessionQuery = queryOptions({
  queryKey: ['session'],
  queryFn: ({ signal }) => readSession(signal),
  retry: false,
});

export function App() {
  const [client] = useState(() => new QueryClient({ defaultOptions: { queries: { retry: false } } }));
  return (
    <QueryClientProvider client={client}>
      <SessionApp />
    </QueryClientProvider>
  );
}

function SessionApp() {
  const client = useQueryClient();
  const session = useQuery(sessionQuery);
  const [updating, setUpdating] = useState(false);
  const [epoch, setEpoch] = useState(0);
  const [notice, setNotice] = useState('');
  const channel = useRef<BroadcastChannel | null>(null);
  const update = useRef(0);

  const discardTenant = useCallback(async () => {
    await client.cancelQueries({ queryKey: ['tenant'] });
    client.removeQueries({ queryKey: ['tenant'] });
    setEpoch((previous) => previous + 1);
  }, [client]);

  const refreshSession = useCallback(async () => {
    const version = ++update.current;
    setUpdating(true);
    await discardTenant();
    await client.resetQueries({ queryKey: sessionQuery.queryKey, exact: true });
    if (version === update.current) setUpdating(false);
  }, [client, discardTenant]);

  useEffect(() => {
    if (typeof BroadcastChannel === 'undefined') return;
    const broadcast = new BroadcastChannel(sessionChannel);
    channel.current = broadcast;
    broadcast.onmessage = () => { void refreshSession(); };
    return () => {
      broadcast.close();
      channel.current = null;
    };
  }, [refreshSession]);

  async function act(action: SessionChange) {
    const version = ++update.current;
    setUpdating(true);
    setNotice('');
    try {
      await discardTenant();
      await client.cancelQueries({ queryKey: sessionQuery.queryKey, exact: true });
      const result = await changeSession(action);
      if (update.current === version) client.setQueryData(sessionQuery.queryKey, result);
      channel.current?.postMessage('session updated');
    } catch (error) {
      if (update.current === version) {
        setNotice(error instanceof ApiError ? error.message : 'The local API is unavailable. Try again.');
        if (action.kind !== 'sign-in') await client.resetQueries({ queryKey: sessionQuery.queryKey, exact: true });
      }
    } finally {
      if (update.current === version) setUpdating(false);
    }
  }

  const current = session.data;
  const active = current?.tenants.find((tenant) => tenant.tenantId === current.activeTenantId);
  function signIn(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const form = event.currentTarget;
    const input = new FormData(form);
    void act({ kind: 'sign-in', username: String(input.get('username')), password: String(input.get('password')) });
    form.reset();
  }

  return (
    <div className="app">
      <header className="masthead">
        <div className="masthead-inner">
          <span className="wordmark">Environmental IoT</span>
          {current && active && (
            <div className="session-actions">
              {current.tenants.length > 1 ? (
                <label className="tenant-control">
                  <span>Tenant</span>
                  <select
                    aria-label="Tenant"
                    value={active.tenantId}
                    disabled={updating}
                    onChange={(event) => { void act({ kind: 'switch', tenantId: event.target.value }); }}
                  >
                    {current.tenants.map((tenant) => <option key={tenant.tenantId} value={tenant.tenantId}>{tenant.displayName}</option>)}
                  </select>
                </label>
              ) : (
                <span className="tenant-name">{active.displayName}</span>
              )}
              <button type="button" disabled={updating} onClick={() => { void act({ kind: 'sign-out' }); }}>
                Sign out
              </button>
            </div>
          )}
        </div>
      </header>

      <main className={current ? 'workspace' : 'session-page'}>
        {session.isPending || (updating && current) ? (
          <p className="panel loading" role="status">Checking your session…</p>
        ) : session.isError ? (
          <section className="panel login">
            <h1>Dashboard unavailable</h1>
            <p role="alert">Your session could not be verified. Check that the local stack is running.</p>
            <button type="button" onClick={() => { void refreshSession(); }}>Retry</button>
          </section>
        ) : current && active ? (
          <>
            {notice && <p className="notice error" role="alert">{notice}</p>}
            <Dashboard
              key={`${active.tenantId}:${epoch}`}
              tenant={active}
              onSessionChanged={refreshSession}
            />
          </>
        ) : (
          <section className="panel login">
            <h1>Sign in</h1>
            <p>Use the operator credentials generated by your local stack.</p>
            <form onSubmit={signIn}>
              <label>Username<input name="username" autoComplete="username" required maxLength={64} /></label>
              <label>Password<input name="password" type="password" autoComplete="current-password" required /></label>
              {notice && <p className="notice error" role="alert">{notice}</p>}
              <button className="primary" type="submit" disabled={updating}>{updating ? 'Signing in…' : 'Sign in'}</button>
            </form>
          </section>
        )}
      </main>
    </div>
  );
}
