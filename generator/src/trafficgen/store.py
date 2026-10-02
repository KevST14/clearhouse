"""SQLite record of every account and transfer the generator created, with its true label.

This is the answer key: the ledger only knows that money moved, while this file knows which
transfers were fraud. The data pipeline and the fraud analyst are scored against it later.
"""

import sqlite3
from pathlib import Path

from trafficgen.models import TransferRecord
from trafficgen.population import Account

SCHEMA = """
create table if not exists accounts (
    id         text primary key,
    name       text not null,
    role       text not null,
    category   text,
    created_at text not null default (strftime('%Y-%m-%dT%H:%M:%fZ', 'now'))
);

create table if not exists transfers (
    seq          integer primary key autoincrement,
    transfer_id  text,
    at           text not null,
    from_id      text not null references accounts (id),
    to_id        text not null references accounts (id),
    amount_minor integer not null,
    label        text not null,
    kind         text not null,
    scenario_id  text,
    status       text not null,
    rejection    text
);
"""


class Store:
    def __init__(self, path: str | Path) -> None:
        if str(path) != ":memory:":
            Path(path).parent.mkdir(parents=True, exist_ok=True)
        self._db = sqlite3.connect(path, check_same_thread=False)
        self._db.execute("pragma journal_mode = wal")
        self._db.executescript(SCHEMA)

    def add_account(self, account: Account) -> None:
        with self._db:
            self._db.execute(
                "insert or ignore into accounts (id, name, role, category) values (?, ?, ?, ?)",
                (account.id, account.name, account.role, account.category),
            )

    def add_transfer(self, record: TransferRecord) -> None:
        with self._db:
            self._db.execute(
                """insert into transfers (transfer_id, at, from_id, to_id, amount_minor, label, kind,
                                          scenario_id, status, rejection)
                   values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
                (
                    record.transfer_id,
                    record.at.isoformat(),
                    record.from_id,
                    record.to_id,
                    record.amount_minor,
                    record.label,
                    record.kind,
                    record.scenario_id,
                    record.status,
                    record.rejection,
                ),
            )

    def recent_transfers(self, limit: int) -> list[TransferRecord]:
        rows = self._db.execute(
            """select t.transfer_id, t.at, t.from_id, f.name, f.role, t.to_id, d.name, d.role,
                      t.amount_minor, t.label, t.kind, t.scenario_id, t.status, t.rejection
               from transfers t
               join accounts f on f.id = t.from_id
               join accounts d on d.id = t.to_id
               order by t.seq desc
               limit ?""",
            (limit,),
        ).fetchall()
        fields = list(TransferRecord.model_fields)
        return [TransferRecord(**dict(zip(fields, row, strict=True))) for row in reversed(rows)]

    def close(self) -> None:
        self._db.close()
