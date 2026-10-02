import { useEffect, useMemo, useRef, useState, type RefObject } from "react";
import type { Label, TransferEvent } from "../api";
import { LABEL_NAMES, LABELS, labelVar } from "../theme";

const BIN_SECONDS = 2;
const BINS = 60;
const HEIGHT = 190;
const MARGIN = { top: 8, right: 4, bottom: 22, left: 30 };
const GAP = 2;

interface Bin {
  start: number;
  counts: Record<Label, number>;
  total: number;
}

/** Stacked columns: everyday at the bottom, fraud stacked on top so bursts read at a glance. */
export function ThroughputChart({ events, version }: { events: RefObject<TransferEvent[]>; version: number }) {
  const wrapRef = useRef<HTMLDivElement>(null);
  const [width, setWidth] = useState(800);
  const [now, setNow] = useState(() => Date.now());
  const [hovered, setHovered] = useState<number | null>(null);

  useEffect(() => {
    const timer = setInterval(() => setNow(Date.now()), 1_000);
    const resize = new ResizeObserver(([entry]) => setWidth(entry.contentRect.width));
    resize.observe(wrapRef.current!);
    return () => {
      clearInterval(timer);
      resize.disconnect();
    };
  }, []);

  const bins = useMemo(() => {
    const binMs = BIN_SECONDS * 1_000;
    const end = Math.ceil(now / binMs) * binMs;
    const start = end - BINS * binMs;
    const result: Bin[] = Array.from({ length: BINS }, (_, i) => ({
      start: start + i * binMs,
      counts: { normal: 0, card_testing: 0, money_mule: 0, account_takeover: 0 },
      total: 0,
    }));
    for (const event of events.current) {
      const index = Math.floor((Date.parse(event.at) - start) / binMs);
      if (index >= 0 && index < BINS) {
        result[index].counts[event.label] += 1;
        result[index].total += 1;
      }
    }
    return result;
  }, [now, version, events]);

  const plotWidth = Math.max(width - MARGIN.left - MARGIN.right, 100);
  const plotHeight = HEIGHT - MARGIN.top - MARGIN.bottom;
  const max = niceMax(Math.max(...bins.map((b) => b.total), 4));
  const slot = plotWidth / BINS;
  const barWidth = Math.max(slot - GAP, 1);
  const y = (value: number) => (value / max) * plotHeight;
  const hoveredBin = hovered === null ? null : bins[hovered];

  return (
    <div className="chart" ref={wrapRef}>
      <ul className="legend" style={{ marginBottom: 8 }}>
        {LABELS.map((label) => (
          <li key={label}>
            <span className="swatch" style={{ "--swatch": labelVar(label) } as never} />
            {LABEL_NAMES[label]}
          </li>
        ))}
      </ul>
      <svg height={HEIGHT} role="img" aria-label="Transfers per two seconds over the last two minutes, by type">
        <g transform={`translate(${MARGIN.left},${MARGIN.top})`}>
          {[0, max / 2, max].map((tick) => (
            <g key={tick} transform={`translate(0,${plotHeight - y(tick)})`}>
              <line x1={0} x2={plotWidth} stroke={tick === 0 ? "var(--baseline)" : "var(--grid)"} />
              <text className="tick" x={-6} dy="0.32em" textAnchor="end">
                {tick}
              </text>
            </g>
          ))}
          {bins.map((bin, i) => {
            let base = 0;
            const segments = LABELS.filter((label) => bin.counts[label] > 0);
            return (
              <g key={bin.start} opacity={hovered === null || hovered === i ? 1 : 0.55}>
                {segments.map((label, s) => {
                  const height = y(bin.counts[label]);
                  const top = s === segments.length - 1;
                  // A 2px gap between stacked segments, carved out of the lower one.
                  const drawn = Math.max(height - (top ? 0 : GAP), 1);
                  const shape = (
                    <path
                      key={label}
                      d={column(i * slot + GAP / 2, plotHeight - base - drawn, barWidth, drawn, top ? 4 : 0)}
                      fill={labelVar(label)}
                    />
                  );
                  base += height;
                  return shape;
                })}
              </g>
            );
          })}
          {bins.map((bin, i) => (
            <rect
              key={bin.start}
              x={i * slot}
              y={0}
              width={slot}
              height={plotHeight}
              fill="transparent"
              onPointerEnter={() => setHovered(i)}
              onPointerLeave={() => setHovered(null)}
            />
          ))}
          {[
            { at: 0, text: "2 min ago" },
            { at: plotWidth / 2, text: "1 min ago" },
            { at: plotWidth, text: "now" },
          ].map(({ at, text }) => (
            <text
              key={text}
              className="tick"
              x={at}
              y={plotHeight + 16}
              textAnchor={at === 0 ? "start" : at === plotWidth ? "end" : "middle"}
            >
              {text}
            </text>
          ))}
        </g>
      </svg>
      {hoveredBin && hovered !== null && (
        <div
          className="tooltip"
          style={{
            left: Math.min(MARGIN.left + hovered * slot + slot + 8, width - 190),
            top: 36,
          }}
        >
          <strong>
            {new Date(hoveredBin.start).toLocaleTimeString("en-GB")} · {hoveredBin.total} transfers
          </strong>
          {LABELS.map((label) => (
            <div className="tooltip-row" key={label}>
              <span className="swatch" style={{ "--swatch": labelVar(label) } as never} />
              {LABEL_NAMES[label]}: {hoveredBin.counts[label]}
            </div>
          ))}
        </div>
      )}
    </div>
  );
}

function niceMax(value: number): number {
  const step = value <= 10 ? 2 : value <= 50 ? 10 : 50;
  return Math.ceil(value / step) * step;
}

/** A column with only its top corners rounded, so the data-end is soft and the baseline stays square. */
function column(x: number, y: number, width: number, height: number, radius: number): string {
  const r = Math.min(radius, width / 2, height);
  return `M${x},${y + height} V${y + r} Q${x},${y} ${x + r},${y} H${x + width - r} Q${x + width},${y} ${x + width},${y + r} V${y + height} Z`;
}
