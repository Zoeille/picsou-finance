import time
import unittest

from fastapi import HTTPException
from fastapi.testclient import TestClient

from positions_parser import parse_plans
from main import (
    PlanPayload,
    PENDING_TTL_SECONDS,
    TokenCollector,
    _cleanup_expired,
    _close_all_pending,
    _close_resources,
    _decode_session,
    _encode_session,
    _pending,
    _pending_lock,
    _take_pending,
    app,
)


class FakeResource:
    def __init__(self, method: str):
        self.method = method
        self.calls = 0

    async def close(self):
        if self.method != "close":
            raise AssertionError("unexpected close")
        self.calls += 1

    async def stop(self):
        if self.method != "stop":
            raise AssertionError("unexpected stop")
        self.calls += 1


class FailingResource:
    async def close(self):
        raise RuntimeError("close failed")


class FakeRequest:
    def __init__(self, url: str, headers: dict):
        self.url = url
        self.headers = headers


class SessionEncodingTest(unittest.TestCase):
    def test_round_trips_the_storage_state_and_bearer(self):
        raw = _encode_session({"cookies": []}, "noeprd token")
        storage_state, token = _decode_session(raw)
        self.assertEqual(storage_state, {"cookies": []})
        self.assertEqual(token, "noeprd token")

    def test_a_session_without_a_bearer_is_unusable(self):
        for broken in ("not-json", "[]", '{"storageState": {}}', '{"token": "t"}'):
            with self.assertRaises(HTTPException) as raised:
                _decode_session(broken)
            self.assertEqual(raised.exception.detail, "INVALID_DATA")


class TokenCollectorTest(unittest.TestCase):
    def test_captures_the_bearer_off_an_authenticated_call(self):
        collector = TokenCollector()
        collector._on_request(FakeRequest(
            "https://epargnant.amundi-ee.com/api/individu/dispositifsMulti",
            {"x-noee-authorization": "noeprd token"},
        ))
        self.assertEqual(collector.token, "noeprd token")

    def test_ignores_unauthenticated_and_headerless_traffic(self):
        collector = TokenCollector()
        collector._on_request(FakeRequest(
            "https://epargnant.amundi-ee.com/public/config", {"x-noee-authorization": "x"}
        ))
        collector._on_request(FakeRequest(
            "https://epargnant.amundi-ee.com/api/individu/operations", {}
        ))
        self.assertIsNone(collector.token)

    def test_keeps_the_first_bearer_it_saw(self):
        collector = TokenCollector()
        for value in ("first", "second"):
            collector._on_request(FakeRequest(
                "https://epargnant.amundi-ee.com/api/individu/dispositifsMulti",
                {"x-noee-authorization": value},
            ))
        self.assertEqual(collector.token, "first")


class ResponseModelTest(unittest.TestCase):
    """The parser feeds PlanPayload directly, so its output must validate.

    Shaped like a real dispositifsMulti response: most fund lines are catalogue
    entries the employee does not hold, carrying nulls throughout.
    """

    def test_parser_output_satisfies_the_response_model(self):
        held = {
            "libelleFonds": "Amundi Label Actions Solidaires ESR",
            "codeIsin": "FR0010405035", "nbParts": 12.3456, "vl": 100.0,
            "mtBrut": 1234.56, "mtPMV": 34.56,
        }
        catalogue = {
            "libelleFonds": "Fonds proposé non détenu", "codeIsin": None,
            "nbParts": None, "vl": None, "mtBrut": None, "mtPMV": None,
        }
        payload = {"count": 2, "listPositionsSalarieDispositifsDto": [
            {"codeDispositif": "PEG001", "libelleDispositif": "Plan d'Épargne Groupe",
             "typeDispositif": "PEG", "nomEntreprise": "ACME SA", "mtBrut": 1234.56,
             "positionsSalarieFondsDto": [catalogue, held, catalogue]},
            {"codeDispositif": "OLD", "libelleDispositif": "PERCO ancien",
             "typeDispositif": "PERCO", "nomEntreprise": "OLDCO", "mtBrut": 0,
             "positionsSalarieFondsDto": [catalogue]},
        ]}

        plans = [PlanPayload.model_validate(plan) for plan in parse_plans(payload)]

        self.assertEqual(len(plans), 1)
        self.assertEqual(plans[0].externalId, "PEG001")
        self.assertEqual(plans[0].planKind, "PEG")
        self.assertTrue(plans[0].snapshotComplete)
        self.assertEqual(len(plans[0].positions), 1)
        self.assertEqual(plans[0].positions[0].isin, "FR0010405035")


class PendingAuthenticationLifecycleTest(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self):
        await _close_all_pending()

    async def asyncTearDown(self):
        await _close_all_pending()

    async def test_expired_attempts_are_swept_and_their_browsers_closed(self):
        context, browser, playwright = FakeResource("close"), FakeResource("close"), FakeResource("stop")
        async with _pending_lock:
            _pending["stale"] = {
                "context": context, "browser": browser, "playwright": playwright,
                "created_at": time.time() - PENDING_TTL_SECONDS - 1,
            }

        await _cleanup_expired()

        self.assertNotIn("stale", _pending)
        self.assertEqual((context.calls, browser.calls, playwright.calls), (1, 1, 1))

    async def test_an_attempt_can_only_be_consumed_once(self):
        async with _pending_lock:
            _pending["once"] = {"created_at": time.time()}

        self.assertIsNotNone(await _take_pending("once"))
        self.assertIsNone(await _take_pending("once"))

    async def test_a_browser_that_refuses_to_close_does_not_break_cleanup(self):
        await _close_resources(FailingResource(), None, None)


class ContractTest(unittest.TestCase):
    def setUp(self):
        self.client = TestClient(app)

    def test_health_is_unauthenticated(self):
        self.assertEqual(self.client.get("/health").json(), {"status": "ok"})

    def test_an_unknown_attempt_reports_an_expired_authentication(self):
        response = self.client.post("/complete", json={"processId": "gone"})
        self.assertEqual(response.status_code, 410)
        self.assertEqual(response.json()["detail"], "AUTH_ATTEMPT_EXPIRED")

    def test_a_malformed_code_is_reported_as_an_invalid_otp(self):
        response = self.client.post("/complete", json={"processId": "p", "code": "12"})
        self.assertEqual(response.status_code, 400)
        self.assertEqual(response.json()["detail"], "INVALID_OTP")

    def test_unexpected_fields_are_refused(self):
        response = self.client.post(
            "/initiate", json={"login": "a", "password": "b", "website": "tc"}
        )
        self.assertEqual(response.status_code, 400)
        self.assertEqual(response.json()["detail"], "INVALID_DATA")


if __name__ == "__main__":
    unittest.main()
