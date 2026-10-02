import { useState } from "react";
import { launchScenario, type Label, type ScenarioRun, type SimState } from "../api";
import { labelVar } from "../theme";

export function ScenarioLauncher({ state }: { state: SimState | null }) {
  const [launching, setLaunching] = useState<Label | null>(null);
  if (!state) return <p className="caption">Waiting for the simulation…</p>;

  const launch = async (kind: Label) => {
    setLaunching(kind);
    try {
      await launchScenario(kind);
    } finally {
      setLaunching(null);
    }
  };

  return (
    <div className="scenarios">
      {state.availableScenarios.map((scenario) => {
        const latest = state.scenarios.filter((run) => run.kind === scenario.kind).at(-1);
        return (
          <article key={scenario.kind} className="scenario" style={{ "--accent": labelVar(scenario.kind) } as never}>
            <h3>{scenario.title}</h3>
            <p>{scenario.description}</p>
            <footer>
              <RunStatus run={latest} />
              <button
                className="button"
                disabled={launching === scenario.kind}
                onClick={() => void launch(scenario.kind)}
              >
                Launch
              </button>
            </footer>
          </article>
        );
      })}
    </div>
  );
}

function RunStatus({ run }: { run: ScenarioRun | undefined }) {
  if (!run) return <span>Not launched yet</span>;
  if (run.status === "running")
    return (
      <span>
        <span className="running-dot" aria-hidden />
        Running · {run.transfers} transfers
      </span>
    );
  const when = new Date(run.finishedAt ?? run.startedAt).toLocaleTimeString("en-GB");
  return (
    <span>
      {run.status === "failed" ? "Failed" : "Finished"} at {when} · {run.transfers} transfers
    </span>
  );
}
