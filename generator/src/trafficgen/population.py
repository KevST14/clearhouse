"""The simulated people and businesses, and how they normally spend."""

import random
from dataclasses import dataclass, field
from enum import StrEnum


class Role(StrEnum):
    CUSTOMER = "customer"
    MERCHANT = "merchant"
    # Accounts opened by fraudsters: money mules and the new payees in an account takeover.
    FRAUDSTER = "fraudster"
    # The ledger's external account: money arriving from (or leaving to) the outside world.
    EXTERNAL = "external"


@dataclass
class Account:
    id: str
    name: str
    role: Role
    # The generator's own estimate of the balance, used to pick sensible amounts.
    # The ledger is the real source of truth and rejects anything it can't cover.
    balance: int = 0
    category: str | None = None
    activity: float = 1.0
    usual_merchants: list[str] = field(default_factory=list)
    friends: list[str] = field(default_factory=list)


@dataclass(frozen=True)
class MerchantCategory:
    name: str
    typical_minor: int
    merchants: tuple[str, ...]


# Typical spend is the median of a log-normal distribution: most purchases sit near it,
# with the occasional much bigger one, which is how real card spending looks.
CATEGORIES = (
    MerchantCategory("groceries", 2_800, ("Corner Grocer", "FreshCo", "Market Hall", "Green Basket")),
    MerchantCategory("coffee", 380, ("Bean There", "Daily Grind", "Steam Room")),
    MerchantCategory("transport", 650, ("City Transit", "RideShare", "Rail Direct")),
    MerchantCategory("eating out", 3_200, ("Noodle Bar", "Pizzeria Uno", "The Local", "Taco Shack")),
    MerchantCategory("bills", 6_500, ("Volt Energy", "Stream+", "MobileNet")),
    MerchantCategory("online", 4_200, ("ShopOnline", "GadgetHub", "BookNook", "StyleBox", "PlayStore")),
)

FIRST_NAMES = (
    "Amara Ben Chloe Dev Ella Farah George Hana Isaac Jade Kofi Lena Marcus Nia Oscar Priya Quinn Ravi "
    "Sofia Tom Uma Victor Wren Yusuf Zara Aiden Bea Callum Daisy Ethan Freya Hugo Imogen Jonah Kemi Leo"
).split()


def customer_names(rng: random.Random, count: int) -> list[str]:
    initials = "ABCDEFGHIJKLMNOPRSTW"
    names: set[str] = set()
    while len(names) < count:
        names.add(f"{rng.choice(FIRST_NAMES)} {rng.choice(initials)}.")
    return sorted(names)


def merchant_amount(category: str, rng: random.Random) -> int:
    typical = next(c.typical_minor for c in CATEGORIES if c.name == category)
    return max(50, round(rng.lognormvariate(0, 0.55) * typical))


def salary(rng: random.Random) -> int:
    return round(rng.lognormvariate(0, 0.35) * 120_000)


def wire_up(customers: list[Account], merchants: list[Account], rng: random.Random) -> None:
    """Gives everyone habits: a few favourite shops, a few friends, and how often they spend."""
    for customer in customers:
        customer.activity = rng.lognormvariate(0, 0.6)
        customer.usual_merchants = [m.id for m in rng.sample(merchants, k=min(5, len(merchants)))]
        others = [c for c in customers if c is not customer]
        customer.friends = [c.id for c in rng.sample(others, k=min(3, len(others)))]
