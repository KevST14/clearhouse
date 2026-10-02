import time

from fastapi.testclient import TestClient

from trafficgen.api import Settings, create_app
from trafficgen.store import Store


def make_client(ledger, tmp_path) -> TestClient:
    settings = Settings(db_path=tmp_path / "sim.db", customers=30, seed=3, paused=True, time_scale=0)
    return TestClient(create_app(settings, ledger=ledger))


def wait_for(client: TestClient, predicate, timeout: float = 5.0) -> dict:
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        state = client.get("/api/state").json()
        if predicate(state):
            return state
        time.sleep(0.05)
    raise AssertionError(f"timed out; last state: {state}")


def test_starts_with_a_funded_population(ledger, tmp_path):
    with make_client(ledger, tmp_path) as client:
        state = client.get("/api/state").json()

    assert state["running"] is False
    assert state["customers"] == 30
    assert state["merchants"] > 10
    normal = next(t for t in state["totals"] if t["label"] == "normal")
    assert normal["accepted"] == 30, "one opening salary per customer"
    assert {s["kind"] for s in state["availableScenarios"]} == {"card_testing", "money_mule", "account_takeover"}


def test_launched_scenario_runs_and_is_labelled(ledger, tmp_path):
    with make_client(ledger, tmp_path) as client:
        response = client.post("/api/scenarios", json={"kind": "money_mule"})
        assert response.status_code == 202
        run_id = response.json()["id"]

        state = wait_for(client, lambda s: s["scenarios"][-1]["status"] != "running")
        recent = client.get("/api/transfers/recent").json()

    assert state["scenarios"][-1]["status"] == "finished"
    mule_transfers = [t for t in recent if t["scenarioId"] == run_id]
    assert mule_transfers and all(t["label"] == "money_mule" for t in mule_transfers)
    assert state["fraudsterAccounts"] >= 3


def test_control_pauses_and_changes_rate(ledger, tmp_path):
    with make_client(ledger, tmp_path) as client:
        state = client.post("/api/control", json={"running": True, "rate": 10}).json()
        assert state["running"] is True and state["rate"] == 10
        wait_for(client, lambda s: next(t for t in s["totals"] if t["label"] == "normal")["accepted"] > 40)

        assert client.post("/api/control", json={"rate": 500}).status_code == 422
        assert client.post("/api/scenarios", json={"kind": "normal"}).status_code == 400


def test_labels_survive_in_the_database(ledger, tmp_path):
    with make_client(ledger, tmp_path) as client:
        client.post("/api/scenarios", json={"kind": "card_testing"})
        wait_for(client, lambda s: s["scenarios"][-1]["status"] != "running")

    store = Store(tmp_path / "sim.db")
    labels = {t.label for t in store.recent_transfers(1_000)}
    store.close()
    assert labels == {"normal", "card_testing"}
