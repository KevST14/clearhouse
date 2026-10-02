import uuid

import pytest

from trafficgen.engine import Simulation
from trafficgen.ledger import EXTERNAL_GBP, TransferOutcome
from trafficgen.store import Store


class FakeLedger:
    """In-memory stand-in for the ledger with the same overdraft rule."""

    def __init__(self) -> None:
        self.balances: dict[str, int] = {EXTERNAL_GBP: 0}
        self.transfers: list[tuple[str, str, int]] = []

    async def open_account(self, owner_name: str) -> str:
        account_id = str(uuid.uuid4())
        self.balances[account_id] = 0
        return account_id

    async def transfer(self, from_id: str, to_id: str, amount_minor: int) -> TransferOutcome:
        if from_id != EXTERNAL_GBP and self.balances[from_id] < amount_minor:
            return TransferOutcome(transfer_id=None, rejection="Insufficient funds")
        self.balances[from_id] -= amount_minor
        self.balances[to_id] += amount_minor
        self.transfers.append((from_id, to_id, amount_minor))
        return TransferOutcome(transfer_id=str(uuid.uuid4()))

    async def aclose(self) -> None:
        pass


@pytest.fixture
def anyio_backend() -> str:
    return "asyncio"


@pytest.fixture
def ledger() -> FakeLedger:
    return FakeLedger()


@pytest.fixture
async def sim(ledger: FakeLedger):
    simulation = Simulation(ledger, Store(":memory:"), customers=40, seed=7, time_scale=0)
    await simulation.start(running=False)
    yield simulation
    await simulation.stop()
