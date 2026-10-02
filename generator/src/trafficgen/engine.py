"""Runs the simulation: everyday traffic in the background, fraud scenarios on demand."""

import asyncio
import logging
import random
import uuid
from collections import Counter
from collections.abc import Coroutine
from datetime import UTC, datetime

from trafficgen.ledger import EXTERNAL_GBP, Ledger
from trafficgen.models import Label, LabelTotals, ScenarioRun, State, TransferRecord
from trafficgen.population import CATEGORIES, Account, Role, customer_names, salary, wire_up
from trafficgen.scenarios import SCENARIOS, everyday_payment
from trafficgen.store import Store

log = logging.getLogger(__name__)


class Simulation:
    def __init__(
        self,
        ledger: Ledger,
        store: Store,
        *,
        customers: int = 120,
        rate: float = 3.0,
        seed: int | None = None,
        time_scale: float = 1.0,
        max_in_flight: int = 16,
    ) -> None:
        self.ledger = ledger
        self.store = store
        self.rng = random.Random(seed)
        self.rate = rate
        self.running = False
        # Multiplies the pauses inside fraud scenarios; tests set it to 0.
        self.time_scale = time_scale
        self.external = Account(EXTERNAL_GBP, "Outside world", Role.EXTERNAL)
        self.accounts: dict[str, Account] = {self.external.id: self.external}
        self.customers: list[Account] = []
        self.merchants: list[Account] = []
        self.scenarios: dict[str, ScenarioRun] = {}
        self.errors = 0
        self.last_error: str | None = None
        self._customer_count = customers
        self._counts: Counter[tuple[Label, str]] = Counter()
        self._volume: Counter[Label] = Counter()
        self._subscribers: set[asyncio.Queue[TransferRecord]] = set()
        self._tasks: set[asyncio.Task[None]] = set()
        self._in_flight = asyncio.Semaphore(max_in_flight)

    async def start(self, *, running: bool = True) -> None:
        await self._populate()
        self.running = running
        self._spawn(self._everyday_traffic())

    async def stop(self) -> None:
        for task in self._tasks:
            task.cancel()
        await asyncio.gather(*self._tasks, return_exceptions=True)

    async def _populate(self) -> None:
        """Opens an account for every shop and customer, then pays everyone an opening salary."""
        self.store.add_account(self.external)
        merchants = [(name, c.name) for c in CATEGORIES for name in c.merchants]
        names = customer_names(self.rng, self._customer_count)

        async def open_one(name: str, role: Role, category: str | None = None) -> Account:
            async with self._in_flight:
                account = Account(await self.ledger.open_account(name), name, role, category=category)
            self._register(account)
            return account

        self.merchants = list(await asyncio.gather(*(open_one(n, Role.MERCHANT, c) for n, c in merchants)))
        self.customers = list(await asyncio.gather(*(open_one(n, Role.CUSTOMER) for n in names)))
        wire_up(self.customers, self.merchants, self.rng)

        async def fund(customer: Account) -> None:
            async with self._in_flight:
                await self.pay(self.external, customer, salary(self.rng), label=Label.NORMAL, kind="salary")

        await asyncio.gather(*(fund(c) for c in self.customers))

    def _register(self, account: Account) -> None:
        self.accounts[account.id] = account
        self.store.add_account(account)

    async def open_fraudster_account(self, prefix: str) -> Account:
        name = f"{prefix} {uuid.uuid4().hex[:4].upper()}"
        account = Account(await self.ledger.open_account(name), name, Role.FRAUDSTER)
        self._register(account)
        return account

    def pick_customer(self, min_balance: int = 0) -> Account:
        """A customer chosen in proportion to how much they usually spend."""
        candidates = [c for c in self.customers if c.balance >= min_balance] or self.customers
        return self.rng.choices(candidates, weights=[c.activity for c in candidates])[0]

    def pick_customers(self, count: int, min_balance: int = 0) -> list[Account]:
        candidates = [c for c in self.customers if c.balance >= min_balance] or self.customers
        return self.rng.sample(candidates, k=min(count, len(candidates)))

    async def sleep(self, seconds: float) -> None:
        await asyncio.sleep(seconds * self.time_scale)

    async def pay(
        self,
        payer: Account,
        payee: Account,
        amount: int,
        *,
        label: Label,
        kind: str,
        scenario_id: str | None = None,
    ) -> TransferRecord | None:
        if amount <= 0:
            return None
        # Reserve the money locally first, so concurrent payments don't all count on the same balance.
        payer.balance -= amount
        try:
            outcome = await self.ledger.transfer(payer.id, payee.id, amount)
        except Exception:
            payer.balance += amount
            raise
        if outcome.accepted:
            payee.balance += amount
        else:
            payer.balance += amount

        record = TransferRecord(
            transfer_id=outcome.transfer_id,
            at=datetime.now(UTC),
            from_id=payer.id,
            from_name=payer.name,
            from_role=payer.role,
            to_id=payee.id,
            to_name=payee.name,
            to_role=payee.role,
            amount_minor=amount,
            label=label,
            kind=kind,
            scenario_id=scenario_id,
            status="accepted" if outcome.accepted else "rejected",
            rejection=outcome.rejection,
        )
        self.store.add_transfer(record)
        self._counts[label, record.status] += 1
        if outcome.accepted:
            self._volume[label] += amount
        if scenario_id in self.scenarios:
            self.scenarios[scenario_id].transfers += 1
        for queue in self._subscribers:
            if not queue.full():
                queue.put_nowait(record)
        return record

    def launch(self, kind: Label) -> ScenarioRun:
        scenario = SCENARIOS[kind]
        run = ScenarioRun(
            id=f"{kind}-{uuid.uuid4().hex[:6]}",
            kind=kind,
            title=scenario.info.title,
            status="running",
            started_at=datetime.now(UTC),
        )
        self.scenarios[run.id] = run

        async def execute() -> None:
            try:
                await scenario.run(self, run.id)
                run.status = "finished"
            except Exception as e:
                log.exception("Scenario %s failed", run.id)
                self._record_error(e)
                run.status = "failed"
            finally:
                run.finished_at = datetime.now(UTC)

        self._spawn(execute())
        return run

    def subscribe(self) -> asyncio.Queue[TransferRecord]:
        queue: asyncio.Queue[TransferRecord] = asyncio.Queue(maxsize=1_000)
        self._subscribers.add(queue)
        return queue

    def unsubscribe(self, queue: asyncio.Queue[TransferRecord]) -> None:
        self._subscribers.discard(queue)

    def state(self) -> State:
        return State(
            running=self.running,
            rate=self.rate,
            customers=len(self.customers),
            merchants=len(self.merchants),
            fraudster_accounts=sum(1 for a in self.accounts.values() if a.role is Role.FRAUDSTER),
            totals=[
                LabelTotals(
                    label=label,
                    accepted=self._counts[label, "accepted"],
                    rejected=self._counts[label, "rejected"],
                    volume_minor=self._volume[label],
                )
                for label in Label
            ],
            scenarios=list(self.scenarios.values())[-20:],
            available_scenarios=[s.info for s in SCENARIOS.values()],
            errors=self.errors,
            last_error=self.last_error,
        )

    async def _everyday_traffic(self) -> None:
        """Starts everyday payments at random intervals averaging `rate` per second, like
        independent customers acting on their own."""
        while True:
            if not self.running:
                await asyncio.sleep(0.2)
                continue
            await asyncio.sleep(self.rng.expovariate(self.rate))
            await self._in_flight.acquire()
            self._spawn(self._everyday_payment())

    async def _everyday_payment(self) -> None:
        try:
            await everyday_payment(self)
        except Exception as e:
            self._record_error(e)
        finally:
            self._in_flight.release()

    def _record_error(self, error: Exception) -> None:
        self.errors += 1
        self.last_error = f"{type(error).__name__}: {error}"
        log.warning("Transfer failed: %s", self.last_error)

    def _spawn(self, coroutine: Coroutine[None, None, None]) -> None:
        task = asyncio.create_task(coroutine)
        self._tasks.add(task)
        task.add_done_callback(self._tasks.discard)
