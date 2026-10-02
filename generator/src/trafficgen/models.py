"""Shapes shared by the simulation, the database and the API (camelCase on the wire)."""

from datetime import datetime
from enum import StrEnum
from typing import Literal

from pydantic import BaseModel, ConfigDict
from pydantic.alias_generators import to_camel

from trafficgen.population import Role


class Label(StrEnum):
    """The ground truth for each transfer. Detection is scored against this later."""

    NORMAL = "normal"
    CARD_TESTING = "card_testing"
    MONEY_MULE = "money_mule"
    ACCOUNT_TAKEOVER = "account_takeover"


class ApiModel(BaseModel):
    model_config = ConfigDict(alias_generator=to_camel, populate_by_name=True)


class TransferRecord(ApiModel):
    transfer_id: str | None
    at: datetime
    from_id: str
    from_name: str
    from_role: Role
    to_id: str
    to_name: str
    to_role: Role
    amount_minor: int
    label: Label
    kind: Literal["purchase", "p2p", "salary", "cash-out"]
    scenario_id: str | None
    status: Literal["accepted", "rejected"]
    rejection: str | None


class ScenarioInfo(ApiModel):
    kind: Label
    title: str
    description: str


class ScenarioRun(ApiModel):
    id: str
    kind: Label
    title: str
    status: Literal["running", "finished", "failed"]
    started_at: datetime
    finished_at: datetime | None = None
    transfers: int = 0


class LabelTotals(ApiModel):
    label: Label
    accepted: int
    rejected: int
    volume_minor: int


class State(ApiModel):
    running: bool
    rate: float
    customers: int
    merchants: int
    fraudster_accounts: int
    totals: list[LabelTotals]
    scenarios: list[ScenarioRun]
    available_scenarios: list[ScenarioInfo]
    errors: int
    last_error: str | None
