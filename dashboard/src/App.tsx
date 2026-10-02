import type { ReactNode } from "react";
import { useSimulation } from "./api";
import { Header } from "./components/Header";
import { LiveFeed } from "./components/LiveFeed";
import { MoneyFlowGraph } from "./components/MoneyFlowGraph";
import { ScenarioLauncher } from "./components/ScenarioLauncher";
import { StatTiles } from "./components/StatTiles";
import { ThroughputChart } from "./components/ThroughputChart";
import { usePalette, useReducedMotion } from "./theme";

export default function App() {
  const sim = useSimulation();
  const palette = usePalette();
  const reducedMotion = useReducedMotion();

  return (
    <div className="page">
      <Header state={sim.state} connection={sim.connection} onState={sim.setState} />

      {sim.connection === "offline" && (
        <div className="banner" role="alert">
          Can't reach the traffic generator. Start the ledger, then run <code>uv run trafficgen</code> in{" "}
          <code>generator/</code>.
        </div>
      )}

      <Panel
        title="Launch a fraud attack"
        caption="Everyday customers pay shops and friends in the background. Launch an attack to watch what that kind of fraud looks like in the money flow."
      >
        <ScenarioLauncher state={sim.state} />
      </Panel>

      <StatTiles state={sim.state} />

      <div className="layout">
        <Panel
          title="Money flow"
          caption="Every dot is an account and every line is money moving between two accounts in the last minute or so. Fraud lines stay longer so you can study their shape."
        >
          <MoneyFlowGraph
            events={sim.events}
            subscribe={sim.subscribe}
            palette={palette}
            reducedMotion={reducedMotion}
          />
        </Panel>
        <Panel title="Live feed" caption="The latest transfers, newest first, with what each one really was.">
          <LiveFeed events={sim.events} version={sim.version} />
        </Panel>
      </div>

      <Panel
        title="Transfers over the last two minutes"
        caption="Each bar counts the transfers in a two-second window. Fraud shows up as coloured bursts on top of the everyday grey."
      >
        <ThroughputChart events={sim.events} version={sim.version} />
      </Panel>

      <p className="footer">
        Everything here is simulated. The labels come from the traffic generator, which knows the truth; the ledger
        itself only sees money moving.
      </p>
    </div>
  );
}

function Panel({ title, caption, children }: { title: string; caption: string; children: ReactNode }) {
  return (
    <section className="panel" aria-label={title}>
      <div className="panel-head">
        <div>
          <h2>{title}</h2>
          <p className="caption">{caption}</p>
        </div>
      </div>
      {children}
    </section>
  );
}
