import type { SimState } from "../api";
import { formatMoneyCompact } from "../theme";

export function StatTiles({ state }: { state: SimState | null }) {
  const totals = state?.totals ?? [];
  const accepted = totals.reduce((sum, t) => sum + t.accepted, 0);
  const rejected = totals.reduce((sum, t) => sum + t.rejected, 0);
  const volume = totals.reduce((sum, t) => sum + t.volumeMinor, 0);
  const fraud = totals.filter((t) => t.label !== "normal").reduce((sum, t) => sum + t.accepted + t.rejected, 0);
  const all = accepted + rejected;
  const dash = state ? undefined : "–";

  return (
    <div className="tiles">
      <Tile name="Transfers" value={dash ?? accepted.toLocaleString("en-GB")} note="went through since the simulation started" />
      <Tile name="Money moved" value={dash ?? formatMoneyCompact(volume)} note="including salaries paid in" />
      <Tile
        name="Fraudulent transfers"
        value={dash ?? fraud.toLocaleString("en-GB")}
        note={all ? `${((fraud / all) * 100).toFixed(1)}% of all attempts` : "none yet"}
      />
      <Tile name="Declined" value={dash ?? rejected.toLocaleString("en-GB")} note="the ledger refused: not enough money" />
    </div>
  );
}

function Tile({ name, value, note }: { name: string; value: string; note: string }) {
  return (
    <div className="tile">
      <div className="name">{name}</div>
      <div className="value">{value}</div>
      <div className="note">{note}</div>
    </div>
  );
}
