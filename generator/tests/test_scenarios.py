"""Each fraud scenario must produce the shape that makes it recognisable."""

import pytest

from trafficgen.models import Label, TransferRecord
from trafficgen.population import Role
from trafficgen.scenarios import SCENARIOS, everyday_payment

pytestmark = pytest.mark.anyio


async def run_scenario(sim, kind: Label) -> list[TransferRecord]:
    queue = sim.subscribe()
    await SCENARIOS[kind].run(sim, "test")
    records = []
    while not queue.empty():
        records.append(queue.get_nowait())
    assert records and all(r.label is kind and r.scenario_id == "test" for r in records)
    return records


async def test_everyday_traffic_is_labelled_normal_and_mostly_goes_through(sim):
    queue = sim.subscribe()
    for _ in range(300):
        await everyday_payment(sim)
    records = [queue.get_nowait() for _ in range(queue.qsize())]

    assert {r.label for r in records} == {Label.NORMAL}
    assert {r.kind for r in records} >= {"purchase", "p2p"}
    accepted = sum(r.status == "accepted" for r in records)
    assert accepted / len(records) > 0.95


async def test_card_testing_is_a_burst_of_tiny_payments_at_many_shops(sim):
    records = await run_scenario(sim, Label.CARD_TESTING)

    probes, cash_out = records[:-1], records[-1]
    assert len(probes) >= 12
    assert all(r.amount_minor < 200 for r in probes)
    assert len({r.to_id for r in probes}) == len(probes), "every probe hits a different shop"
    assert len({r.from_id for r in records}) == 1, "all from the one stolen card"
    assert cash_out.amount_minor > 100 * max(r.amount_minor for r in probes)


async def test_money_mule_collects_from_many_victims_then_moves_it_on(sim):
    records = await run_scenario(sim, Label.MONEY_MULE)

    mule = records[0].to_id
    into_mule = [r for r in records if r.to_id == mule]
    out_of_mule = [r for r in records if r.from_id == mule]
    assert records[0].to_role is Role.FRAUDSTER
    assert len({r.from_id for r in into_mule}) >= 5, "fan-in from several victims"
    assert all(r.from_role is Role.CUSTOMER for r in into_mule)
    received = sum(r.amount_minor for r in into_mule if r.status == "accepted")
    passed_on = sum(r.amount_minor for r in out_of_mule if r.status == "accepted")
    assert passed_on >= 0.85 * received, "the mule keeps very little"
    assert any(r.kind == "cash-out" and r.to_role is Role.EXTERNAL for r in records)


async def test_account_takeover_drains_a_customer_to_brand_new_payees(sim):
    before = {c.id: c.balance for c in sim.customers}
    records = await run_scenario(sim, Label.ACCOUNT_TAKEOVER)

    victim = records[0].from_id
    assert all(r.from_id == victim for r in records)
    assert all(r.to_role is Role.FRAUDSTER for r in records)
    assert len({r.to_id for r in records}) == len(records), "a different new payee each time"
    drained = sum(r.amount_minor for r in records if r.status == "accepted")
    assert drained >= 0.8 * before[victim]


async def test_shadow_balances_match_the_ledger(sim, ledger):
    for _ in range(200):
        await everyday_payment(sim)
    for kind in (Label.CARD_TESTING, Label.MONEY_MULE, Label.ACCOUNT_TAKEOVER):
        await SCENARIOS[kind].run(sim, "test")

    for account in sim.accounts.values():
        if account.role is not Role.EXTERNAL:
            assert account.balance == ledger.balances[account.id], account.name
