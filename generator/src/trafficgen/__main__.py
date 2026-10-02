import argparse
import logging
from pathlib import Path

import uvicorn

from trafficgen.api import Settings, create_app


def main() -> None:
    parser = argparse.ArgumentParser(description="Simulate customers, merchants and fraudsters using the ledger.")
    parser.add_argument("--ledger-url", default="http://localhost:8080")
    parser.add_argument("--port", type=int, default=8090)
    parser.add_argument("--customers", type=int, default=120)
    parser.add_argument("--rate", type=float, default=3.0, help="everyday transfers per second")
    parser.add_argument("--seed", type=int, help="make the population and behaviour repeatable")
    parser.add_argument("--db", type=Path, default=Path("data/simulation.db"), help="where the labels are kept")
    parser.add_argument("--paused", action="store_true", help="start with everyday traffic paused")
    args = parser.parse_args()

    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(name)s: %(message)s")
    settings = Settings(
        ledger_url=args.ledger_url,
        db_path=args.db,
        customers=args.customers,
        rate=args.rate,
        seed=args.seed,
        paused=args.paused,
    )
    uvicorn.run(create_app(settings), host="127.0.0.1", port=args.port)


if __name__ == "__main__":
    main()
