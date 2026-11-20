import { LineChart, type LineSeriesOption } from 'echarts/charts';
import { GridComponent, MarkAreaComponent, TooltipComponent, type GridComponentOption, type TooltipComponentOption } from 'echarts/components';
import { init, use, type ComposeOption, type EChartsType } from 'echarts/core';
import { CanvasRenderer } from 'echarts/renderers';
import { useEffect, useMemo, useRef, useState } from 'react';
import type { Sensor, TrendBucket, Trends } from './api';
import { axisTime, timestamp } from './ranges';

use([LineChart, GridComponent, TooltipComponent, MarkAreaComponent, CanvasRenderer]);

type Option = ComposeOption<LineSeriesOption | GridComponentOption | TooltipComponentOption>;
const colors = ['#176a65', '#1656b8', '#a44822', '#7c438d', '#496e15', '#a13759', '#695338', '#315187', '#007083', '#72371b'];
const number = new Intl.NumberFormat(undefined, { maximumFractionDigits: 3 });
const columns = [
  { key: 'mean', label: 'Mean', style: 'solid' },
  { key: 'min', label: 'Minimum', style: 'dotted' },
  { key: 'max', label: 'Maximum', style: 'dashed' },
] as const;

export default function TrendChart({ trends, sensors }: { trends: Trends; sensors: Sensor[] }) {
  const host = useRef<HTMLDivElement>(null);
  const chart = useRef<EChartsType | null>(null);
  const names = useMemo(() => new Map(sensors.map((sensor) => [sensor.sensorId, sensor.displayName])), [sensors]);

  useEffect(() => {
    const node = host.current;
    if (!node) return;
    const view = init(node, null, { renderer: 'canvas' });
    chart.current = view;
    const observer = new ResizeObserver(() => view.resize());
    observer.observe(node);
    return () => {
      observer.disconnect();
      view.dispose();
      chart.current = null;
    };
  }, []);

  useEffect(() => {
    const all = trends.series;
    const partial = all[0]?.buckets.filter((bucket) => bucket.partial) ?? [];
    const from = Date.parse(trends.from);
    const to = Date.parse(trends.to);
    const option: Option = {
      animation: false,
      useUTC: true,
      grid: { top: 24, left: 10, right: 20, bottom: 8, outerBoundsMode: 'same' },
      xAxis: {
        type: 'time', min: from, max: to, splitNumber: 5,
        axisLabel: { formatter: (value: number) => axisTime.format(value), hideOverlap: true, color: '#435872' },
        axisLine: { lineStyle: { color: '#a4b8c9' } },
      },
      yAxis: {
        type: 'value', scale: true, splitNumber: 4,
        splitLine: { lineStyle: { color: '#dce6eb' } },
        axisLabel: { color: '#435872' },
      },
      tooltip: {
        trigger: 'axis', renderMode: 'richText', confine: true,
        formatter(params) {
          const item = Array.isArray(params) ? params[0] : params;
          const value = Array.isArray(item?.value) ? item.value[0] : undefined;
          if (typeof value !== 'number') return '';
          const lines = all.flatMap((series) => {
            const bucket = series.buckets.find((point) => Date.parse(point.start) === value);
            if (!bucket) return [];
            const name = names.get(series.sensorId) ?? series.sensorId;
            const summary = bucket.count === 0 ? 'No samples'
              : `Min ${show(bucket.min)} · Mean ${show(bucket.mean)} · Max ${show(bucket.max)} ${series.unit}`;
            return [name, `${summary} · ${number.format(bucket.count)} samples${bucket.partial ? ' · Partial' : ''}`];
          });
          return [timestamp.format(value), ...lines].join('\n');
        },
      },
      series: all.flatMap((series, index) => columns.map(({ key, style }) => ({
        type: 'line' as const,
        name: `${names.get(series.sensorId) ?? series.sensorId} ${key}`,
        data: series.buckets.map((bucket) => [Date.parse(bucket.start), bucket[key]]),
        connectNulls: false,
        showSymbol: key === 'mean' || series.buckets.length <= 60,
        symbolSize: key === 'mean' ? (series.buckets.length > 60 ? 3 : 6) : 3,
        symbol: key === 'mean' ? 'circle' : key === 'min' ? 'triangle' : 'diamond',
        itemStyle: { color: colors[index % colors.length] },
        lineStyle: { type: style, width: key === 'mean' ? 2.5 : 1.5 },
        ...(index === 0 && key === 'mean' && partial.length ? {
          markArea: {
            silent: true, label: { show: false }, itemStyle: { color: 'rgba(217, 163, 67, 0.18)' },
            data: partial.map((bucket) => [{ xAxis: Date.parse(bucket.start) }, { xAxis: Date.parse(bucket.end) }]),
          },
        } : {}),
      }))),
    };
    chart.current?.setOption(option, { notMerge: true });
  }, [names, trends]);

  const hasPartial = trends.series.some((series) => series.buckets.some((bucket) => bucket.partial));
  return (
    <>
      <div className="chart" ref={host} role="img" aria-label="Sensor trend chart" />
      <div className="chart-key">
        {trends.series.map((series, index) => (
          <span key={series.sensorId}><i style={{ backgroundColor: colors[index % colors.length] }} />{names.get(series.sensorId) ?? series.sensorId}</span>
        ))}
        {columns.map(({ label, style }) => <span key={label}><i className={`line-key ${style}`} />{label}</span>)}
        {hasPartial && <span><i className="partial-key" />Partial bucket</span>}
      </div>
      <BucketTable trends={trends} names={names} />
    </>
  );
}

function show(value: number | null) {
  return value === null ? '—' : number.format(value);
}

const pageSize = 20;

function BucketTable({ trends, names }: { trends: Trends; names: Map<string, string> }) {
  const [open, setOpen] = useState(false);
  const [sensorId, setSensorId] = useState('');
  const [page, setPage] = useState(0);
  const series = trends.series.find((item) => item.sensorId === sensorId) ?? trends.series[0];
  if (!series) return null;
  const buckets = series.buckets;
  const lastPage = Math.max(0, Math.ceil(buckets.length / pageSize) - 1);
  const current = Math.min(page, lastPage);
  const recent = buckets.slice(Math.max(0, buckets.length - (current + 1) * pageSize), buckets.length - current * pageSize).reverse();
  return (
    <details className="data-details" onToggle={(event) => setOpen(event.currentTarget.open)}>
      <summary>View bucket values</summary>
      {open && (
        <>
          {trends.series.length > 1 && (
            <label>Sensor data
              <select value={series.sensorId} onChange={(event) => { setSensorId(event.target.value); setPage(0); }}>
                {trends.series.map((row) => <option key={row.sensorId} value={row.sensorId}>{names.get(row.sensorId) ?? row.sensorId}</option>)}
              </select>
            </label>
          )}
          <div className="table-scroll">
            <table>
              <caption>{names.get(series.sensorId) ?? series.sensorId} · {series.unit}</caption>
              <thead><tr>
                <th scope="col">Bucket start (UTC)</th><th scope="col">Min</th><th scope="col">Mean</th>
                <th scope="col">Max</th><th scope="col">Samples</th><th scope="col">State</th>
              </tr></thead>
              <tbody>{recent.map((bucket) => <BucketRow key={bucket.start} bucket={bucket} />)}</tbody>
            </table>
          </div>
          {lastPage > 0 && (
            <div className="data-pagination">
              <button type="button" disabled={current === 0} onClick={() => setPage(current - 1)}>Newer buckets</button>
              <button type="button" disabled={current === lastPage} onClick={() => setPage(current + 1)}>Older buckets</button>
            </div>
          )}
        </>
      )}
    </details>
  );
}

function BucketRow({ bucket }: { bucket: TrendBucket }) {
  return (
    <tr>
      <th scope="row">{timestamp.format(new Date(bucket.start))}</th>
      <td>{show(bucket.min)}</td><td>{show(bucket.mean)}</td><td>{show(bucket.max)}</td><td>{number.format(bucket.count)}</td>
      <td>{bucket.partial ? (bucket.count === 0 ? 'Partial gap' : 'Partial') : bucket.count === 0 ? 'Gap' : 'Complete'}</td>
    </tr>
  );
}
