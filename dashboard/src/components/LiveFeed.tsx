import type { RefObject } from "react";
import type { TransferEvent } from "../api";
import { formatMoney, formatTime, LABEL_NAMES, labelVar } from "../theme";

const ROWS = 14;

/** Also the accessible table view of everything the charts show. */
export function LiveFeed({ events }: { events: RefObject<TransferEvent[]>; version: number }) {
  const latest = events.current.slice(-ROWS).reverse();
  if (!latest.length) return <p className="caption">No transfers yet.</p>;

  return (
    <table className="feed">
      <thead>
        <tr>
          <th scope="col">Time</th>
          <th scope="col">From → to</th>
          <th scope="col" style={{ textAlign: "right" }}>
            Amount
          </th>
          <th scope="col">What it was</th>
        </tr>
      </thead>
      <tbody>
        {latest.map((event) => (
          <tr
            key={`${event.at}-${event.fromId}-${event.toId}`}
            className={event.label === "normal" ? undefined : "fraud"}
            style={{ "--accent": labelVar(event.label) } as never}
          >
            <td className="time">{formatTime(event.at)}</td>
            <td className="parties">
              {event.fromName} <span className="arrow">→</span> {event.toName}
            </td>
            <td className="amount">{formatMoney(event.amountMinor)}</td>
            <td>
              <span className="chip">{event.label === "normal" ? kindName(event.kind) : LABEL_NAMES[event.label]}</span>
              {event.status === "rejected" && <span className="declined">Declined</span>}
            </td>
          </tr>
        ))}
      </tbody>
    </table>
  );
}

function kindName(kind: TransferEvent["kind"]): string {
  return { purchase: "Purchase", p2p: "Paid a friend", salary: "Salary", "cash-out": "Cash out" }[kind];
}
