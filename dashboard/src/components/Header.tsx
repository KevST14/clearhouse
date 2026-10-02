import { useEffect, useState } from "react";
import { control, type Connection, type SimState } from "../api";

const CONNECTION_TEXT: Record<Connection, string> = {
  connecting: "Connecting…",
  live: "Live",
  offline: "Offline",
};

export function Header({
  state,
  connection,
  onState,
}: {
  state: SimState | null;
  connection: Connection;
  onState: (state: SimState) => void;
}) {
  const [rate, setRate] = useState(3);
  useEffect(() => {
    if (state) setRate(state.rate);
  }, [state?.rate]);

  const update = async (body: { running?: boolean; rate?: number }) => onState(await control(body));

  return (
    <header className="header">
      <div className="brand">
        <Logo />
        <div>
          <h1>Clearhouse control room</h1>
          <p>A simulated bank: everyday customers, plus fraud you can launch on demand.</p>
        </div>
      </div>
      <div className="controls">
        <span className="pill" data-state={connection}>
          <span className="dot" aria-hidden />
          {CONNECTION_TEXT[connection]}
        </span>
        <label className="rate">
          Everyday traffic
          <input
            type="range"
            min={0.5}
            max={15}
            step={0.5}
            value={rate}
            disabled={!state}
            onChange={(e) => setRate(Number(e.target.value))}
            onPointerUp={() => void update({ rate })}
            onKeyUp={() => void update({ rate })}
          />
          <output>{rate.toFixed(1)} / sec</output>
        </label>
        <button className="button" disabled={!state} onClick={() => void update({ running: !state?.running })}>
          {state?.running === false ? "▶ Resume" : "❚❚ Pause"}
        </button>
      </div>
    </header>
  );
}

/** Two overlapping squares: every transfer is a matching debit and credit. */
function Logo() {
  return (
    <svg width="34" height="34" viewBox="0 0 34 34" aria-hidden>
      <rect x="3" y="3" width="18" height="18" rx="4" fill="var(--label-card-testing)" />
      <rect
        x="13"
        y="13"
        width="18"
        height="18"
        rx="4"
        fill="var(--label-money-mule)"
        stroke="var(--page)"
        strokeWidth="2"
      />
    </svg>
  );
}
