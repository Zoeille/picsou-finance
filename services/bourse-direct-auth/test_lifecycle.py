import asyncio
import time
import unittest

from fastapi.testclient import TestClient

from main import (
    PENDING_TTL_SECONDS,
    _cleanup_expired,
    _close_all_pending,
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


class PendingAuthenticationLifecycleTest(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self):
        await _close_all_pending()

    async def asyncTearDown(self):
        await _close_all_pending()

    async def test_expired_authentication_closes_every_browser_resource(self):
        context = FakeResource("close")
        browser = FakeResource("close")
        playwright = FakeResource("stop")
        async with _pending_lock:
            _pending["expired"] = {
                "context": context,
                "browser": browser,
                "playwright": playwright,
                "created_at": time.time() - PENDING_TTL_SECONDS - 1,
            }

        await _cleanup_expired()

        self.assertNotIn("expired", _pending)
        self.assertEqual(context.calls, 1)
        self.assertEqual(browser.calls, 1)
        self.assertEqual(playwright.calls, 1)

    async def test_pending_authentication_can_only_be_claimed_once(self):
        state = {"created_at": time.time()}
        async with _pending_lock:
            _pending["process"] = state

        first, second = await asyncio.gather(
            _take_pending("process"),
            _take_pending("process"),
        )

        self.assertEqual(sum(value is state for value in (first, second)), 1)
        self.assertEqual(sum(value is None for value in (first, second)), 1)


class RequestContractTest(unittest.TestCase):
    def test_accounts_rejects_a_non_object_storage_state(self):
        with TestClient(app) as client:
            response = client.post("/accounts", json={"sessionState": "[]"})

        self.assertEqual(response.status_code, 400)
        self.assertEqual(response.json()["detail"], "INVALID_DATA")


if __name__ == "__main__":
    unittest.main()
