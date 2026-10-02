import { useEffect, useRef, useState } from "react";

export type Label = "normal" | "card_testing" | "money_mule" | "account_takeover";
export type Role = "customer" | "merchant" | "fraudster" | "external";

export interface TransferEvent {
  transferId: string | null;
  at: string;
  fromId: string;
  fromName: string;
  fromRole: Role;
  toId: string;
  toName: string;
  toRole: Role;
  amountMinor: number;
  label: Label;
  kind: "purchase" | "p2p" | "salary" | "cash-out";
  scenarioId: string | null;
  status: "accepted" | "rejected";
  rejection: string | null;
}

export interface ScenarioInfo {
  kind: Label;
  title: string;
  description: string;
}

export interface ScenarioRun {
  id: string;
  kind: Label;
  title: string;
  status: "running" | "finished" | "failed";
  startedAt: string;
  finishedAt: string | null;
  transfers: number;
}

export interface SimState {
  running: boolean;
  rate: number;
  customers: number;
  merchants: number;
  fraudsterAccounts: number;
  totals: { label: Label; accepted: number; rejected: number; volumeMinor: number }[];
  scenarios: ScenarioRun[];
  availableScenarios: ScenarioInfo[];
  errors: number;
  lastError: string | null;
}

export type Connection = "connecting" | "live" | "offline";

async function send<T>(path: string, init?: RequestInit): Promise<T> {
  const response = await fetch(path, {
    ...init,
    headers: { "Content-Type": "application/json", ...init?.headers },
  });
  if (!response.ok) throw new Error(`${path}: ${response.status}`);
  return response.json() as Promise<T>;
}

export const control = (body: { running?: boolean; rate?: number }) =>
  send<SimState>("/api/control", { method: "POST", body: JSON.stringify(body) });

export const launchScenario = (kind: Label) =>
  send<ScenarioRun>("/api/scenarios", { method: "POST", body: JSON.stringify({ kind }) });

const MAX_EVENTS = 1_500;

/**
 * Polls the simulation's state and follows its live stream of transfers.
 * Events are kept in a ref (they arrive many times a second) and `version` bumps at
 * most once per animation frame so the UI re-renders in step with the screen.
 */
export function useSimulation() {
  const [state, setState] = useState<SimState | null>(null);
  const [connection, setConnection] = useState<Connection>("connecting");
  const [version, setVersion] = useState(0);
  const events = useRef<TransferEvent[]>([]);
  const listeners = useRef(new Set<(event: TransferEvent) => void>());

  useEffect(() => {
    let stopped = false;
    const poll = async () => {
      try {
        const next = await send<SimState>("/api/state");
        if (!stopped) setState(next);
      } catch {
        if (!stopped) setConnection("offline");
      }
    };
    void poll();
    const timer = setInterval(poll, 1_500);
    return () => {
      stopped = true;
      clearInterval(timer);
    };
  }, []);

  useEffect(() => {
    let source: EventSource | null = null;
    let cancelled = false;
    let frame = 0;
    const bump = () => {
      if (!frame) {
        frame = requestAnimationFrame(() => {
          frame = 0;
          setVersion((v) => v + 1);
        });
      }
    };

    send<TransferEvent[]>("/api/transfers/recent?limit=400")
      .then((recent) => {
        if (cancelled) return;
        events.current = recent;
        bump();
        source = new EventSource("/api/stream");
        source.onopen = () => setConnection("live");
        source.onerror = () => setConnection("connecting");
        source.onmessage = (message) => {
          const event = JSON.parse(message.data) as TransferEvent;
          events.current.push(event);
          if (events.current.length > MAX_EVENTS) events.current.splice(0, events.current.length - MAX_EVENTS);
          listeners.current.forEach((listener) => listener(event));
          bump();
        };
      })
      .catch(() => setConnection("offline"));

    return () => {
      cancelled = true;
      source?.close();
      cancelAnimationFrame(frame);
    };
  }, []);

  const subscribe = (listener: (event: TransferEvent) => void) => {
    listeners.current.add(listener);
    return () => void listeners.current.delete(listener);
  };

  return { state, setState, connection, events, version, subscribe };
}
