import { useInfiniteQuery, useQuery } from '@tanstack/react-query';
import { lazy, Suspense, useEffect, useState } from 'react';
import { ApiError, pollingInterval, readSensors, readTrends, type Resolution, type Sensor, type Tenant, type Trends } from './api';
import { alignedRange, metricNames, periods, ranges, resolutions, timestamp } from './ranges';

const TrendChart = lazy(() => import('./TrendChart'));
const maxSensors = 10;

function lostSession(error: Error | null) {
  return error instanceof ApiError && (error.status === 401 || error.status === 403 || error.code === 'SESSION_CHANGED');
}

export function Dashboard({ tenant, onSessionChanged }: { tenant: Tenant; onSessionChanged: () => void }) {
  const [metricChoice, setMetric] = useState('');
  const [selection, setSelection] = useState<string[] | null>(null);
  const [resolution, setResolution] = useState<Resolution>('15m');
  const [hours, setHours] = useState(6);
  const [filter, setFilter] = useState('');

  const registry = useInfiniteQuery({
    queryKey: ['tenant', tenant.tenantId, 'sensors'],
    queryFn: ({ pageParam, signal }) => readSensors(tenant.tenantId, pageParam, signal),
    initialPageParam: null as string | null,
    getNextPageParam: (page) => page.nextCursor ?? undefined,
    refetchInterval: (query) => pollingInterval(query.state.error),
  });
  const sensors = registry.data?.pages.flatMap((page) => page.items) ?? [];
  const metrics = [...new Set(sensors.map((sensor) => sensor.type))];
  const metric = metrics.includes(metricChoice) ? metricChoice : metrics[0] ?? '';
  const measured = sensors.filter((sensor) => sensor.type === metric);
  const unit = measured[0]?.unit ?? '';
  const measurement = metricNames[metric] ?? metric;
  const selected = selection === null
    ? measured.slice(0, 1).map((sensor) => sensor.sensorId)
    : selection.filter((id) => measured.some((sensor) => sensor.sensorId === id));
  const validRanges = ranges.filter((range) => range.value * 3_600_000 >= periods[resolution]);
  const rangeHours = validRanges.find((range) => range.value === hours)?.value ?? validRanges[0].value;

  const trends = useQuery({
    queryKey: ['tenant', tenant.tenantId, 'trends', resolution, rangeHours, selected],
    enabled: selected.length > 0 && !lostSession(registry.error),
    queryFn: ({ signal }) => {
      const { from, to } = alignedRange(resolution, rangeHours, Date.now());
      return readTrends(tenant.tenantId, selected, resolution, from, to, signal);
    },
    refetchInterval: (query) => pollingInterval(query.state.error),
  });

  const sessionLost = lostSession(registry.error) || lostSession(trends.error);
  useEffect(() => {
    if (sessionLost) onSessionChanged();
  }, [sessionLost, onSessionChanged]);
  if (sessionLost) return <p className="panel loading" role="status">Checking your session…</p>;

  const showing = sensors.filter((sensor) => (sensor.displayName + ' ' + sensor.sensorId).toLowerCase().includes(filter.toLowerCase()));
  const hasSamples = trends.data?.series.some((series) => series.buckets.some((bucket) => bucket.count > 0));
  const freshness = trends.data?.series.map((series) => series.freshness.state) ?? [];

  function toggle(sensor: Sensor) {
    const id = sensor.sensorId;
    if (sensor.type !== metric) {
      setMetric(sensor.type);
      setSelection([id]);
      return;
    }
    setSelection(selected.includes(id) ? selected.filter((item) => item !== id) : [...selected, id]);
  }

  function chooseResolution(next: Resolution) {
    setResolution(next);
    if (rangeHours * 3_600_000 < periods[next]) {
      setHours(ranges.find((range) => range.value * 3_600_000 >= periods[next])!.value);
    }
  }

  return (
    <>
      <div className="workspace-header">
        <div>
          <div className="workspace-context">{tenant.displayName} <span>{tenant.role}</span></div>
          <h1>Sensor trends</h1>
        </div>
        {trends.data && <div className="updated">Last updated {timestamp.format(new Date(trends.data.generatedAt))}</div>}
      </div>

      <section className="panel trends" aria-labelledby="trend-heading" aria-busy={Boolean(selected.length && trends.isPending)}>
        <div className="panel-header">
          <div>
            <h2 id="trend-heading">{measurement || 'Measurements'}</h2>
            {unit && <p className="chart-unit">{unit} · minimum, mean and maximum</p>}
          </div>
          <div className="filters">
            <label>Metric
              <select
                aria-label="Metric"
                value={metric}
                disabled={metrics.length === 0}
                onChange={(event) => {
                  setMetric(event.target.value);
                  setSelection(null);
                  setFilter('');
                }}
              >
                {metrics.length === 0 && <option value="">No metrics</option>}
                {metrics.map((type) => <option key={type} value={type}>{metricNames[type] ?? type}</option>)}
              </select>
            </label>
            <label>Time range
              <select aria-label="Time range" value={rangeHours} onChange={(event) => setHours(Number(event.target.value))}>
                {validRanges.map(({ value, label }) => <option key={value} value={value}>{label}</option>)}
              </select>
            </label>
          </div>
        </div>

        <div className="chart-toolbar">
          <fieldset className="resolutions">
            <legend className="visually-hidden">Trend resolution</legend>
            {resolutions.map(({ value, label }) => (
              <label key={value}>
                <input type="radio" name="resolution" value={value} checked={value === resolution} onChange={() => chooseResolution(value)} />
                <span>{label}</span>
              </label>
            ))}
          </fieldset>
          <span className="axis-note">UTC</span>
        </div>

        {trends.isError && (
          <div className="notice error" role="alert">
            <span>{trends.data ? 'Refresh failed. Showing the last successful result.' : errorMessage(trends.error)}</span>
            <button type="button" onClick={() => { void trends.refetch(); }}>Retry trends</button>
          </div>
        )}
        {freshness.includes('repair_pending') && <p className="notice warning" role="status">Historical data is being recalculated after late readings.</p>}
        {freshness.includes('stale') && <p className="notice warning" role="status">The stored aggregates are stale.</p>}

        {registry.isPending ? (
          <p className="chart-empty" role="status">Loading the sensor registry…</p>
        ) : registry.isError && !registry.data ? null : selected.length === 0 ? (
          <p className="chart-empty">{sensors.length ? 'Select a sensor in the registry to view its trend.' : 'No sensors are available to plot.'}</p>
        ) : trends.isPending ? (
          <p className="chart-empty" role="status">Loading trends…</p>
        ) : trends.data ? (
          <>
            <EffectiveRange trends={trends.data} />
            {hasSamples ? (
              <Suspense fallback={<p className="chart-empty" role="status">Preparing the chart…</p>}>
                <TrendChart trends={trends.data} sensors={measured} />
              </Suspense>
            ) : (
              <p className="chart-empty" role="status">No readings in this range.</p>
            )}
          </>
        ) : null}
      </section>

      <section className="panel registry" aria-labelledby="registry-heading" aria-busy={registry.isFetching}>
        <div className="panel-header">
          <div>
            <h2 id="registry-heading">Sensor registry</h2>
            <p className="chart-unit">{selected.length} of {maxSensors} chart selections · {sensors.length} registered sensors loaded</p>
          </div>
          <label className="sensor-filter">Filter sensors
            <input type="search" value={filter} onChange={(event) => setFilter(event.target.value)} />
          </label>
        </div>
        {registry.isError && (
          <div className="notice error" role="alert">
            <span>{registry.data ? 'Registry refresh failed. Showing the last successful result.' : errorMessage(registry.error)}</span>
            <button type="button" onClick={() => { void registry.refetch(); }}>Retry registry</button>
          </div>
        )}
        {showing.length > 0 ? (
          <div className="table-scroll">
            <table>
              <thead><tr>
                <th scope="col">Plot</th><th scope="col">Sensor</th><th scope="col">Connectivity</th>
                <th scope="col">Reading status</th><th scope="col">Desired</th><th scope="col">Confirmed</th>
                <th scope="col">Last sample</th>
              </tr></thead>
              <tbody>
                {showing.map((sensor) => (
                  <tr key={sensor.sensorId}>
                    <td>
                      <input
                        type="checkbox" aria-label={`Plot ${sensor.displayName}`}
                        checked={selected.includes(sensor.sensorId)}
                        disabled={sensor.type === metric && selected.length >= maxSensors && !selected.includes(sensor.sensorId)}
                        onChange={() => toggle(sensor)}
                      />
                    </td>
                    <th scope="row"><strong>{sensor.displayName}</strong><span>{metricNames[sensor.type] ?? sensor.type} · {sensor.unit}</span></th>
                    <td><Status value={sensor.connectivity} /></td>
                    <td><Status value={sensor.sampleStatus} /></td>
                    <td>{sensor.desiredEnabled ? 'Enabled' : 'Paused'}</td>
                    <td>{sensor.confirmedEnabled === null ? 'Unknown' : sensor.confirmedEnabled ? 'Enabled' : 'Paused'}</td>
                    <td>{sensor.lastObservedAt ? timestamp.format(new Date(sensor.lastObservedAt)) : 'No reading yet'}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        ) : !registry.isPending && !registry.isError ? (
          <p className="registry-empty">{sensors.length ? 'No loaded sensors match the filter.' : 'This tenant has no registered sensors.'}</p>
        ) : null}
        {registry.hasNextPage && (
          <div className="registry-footer">
            <button type="button" disabled={registry.isFetching} onClick={() => { void registry.fetchNextPage(); }}>
              {registry.isFetchingNextPage ? 'Loading more…' : 'Load more sensors'}
            </button>
          </div>
        )}
      </section>
    </>
  );
}

function errorMessage(error: Error) {
  return error instanceof ApiError ? error.message : 'The local API is unavailable. Try again.';
}

function EffectiveRange({ trends }: { trends: Trends }) {
  const start = timestamp.format(new Date(trends.from));
  const end = timestamp.format(new Date(trends.to));
  return <p className="effective-range">Effective range <time dateTime={trends.from}>{start}</time> to <time dateTime={trends.to}>{end}</time></p>;
}

function Status({ value }: { value: Sensor['connectivity'] | Sensor['sampleStatus'] }) {
  return <span className={`status status-${value}`}>{value[0].toUpperCase() + value.slice(1)}</span>;
}
