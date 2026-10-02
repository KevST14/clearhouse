"""A small async client for the ledger's REST API."""

import asyncio
import uuid
from dataclasses import dataclass
from typing import Protocol

import httpx

EXTERNAL_GBP = "00000000-0000-0000-0000-000000000826"

MAX_ATTEMPTS = 4


class LedgerUnavailableError(RuntimeError):
    pass


@dataclass(frozen=True)
class TransferOutcome:
    """What the ledger said. A rejection (e.g. insufficient funds) is an answer, not an error."""

    transfer_id: str | None
    rejection: str | None = None

    @property
    def accepted(self) -> bool:
        return self.rejection is None


class Ledger(Protocol):
    async def open_account(self, owner_name: str) -> str: ...

    async def transfer(self, from_id: str, to_id: str, amount_minor: int) -> TransferOutcome: ...

    async def aclose(self) -> None: ...


class LedgerClient:
    def __init__(self, base_url: str, transport: httpx.AsyncBaseTransport | None = None) -> None:
        self._http = httpx.AsyncClient(base_url=base_url, timeout=10, transport=transport)

    async def open_account(self, owner_name: str) -> str:
        response = await self._send("POST", "/accounts", json={"ownerName": owner_name, "currency": "GBP"})
        response.raise_for_status()
        return response.json()["id"]

    async def transfer(self, from_id: str, to_id: str, amount_minor: int) -> TransferOutcome:
        """Sends a transfer, retrying network errors and busy responses with the same idempotency
        key, so a retry can never pay twice."""
        key = str(uuid.uuid4())
        body = {"fromAccountId": from_id, "toAccountId": to_id, "amountMinor": amount_minor, "currency": "GBP"}
        for attempt in range(1, MAX_ATTEMPTS + 1):
            try:
                response = await self._http.post("/transfers", json=body, headers={"Idempotency-Key": key})
            except httpx.TransportError:
                if attempt == MAX_ATTEMPTS:
                    raise
            else:
                if response.status_code in (200, 201):
                    return TransferOutcome(transfer_id=response.json()["id"])
                if response.status_code == 422:
                    return TransferOutcome(transfer_id=None, rejection=response.json().get("title", "Rejected"))
                if response.status_code != 409 and response.status_code < 500:
                    response.raise_for_status()
                if attempt == MAX_ATTEMPTS:
                    response.raise_for_status()
            await asyncio.sleep(0.1 * 2**attempt)
        raise AssertionError("unreachable")

    async def _send(self, method: str, path: str, **kwargs) -> httpx.Response:
        try:
            return await self._http.request(method, path, **kwargs)
        except httpx.ConnectError as e:
            raise LedgerUnavailableError(f"Can't reach the ledger at {self._http.base_url}. Is it running?") from e

    async def aclose(self) -> None:
        await self._http.aclose()
