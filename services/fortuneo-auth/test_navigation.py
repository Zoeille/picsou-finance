"""Browser navigation / DOM parsing tests.

STATUS: placeholder. Fortuneo's real login and portfolio page structure has
not been reverse-engineered yet (see Phase 0 discovery in the implementation
plan), so there is no navigation logic to test here. Once main.py's
initiate/complete/accounts handlers are implemented against the real site,
add tests here mirroring services/bourse-direct-auth/test_navigation.py:
selector fallback behaviour, OTP-visible detection, and session-state
polling, using fixture HTML/JSON captured from the real account.
"""

import unittest


class NavigationPlaceholderTest(unittest.TestCase):
    def test_placeholder_until_fortuneo_navigation_is_implemented(self):
        self.assertTrue(True)


if __name__ == "__main__":
    unittest.main()
