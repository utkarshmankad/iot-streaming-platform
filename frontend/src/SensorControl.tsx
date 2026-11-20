import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useEffect, useState } from 'react';
import { ApiError, readCommand, requestCommand, type CommandSummary, type Sensor, type Tenant } from './api';

type Request = { enabled: boolean; key: string };

export function SensorControl({
  sensor, tenant, unavailable, onSessionChanged,
}: { sensor: Sensor; tenant: Tenant; unavailable: boolean; onSessionChanged: () => void }) {
  const client = useQueryClient();
  const [submitted, setSubmitted] = useState<CommandSummary>();
  const [retry, setRetry] = useState<Request>();
  const stored = sensor.latestCommand;
  const latest = submitted && (!stored || submitted.version > stored.version) ? submitted : stored;
  const pending = latest?.status === 'pending';
  const details = useQuery({
    queryKey: ['tenant', tenant.tenantId, 'command', latest?.commandId],
    enabled: pending,
    queryFn: ({ signal }) => readCommand(tenant.tenantId, latest!.commandId, signal),
    refetchInterval: (query) => {
      if (query.state.data && query.state.data.status !== 'pending') return false;
      return query.state.error ? pollDelay(query.state.error) : 1000;
    },
  });
  const detail = pending ? details.data : undefined;
  const status = detail?.status ?? latest?.status;
  const terminal = Boolean(detail && detail.status !== 'pending');
  useEffect(() => {
    if (terminal) void client.invalidateQueries({ queryKey: ['tenant', tenant.tenantId, 'sensors'] });
  }, [terminal, client, tenant.tenantId]);
  useEffect(() => {
    if (sessionChanged(details.error)) onSessionChanged();
  }, [details.error, onSessionChanged]);

  const mutation = useMutation({
    mutationFn: (action: Request) => requestCommand(tenant.tenantId, sensor.sensorId, action.enabled, action.key),
    onSuccess: (command) => {
      setRetry(undefined);
      setSubmitted(command);
      void client.invalidateQueries({ queryKey: ['tenant', tenant.tenantId, 'sensors'] });
    },
    onError: (error, action) => {
      if (sessionChanged(error)) {
        onSessionChanged();
      } else if (!(error instanceof ApiError) || error.status >= 500 || error.status === 429) {
        setRetry(action);
      }
    },
  });

  const confirmed = detail?.status === 'applied'
    ? detail.confirmedEnabled ?? sensor.confirmedEnabled
    : sensor.confirmedEnabled;
  const enabled = !(confirmed ?? sensor.desiredEnabled);
  const blocked = mutation.isPending || status === 'pending' || unavailable;

  return (
    <div className="sensor-control">
      {tenant.role === 'operator' && (
        <button
          type="button"
          className="sampling-button"
          aria-label={retry ? `Retry command for ${sensor.displayName}` : `${enabled ? 'Resume' : 'Pause'} ${sensor.displayName}`}
          disabled={blocked}
          onClick={() => mutation.mutate(retry ?? { enabled, key: crypto.randomUUID() })}
        >
          {mutation.isPending ? 'Sending…' : retry ? 'Retry command' : enabled ? 'Resume' : 'Pause'}
        </button>
      )}
      <span role="status">
        {status === 'pending' && (sensor.connectivity === 'offline' ? 'Pending · sensor offline' : 'Pending confirmation')}
        {status === 'applied' && (latest?.requestedEnabled ? 'Resume confirmed' : 'Pause confirmed')}
        {status === 'expired' && 'Command expired'}
        {status === 'failed' && 'Command failed'}
        {status === 'superseded' && 'Superseded'}
      </span>
      {mutation.isError && <span className="control-error" role="alert">
        {retry ? 'Could not confirm submission. Retry uses the same request.' : mutation.error.message}
      </span>}
      {pending && details.isError && <span className="control-error" role="alert">
        Command status unavailable.
      </span>}
    </div>
  );
}

function sessionChanged(error: Error | null) {
  return error instanceof ApiError && (error.status === 401 || error.code === 'SESSION_CHANGED');
}

function pollDelay(error: Error) {
  if (error instanceof ApiError && (sessionChanged(error) || error.status === 403 || error.status === 404)) {
    return false;
  }
  return error instanceof ApiError ? Math.max(5000, error.retryAfterMs) : 5000;
}
