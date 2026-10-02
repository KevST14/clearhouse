"""How simulated people behave: everyday spending, and three kinds of fraud.

Each fraud scenario reproduces the shape that makes that fraud recognisable in real
payment data, because that shape is what detection will have to find.
"""

from collections.abc import Awaitable, Callable
from dataclasses import dataclass
from typing import TYPE_CHECKING

from trafficgen.models import Label, ScenarioInfo
from trafficgen.population import merchant_amount, salary

if TYPE_CHECKING:
    from trafficgen.engine import Simulation

LOW_BALANCE = 5_000


async def everyday_payment(sim: "Simulation") -> None:
    """One ordinary transfer: usually a purchase at a favourite shop, sometimes paying a friend
    back, and a salary top-up when someone is running low."""
    rng = sim.rng
    payer = sim.pick_customer()
    if payer.balance < LOW_BALANCE:
        await sim.pay(sim.external, payer, salary(rng), label=Label.NORMAL, kind="salary")
        return
    if payer.friends and rng.random() < 0.12:
        friend = sim.accounts[rng.choice(payer.friends)]
        await sim.pay(payer, friend, rng.randint(1_000, 15_000), label=Label.NORMAL, kind="p2p")
        return
    if rng.random() < 0.8:
        shop = sim.accounts[rng.choice(payer.usual_merchants)]
    else:
        shop = rng.choice(sim.merchants)
    await sim.pay(payer, shop, merchant_amount(shop.category, rng), label=Label.NORMAL, kind="purchase")


async def card_testing(sim: "Simulation", scenario_id: str) -> None:
    rng = sim.rng
    victim = sim.pick_customer(min_balance=20_000)
    shops = rng.sample(sim.merchants, k=min(len(sim.merchants), rng.randint(12, 20)))
    for shop in shops:
        await sim.pay(
            victim, shop, rng.randint(50, 199), label=Label.CARD_TESTING, kind="purchase", scenario_id=scenario_id
        )
        await sim.sleep(rng.uniform(0.2, 0.7))
    online = [m for m in sim.merchants if m.category == "online"]
    await sim.pay(
        victim,
        rng.choice(online),
        round(victim.balance * 0.8),
        label=Label.CARD_TESTING,
        kind="purchase",
        scenario_id=scenario_id,
    )


async def money_mule(sim: "Simulation", scenario_id: str) -> None:
    rng = sim.rng
    mule = await sim.open_fraudster_account("New account")
    for victim in sim.pick_customers(rng.randint(5, 9), min_balance=30_000):
        await sim.pay(
            victim,
            mule,
            round(victim.balance * rng.uniform(0.3, 0.7)),
            label=Label.MONEY_MULE,
            kind="p2p",
            scenario_id=scenario_id,
        )
        await sim.sleep(rng.uniform(0.5, 2.0))
    await sim.sleep(rng.uniform(2.0, 4.0))

    received = mule.balance
    for _ in range(2):
        onward = await sim.open_fraudster_account("New account")
        await sim.pay(mule, onward, round(received * 0.45), label=Label.MONEY_MULE, kind="p2p", scenario_id=scenario_id)
        await sim.sleep(rng.uniform(0.5, 1.5))
        await sim.pay(
            onward, sim.external, onward.balance, label=Label.MONEY_MULE, kind="cash-out", scenario_id=scenario_id
        )


async def account_takeover(sim: "Simulation", scenario_id: str) -> None:
    rng = sim.rng
    victim = sim.pick_customer(min_balance=50_000)
    payees = [await sim.open_fraudster_account("New payee") for _ in range(rng.randint(2, 4))]
    target = round(victim.balance * rng.uniform(0.85, 0.95))
    share = target // len(payees)
    for i, payee in enumerate(payees):
        amount = share if i < len(payees) - 1 else target - share * (len(payees) - 1)
        await sim.pay(victim, payee, amount, label=Label.ACCOUNT_TAKEOVER, kind="p2p", scenario_id=scenario_id)
        await sim.sleep(rng.uniform(1.0, 3.0))


@dataclass(frozen=True)
class Scenario:
    info: ScenarioInfo
    run: Callable[["Simulation", str], Awaitable[None]]


SCENARIOS = {
    s.info.kind: s
    for s in (
        Scenario(
            ScenarioInfo(
                kind=Label.CARD_TESTING,
                title="Card testing",
                description="Stolen card details are checked with a burst of tiny purchases at many "
                "different shops, seconds apart. If they go through, one large purchase follows.",
            ),
            card_testing,
        ),
        Scenario(
            ScenarioInfo(
                kind=Label.MONEY_MULE,
                title="Money mule ring",
                description="Scam victims are tricked into paying a freshly opened account. Within "
                "minutes it passes almost everything on to more new accounts, which cash out.",
            ),
            money_mule,
        ),
        Scenario(
            ScenarioInfo(
                kind=Label.ACCOUNT_TAKEOVER,
                title="Account takeover",
                description="A criminal gets into a long-standing customer's account and drains it "
                "with a few large payments to payees the customer has never paid before.",
            ),
            account_takeover,
        ),
    )
}
