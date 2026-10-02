"""HTTP API the dashboard uses to watch and steer the simulation."""

import asyncio
from collections.abc import AsyncIterator
from contextlib import asynccontextmanager
from dataclasses import dataclass
from pathlib import Path

from fastapi import FastAPI, HTTPException, Request
from fastapi.responses import StreamingResponse
from pydantic import Field

from trafficgen.engine import Simulation
from trafficgen.ledger import Ledger, LedgerClient
from trafficgen.models import ApiModel, Label, ScenarioRun, State, TransferRecord
from trafficgen.store import Store


@dataclass(frozen=True)
class Settings:
    ledger_url: str = "http://localhost:8080"
    db_path: Path = Path("data/simulation.db")
    customers: int = 120
    rate: float = 3.0
    seed: int | None = None
    paused: bool = False
    time_scale: float = 1.0


class Control(ApiModel):
    running: bool | None = None
    rate: float | None = Field(default=None, ge=0.2, le=25)


class Launch(ApiModel):
    kind: Label


def create_app(settings: Settings, ledger: Ledger | None = None) -> FastAPI:
    @asynccontextmanager
    async def lifespan(app: FastAPI) -> AsyncIterator[None]:
        client = ledger or LedgerClient(settings.ledger_url)
        store = Store(settings.db_path)
        sim = Simulation(
            client,
            store,
            customers=settings.customers,
            rate=settings.rate,
            seed=settings.seed,
            time_scale=settings.time_scale,
        )
        await sim.start(running=not settings.paused)
        app.state.sim = sim
        try:
            yield
        finally:
            await sim.stop()
            await client.aclose()
            store.close()

    app = FastAPI(title="Clearhouse traffic generator", lifespan=lifespan)

    def sim_of(request: Request) -> Simulation:
        return request.app.state.sim

    @app.get("/api/state")
    async def state(request: Request) -> State:
        return sim_of(request).state()

    @app.post("/api/control")
    async def control(request: Request, body: Control) -> State:
        sim = sim_of(request)
        if body.running is not None:
            sim.running = body.running
        if body.rate is not None:
            sim.rate = body.rate
        return sim.state()

    @app.post("/api/scenarios", status_code=202)
    async def launch(request: Request, body: Launch) -> ScenarioRun:
        if body.kind is Label.NORMAL:
            raise HTTPException(400, "Normal traffic runs continuously; launch a fraud scenario instead")
        return sim_of(request).launch(body.kind)

    @app.get("/api/transfers/recent")
    async def recent(request: Request, limit: int = 200) -> list[TransferRecord]:
        return sim_of(request).store.recent_transfers(min(limit, 1_000))

    @app.get("/api/stream")
    async def stream(request: Request) -> StreamingResponse:
        """Server-sent events: one `data:` line per transfer, as it happens."""
        sim = sim_of(request)
        queue = sim.subscribe()

        async def events() -> AsyncIterator[str]:
            try:
                while not await request.is_disconnected():
                    try:
                        record = await asyncio.wait_for(queue.get(), timeout=15)
                    except TimeoutError:
                        yield ": keep-alive\n\n"
                        continue
                    yield f"data: {record.model_dump_json(by_alias=True)}\n\n"
            finally:
                sim.unsubscribe(queue)

        return StreamingResponse(events(), media_type="text/event-stream", headers={"Cache-Control": "no-cache"})

    return app
