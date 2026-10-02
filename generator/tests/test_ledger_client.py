import httpx
import pytest

from trafficgen.ledger import LedgerClient, LedgerUnavailableError

pytestmark = pytest.mark.anyio


def client_with(responses: list[httpx.Response], seen: list[httpx.Request]) -> LedgerClient:
    def handle(request: httpx.Request) -> httpx.Response:
        seen.append(request)
        return responses.pop(0)

    return LedgerClient("http://ledger", transport=httpx.MockTransport(handle))


async def test_retries_reuse_the_idempotency_key():
    seen: list[httpx.Request] = []
    client = client_with([httpx.Response(503), httpx.Response(409), httpx.Response(201, json={"id": "t-1"})], seen)

    outcome = await client.transfer("a", "b", 500)

    assert outcome.accepted and outcome.transfer_id == "t-1"
    assert len(seen) == 3
    assert len({r.headers["Idempotency-Key"] for r in seen}) == 1


async def test_insufficient_funds_is_an_outcome_not_an_error():
    client = client_with([httpx.Response(422, json={"title": "Insufficient funds"})], [])

    outcome = await client.transfer("a", "b", 500)

    assert not outcome.accepted
    assert outcome.rejection == "Insufficient funds"


async def test_bad_requests_are_not_retried():
    seen: list[httpx.Request] = []
    client = client_with([httpx.Response(400, json={"title": "Bad Request"})], seen)

    with pytest.raises(httpx.HTTPStatusError):
        await client.transfer("a", "a", 500)
    assert len(seen) == 1


async def test_unreachable_ledger_has_a_clear_message():
    def refuse(request: httpx.Request) -> httpx.Response:
        raise httpx.ConnectError("connection refused")

    client = LedgerClient("http://ledger", transport=httpx.MockTransport(refuse))

    with pytest.raises(LedgerUnavailableError, match="Is it running"):
        await client.open_account("Alice")
