import unittest
from decimal import Decimal

from positions_parser import PositionsFormatError, decimal_value, parse_plans, plan_kind


def fund(**overrides):
    line = {
        "libelleFonds": "Amundi Label Actions Solidaires",
        "codeIsin": "fr0010405035",
        "nbParts": 12.3456,
        "vl": 100.0,
        "mtBrut": 1234.56,
        "mtPMV": 34.56,
    }
    line.update(overrides)
    return line


def plan(**overrides):
    dispositif = {
        "codeDispositif": "PEG001",
        "libelleDispositif": "Plan d'Épargne Groupe",
        "typeDispositif": "PEG",
        "nomEntreprise": "ACME SA",
        "mtBrut": 1234.56,
        "positionsSalarieFondsDto": [fund()],
    }
    dispositif.update(overrides)
    return dispositif


def payload(*plans):
    return {"listPositionsSalarieDispositifsDto": list(plans)}


class DecimalTest(unittest.TestCase):
    def test_json_numbers_avoid_float_drift(self):
        self.assertEqual(decimal_value(1234.56), Decimal("1234.56"))
        self.assertEqual(decimal_value(12), Decimal("12"))

    def test_french_formatted_strings_are_accepted(self):
        self.assertEqual(decimal_value("1 234,56"), Decimal("1234.56"))

    def test_missing_or_invalid_values_are_never_coerced_to_zero(self):
        self.assertIsNone(decimal_value(None))
        self.assertIsNone(decimal_value("not-a-number"))
        self.assertIsNone(decimal_value(True))


class PlanKindTest(unittest.TestCase):
    def test_known_kinds_are_normalised(self):
        self.assertEqual(plan_kind("percoi"), "PERCO")
        self.assertEqual(plan_kind("HES"), "PEE")

    def test_unknown_kind_is_passed_through_rather_than_dropped(self):
        self.assertEqual(plan_kind("PEX"), "PEX")
        self.assertIsNone(plan_kind(None))


class ParsePlansTest(unittest.TestCase):
    def test_maps_a_plan_and_its_fund_lines(self):
        [parsed] = parse_plans(payload(plan()))

        self.assertEqual(parsed["externalId"], "PEG001")
        self.assertEqual(parsed["name"], "Plan d'Épargne Groupe")
        self.assertEqual(parsed["planKind"], "PEG")
        self.assertEqual(parsed["employer"], "ACME SA")
        self.assertEqual(parsed["balanceEur"], Decimal("1234.56"))
        self.assertTrue(parsed["snapshotComplete"])

        [line] = parsed["positions"]
        self.assertEqual(line["isin"], "FR0010405035")
        self.assertEqual(line["quantity"], Decimal("12.3456"))
        self.assertEqual(line["unitValue"], Decimal("100.0"))
        self.assertEqual(line["valueEur"], Decimal("1234.56"))
        self.assertEqual(line["pnlEur"], Decimal("34.56"))

    def test_id_falls_back_to_iddispositif(self):
        [parsed] = parse_plans(payload(plan(codeDispositif=None, idDispositif="42")))
        self.assertEqual(parsed["externalId"], "42")

    def test_lines_that_do_not_reconcile_reject_the_whole_snapshot(self):
        with self.assertRaises(PositionsFormatError) as raised:
            parse_plans(payload(plan(mtBrut=2000.0)))
        self.assertEqual(raised.exception.code, "PORTFOLIO_INCOMPLETE")

    def test_rounding_within_tolerance_still_reconciles(self):
        parsed = parse_plans(payload(plan(mtBrut=1234.59)))
        self.assertEqual(parsed[0]["balanceEur"], Decimal("1234.59"))

    def test_a_funded_plan_with_no_lines_is_a_partial_read(self):
        with self.assertRaises(PositionsFormatError) as raised:
            parse_plans(payload(plan(positionsSalarieFondsDto=[])))
        self.assertEqual(raised.exception.code, "PORTFOLIO_INCOMPLETE")

    def test_an_emptied_plan_is_skipped_rather_than_failing_the_sync(self):
        parsed = parse_plans(payload(
            plan(codeDispositif="OLD", mtBrut=0, positionsSalarieFondsDto=[]),
            plan(),
        ))
        self.assertEqual([entry["externalId"] for entry in parsed], ["PEG001"])

    def test_a_missing_valuation_is_reported_as_a_format_change(self):
        with self.assertRaises(PositionsFormatError) as raised:
            parse_plans(payload(plan(positionsSalarieFondsDto=[fund(mtBrut=None)])))
        self.assertEqual(raised.exception.code, "UPSTREAM_FORMAT_CHANGED")

    def test_a_reshaped_response_is_rejected(self):
        for broken in ([], {}, {"listPositionsSalarieDispositifsDto": {}}):
            with self.assertRaises(PositionsFormatError) as raised:
                parse_plans(broken)
            self.assertEqual(raised.exception.code, "UPSTREAM_FORMAT_CHANGED")

    def test_an_account_holding_nothing_anywhere_is_rejected(self):
        with self.assertRaises(PositionsFormatError) as raised:
            parse_plans(payload())
        self.assertEqual(raised.exception.code, "PORTFOLIO_INCOMPLETE")


if __name__ == "__main__":
    unittest.main()
