import {
  forceCollide,
  forceLink,
  forceManyBody,
  forceSimulation,
  forceX,
  forceY,
  type SimulationLinkDatum,
  type SimulationNodeDatum,
} from "d3-force";
import { useEffect, useRef, useState, type RefObject } from "react";
import type { Label, Role, TransferEvent } from "../api";
import { FRAUD_LABELS, LABEL_NAMES, labelVar, type Palette } from "../theme";

interface Node extends SimulationNodeDatum {
  id: string;
  name: string;
  role: Role;
  lastActive: number;
}

interface Link extends SimulationLinkDatum<Node> {
  key: string;
  source: Node;
  target: Node;
  label: Label;
  at: number;
  count: number;
}

interface Pulse {
  link: Link;
  label: Label;
  start: number;
}

// How long a line stays on screen after the last transfer along it.
const NORMAL_TTL = 45_000;
const FRAUD_TTL = 150_000;
const PULSE_MS = 900;

const ROLE_NAMES: Record<Role, string> = {
  customer: "Customer",
  merchant: "Shop",
  fraudster: "Fraudster-controlled account",
  external: "Outside world (salaries in, cash out)",
};

interface Hover {
  x: number;
  y: number;
  node: Node;
  links: number;
}

export function MoneyFlowGraph({
  events,
  subscribe,
  palette,
  reducedMotion,
}: {
  events: RefObject<TransferEvent[]>;
  subscribe: (listener: (event: TransferEvent) => void) => () => void;
  palette: Palette;
  reducedMotion: boolean;
}) {
  const wrapRef = useRef<HTMLDivElement>(null);
  const canvasRef = useRef<HTMLCanvasElement>(null);
  const paletteRef = useRef(palette);
  const motionRef = useRef(reducedMotion);
  const findRef = useRef<(x: number, y: number) => Hover | null>(() => null);
  const [hover, setHover] = useState<Hover | null>(null);
  const [summary, setSummary] = useState("");
  paletteRef.current = palette;
  motionRef.current = reducedMotion;

  useEffect(() => {
    const canvas = canvasRef.current!;
    const context = canvas.getContext("2d")!;
    const nodes = new Map<string, Node>();
    const links = new Map<string, Link>();
    let pulses: Pulse[] = [];
    let structureChanged = false;
    const view = { k: 1, x: 0, y: 0 };
    let size = { width: 0, height: 0 };

    const simulation = forceSimulation<Node>([])
      .force(
        "charge",
        forceManyBody<Node>().strength((n) => (n.role === "external" ? -260 : n.role === "merchant" ? -70 : -22)),
      )
      .force(
        "link",
        forceLink<Node, Link>([])
          .id((n) => n.id)
          .distance((l) => (l.label === "normal" ? 70 : 34))
          .strength((l) => (l.label === "normal" ? 0.05 : 0.6)),
      )
      // A stronger pull vertically than horizontally spreads the network out to fit a wide panel.
      .force("x", forceX<Node>(0).strength(0.02))
      .force("y", forceY<Node>(0).strength(0.09))
      .force(
        "collide",
        forceCollide<Node>((n) => radius(n) + 3),
      )
      .alphaDecay(0.015)
      .stop();

    const ensureNode = (id: string, name: string, role: Role, near?: Node): Node => {
      let node = nodes.get(id);
      if (!node) {
        const angle = Math.random() * Math.PI * 2;
        const distance = near ? 20 : 120 + Math.random() * 120;
        node = {
          id,
          name,
          role,
          lastActive: 0,
          x: (near?.x ?? 0) + Math.cos(angle) * distance,
          y: (near?.y ?? 0) + Math.sin(angle) * distance,
        };
        nodes.set(id, node);
        structureChanged = true;
      }
      return node;
    };

    const add = (event: TransferEvent, animate: boolean) => {
      if (event.status === "rejected") return; // no money moved
      const at = Date.parse(event.at);
      const from = ensureNode(event.fromId, event.fromName, event.fromRole, nodes.get(event.toId));
      const to = ensureNode(event.toId, event.toName, event.toRole, from);
      from.lastActive = Math.max(from.lastActive, at);
      to.lastActive = Math.max(to.lastActive, at);
      const key = `${from.id}>${to.id}`;
      let link = links.get(key);
      if (!link) {
        link = { key, source: from, target: to, label: event.label, at, count: 0 };
        links.set(key, link);
        structureChanged = true;
      }
      link.at = Math.max(link.at, at);
      link.count += 1;
      if (event.label !== "normal") link.label = event.label;
      if (animate && !motionRef.current) pulses.push({ link, label: event.label, start: performance.now() });
    };

    const prune = (now: number) => {
      for (const [key, link] of links) {
        if (now - link.at > (link.label === "normal" ? NORMAL_TTL : FRAUD_TTL)) {
          links.delete(key);
          structureChanged = true;
        }
      }
      const linked = new Set<string>();
      for (const link of links.values()) linked.add(link.source.id).add(link.target.id);
      // An account with no recent money flow has nothing to show, and left alone it would
      // drift outwards and drag the camera with it.
      for (const [id, node] of nodes) {
        if (!linked.has(id) && node.role !== "external") {
          nodes.delete(id);
          structureChanged = true;
        }
      }
      if (structureChanged) {
        structureChanged = false;
        simulation.nodes([...nodes.values()]);
        simulation.force<ReturnType<typeof forceLink<Node, Link>>>("link")!.links([...links.values()]);
        simulation.alpha(Math.max(simulation.alpha(), 0.3));
        const fraud = [...links.values()].filter((l) => l.label !== "normal").length;
        setSummary(`${nodes.size} accounts and ${links.size} recent money flows, ${fraud} of them fraudulent.`);
      }
    };

    for (const event of events.current) add(event, false);
    const unsubscribe = subscribe((event) => add(event, true));

    const render = (time: number) => {
      fitView(view, nodes, size);
      paint(context, paletteRef.current, view, size, links, nodes, pulses, Date.now(), time);
    };

    const resize = new ResizeObserver(([entry]) => {
      size = { width: entry.contentRect.width, height: entry.contentRect.height };
      const dpr = window.devicePixelRatio || 1;
      canvas.width = Math.round(size.width * dpr);
      canvas.height = Math.round(size.height * dpr);
      render(performance.now()); // resizing clears the canvas; don't wait for the next frame
    });
    resize.observe(wrapRef.current!);

    findRef.current = (px, py) => {
      const x = (px - size.width / 2) / view.k + view.x;
      const y = (py - size.height / 2) / view.k + view.y;
      const node = simulation.find(x, y, 14 / view.k);
      if (!node) return null;
      let count = 0;
      for (const link of links.values()) if (link.source === node || link.target === node) count += 1;
      return { x: px, y: py, node, links: count };
    };

    let lastPrune = 0;
    let frame = requestAnimationFrame(function draw(time) {
      const now = Date.now();
      if (time - lastPrune > 1_000 || structureChanged) {
        lastPrune = time;
        prune(now);
      }
      if (simulation.alpha() > simulation.alphaMin()) simulation.tick();
      render(time);
      pulses = pulses.filter((p) => time - p.start < PULSE_MS);
      frame = requestAnimationFrame(draw);
    });

    return () => {
      cancelAnimationFrame(frame);
      unsubscribe();
      resize.disconnect();
      simulation.stop();
    };
  }, [events, subscribe]);

  return (
    <>
      <div
        ref={wrapRef}
        className="flow"
        onPointerMove={(e) => {
          const rect = e.currentTarget.getBoundingClientRect();
          setHover(findRef.current(e.clientX - rect.left, e.clientY - rect.top));
        }}
        onPointerLeave={() => setHover(null)}
      >
        <canvas ref={canvasRef} role="img" aria-label={`Money flow network: ${summary}`} />
        {hover && (
          <div className="tooltip" style={{ left: hover.x + 14, top: hover.y + 14 }}>
            <strong>{hover.node.name}</strong>
            <span className="muted">{ROLE_NAMES[hover.node.role]}</span>
            <div>{hover.links} recent money flows</div>
          </div>
        )}
      </div>
      <FlowLegend />
    </>
  );
}

function FlowLegend() {
  return (
    <ul className="legend" style={{ marginTop: 10 }}>
      <li>
        <span className="swatch" style={{ "--swatch": labelVar("normal") } as never} />
        {LABEL_NAMES.normal}
      </li>
      {FRAUD_LABELS.map((label) => (
        <li key={label}>
          <span className="swatch" style={{ "--swatch": labelVar(label), height: 4 } as never} />
          {LABEL_NAMES[label]}
        </li>
      ))}
      <li>
        <svg width="10" height="10" aria-hidden>
          <circle cx="5" cy="5" r="3.5" fill="var(--ink-muted)" />
        </svg>
        Customer
      </li>
      <li>
        <svg width="10" height="10" aria-hidden>
          <rect x="1" y="1" width="8" height="8" rx="2" fill="var(--ink-secondary)" />
        </svg>
        Shop
      </li>
      <li>
        <svg width="12" height="12" aria-hidden>
          <path d="M6 0.5 L11.5 6 L6 11.5 L0.5 6 Z" fill="var(--ink)" />
        </svg>
        Fraudster account
      </li>
      <li>
        <svg width="12" height="12" aria-hidden>
          <circle cx="6" cy="6" r="5" fill="none" stroke="var(--ink)" strokeWidth="1.5" />
        </svg>
        Outside world
      </li>
    </ul>
  );
}

function radius(node: Node): number {
  return { customer: 3.2, merchant: 5, fraudster: 6.5, external: 11 }[node.role];
}

/** Eases the camera towards whatever frames every node, so the graph never drifts off screen. */
function fitView(view: { k: number; x: number; y: number }, nodes: Map<string, Node>, size: { width: number; height: number }) {
  if (!nodes.size || !size.width) return;
  let minX = Infinity;
  let minY = Infinity;
  let maxX = -Infinity;
  let maxY = -Infinity;
  for (const node of nodes.values()) {
    minX = Math.min(minX, node.x!);
    maxX = Math.max(maxX, node.x!);
    minY = Math.min(minY, node.y!);
    maxY = Math.max(maxY, node.y!);
  }
  const padding = 40;
  const k = Math.min(
    (size.width - padding * 2) / Math.max(maxX - minX, 1),
    (size.height - padding * 2) / Math.max(maxY - minY, 1),
  );
  const target = { k: Math.min(Math.max(k, 0.3), 2.2), x: (minX + maxX) / 2, y: (minY + maxY) / 2 };
  view.k += (target.k - view.k) * 0.06;
  view.x += (target.x - view.x) * 0.06;
  view.y += (target.y - view.y) * 0.06;
}

function paint(
  context: CanvasRenderingContext2D,
  palette: Palette,
  view: { k: number; x: number; y: number },
  size: { width: number; height: number },
  links: Map<string, Link>,
  nodes: Map<string, Node>,
  pulses: Pulse[],
  now: number,
  time: number,
) {
  const dpr = window.devicePixelRatio || 1;
  context.setTransform(1, 0, 0, 1, 0, 0);
  context.clearRect(0, 0, context.canvas.width, context.canvas.height);
  context.setTransform(
    dpr * view.k,
    0,
    0,
    dpr * view.k,
    dpr * (size.width / 2 - view.x * view.k),
    dpr * (size.height / 2 - view.y * view.k),
  );
  const px = 1 / view.k; // one screen pixel in graph units

  // Everyday lines first, so fraud is always drawn on top.
  const ordered = [...links.values()].sort((a, b) => Number(a.label !== "normal") - Number(b.label !== "normal"));
  for (const link of ordered) {
    const fraud = link.label !== "normal";
    const age = now - link.at;
    const fade = Math.max(0, 1 - age / (fraud ? FRAUD_TTL : NORMAL_TTL));
    context.globalAlpha = fraud ? 0.35 + 0.65 * fade : 0.9 * fade;
    context.strokeStyle = palette.labels[link.label];
    context.lineWidth = (fraud ? 2 : 1) * px;
    context.beginPath();
    context.moveTo(link.source.x!, link.source.y!);
    context.lineTo(link.target.x!, link.target.y!);
    context.stroke();
  }

  // A dot travelling from payer to payee for each new transfer.
  context.globalAlpha = 1;
  for (const pulse of pulses) {
    const t = easeOut(Math.min(1, (time - pulse.start) / PULSE_MS));
    const { source, target } = pulse.link;
    context.fillStyle = pulse.label === "normal" ? palette.inkSecondary : palette.labels[pulse.label];
    context.beginPath();
    context.arc(source.x! + (target.x! - source.x!) * t, source.y! + (target.y! - source.y!) * t, 2.5 * px, 0, Math.PI * 2);
    context.fill();
  }

  for (const node of nodes.values()) {
    const x = node.x!;
    const y = node.y!;
    const r = radius(node) * px;
    context.beginPath();
    switch (node.role) {
      case "customer":
        context.arc(x, y, r, 0, Math.PI * 2);
        context.fillStyle = palette.inkMuted;
        context.fill();
        break;
      case "merchant":
        context.roundRect(x - r, y - r, r * 2, r * 2, 2 * px);
        context.fillStyle = palette.inkSecondary;
        context.fill();
        break;
      case "fraudster":
        context.moveTo(x, y - r);
        context.lineTo(x + r, y);
        context.lineTo(x, y + r);
        context.lineTo(x - r, y);
        context.closePath();
        context.fillStyle = palette.ink;
        context.strokeStyle = palette.surface;
        context.lineWidth = 2 * px;
        context.fill();
        context.stroke();
        break;
      case "external":
        context.arc(x, y, r, 0, Math.PI * 2);
        context.fillStyle = palette.surface;
        context.strokeStyle = palette.ink;
        context.lineWidth = 1.5 * px;
        context.fill();
        context.stroke();
        break;
    }
  }

  // Direct labels only where they matter: the outside world, then the most recently active
  // fraudster accounts. A label that would collide with one already drawn is skipped.
  const labelled = [...nodes.values()]
    .filter((n) => n.role === "external" || n.role === "fraudster")
    .sort((a, b) => Number(b.role === "external") - Number(a.role === "external") || b.lastActive - a.lastActive);
  const taken: { x1: number; y1: number; x2: number; y2: number }[] = [];
  context.font = `${11 * px}px system-ui, -apple-system, sans-serif`;
  context.textAlign = "center";
  context.lineJoin = "round";
  for (const node of labelled) {
    const text = node.role === "external" ? "Outside world" : node.name;
    const half = context.measureText(text).width / 2;
    const baseline = node.y! + radius(node) * px + 12 * px;
    const box = { x1: node.x! - half, y1: baseline - 10 * px, x2: node.x! + half, y2: baseline + 3 * px };
    if (taken.some((t) => box.x1 < t.x2 && box.x2 > t.x1 && box.y1 < t.y2 && box.y2 > t.y1)) continue;
    taken.push(box);
    // A halo in the background colour keeps the text readable where lines pass behind it.
    context.strokeStyle = palette.surface;
    context.lineWidth = 3 * px;
    context.strokeText(text, node.x!, baseline);
    context.fillStyle = palette.inkSecondary;
    context.fillText(text, node.x!, baseline);
  }
}

const easeOut = (t: number) => 1 - (1 - t) ** 3;
