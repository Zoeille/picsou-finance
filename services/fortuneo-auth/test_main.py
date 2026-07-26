import unittest

from fortuneo_parser import (
    PortfolioFormatError,
    account_balance,
    decimal_value,
    extract_accounts_from_equipment,
    fold_cash_pockets_into_securities_accounts,
    optional_decimal_value,
    parse_bourse_synthese_table,
    parse_equipment_multipart,
    parse_legacy_account_ids,
    parse_portfolio_positions,
    parse_portfolio_summary,
)


class DecimalParsingTest(unittest.TestCase):
    def test_decimal_parses_french_values(self):
        self.assertEqual(str(decimal_value("1 234,56 €")), "1234.56")
        self.assertEqual(str(decimal_value("1.234,56 €")), "1234.56")
        self.assertEqual(str(decimal_value("-12,30 %")), "-12.30")

    def test_missing_or_invalid_decimal_is_never_coerced_to_zero(self):
        with self.assertRaises(PortfolioFormatError):
            decimal_value(None, "balance")
        with self.assertRaises(PortfolioFormatError):
            decimal_value("not-a-number", "balance")
        with self.assertRaises(PortfolioFormatError):
            decimal_value("1e3", "balance")
        with self.assertRaises(PortfolioFormatError):
            decimal_value("12oops34", "balance")

    def test_optional_decimal_returns_none_for_blank_input(self):
        self.assertIsNone(optional_decimal_value(None))
        self.assertIsNone(optional_decimal_value("   "))
        self.assertEqual(str(optional_decimal_value("42,00")), "42.00")


class AccountBalanceTest(unittest.TestCase):
    def test_extracts_the_balance_named_amount(self):
        account = {
            "id": "acc-1",
            "productType": "current-account",
            "balances": [{"name": "balance", "amount": {"value": 4.7, "currency": "EUR"}}],
        }
        self.assertEqual(account_balance(account), decimal_value("4.7"))

    def test_returns_none_when_no_balances_array(self):
        # Securities accounts (account-api/v2/accounts) have no `balances` key.
        account = {"id": "acc-2", "productType": "share-savings-plan"}
        self.assertIsNone(account_balance(account))


# Synthetic fixture mirroring the real `famille_bourse` HTML fragment
# structure observed in a live capture (fake names/ids/amounts).
SYNTHESE_BOURSE_FIXTURE = '''
<table cellspacing="0" cellpadding="0" class="synthese_table" id="famille_bourse">
<tbody>
<tr class="l1 synthese_compte_ligne_num_compte">
<td width="5%"><div class="synthese_spacer"></div></td>
<td width="81%">
<a href="/fr/prive/mes-comptes/compte-titres-pea/situation/portefeuille-temps-reel.jsp?ca=CTOWEBID"
   class="synthese_id_compte" title="Valorisation du compte au&nbsp;24/07/2026">
	<span class="synthese_numero_compte">
		N&deg;000000000001
	</span>
	<span class="synthese_compte_tiret">-&nbsp;</span>Compte-titres TEST USER
			<span class="synthese_solde_compte ">
				+ 100,00&nbsp;EUR
			</span>
</a>
</td>
<td width="14%">shortcuts</td>
</tr>
<tr class="l1 synthese_compte_ligne_autre">
<td><div class="synthese_spacer2"></div></td>
<td>
<a href="/fr/prive/mes-comptes/compte-especes/consulter-situation/consulter-solde.jsp?ca=CASHWEBID"
   class="synthese_compte_associe">
	<span class="synthese_compte_tiret">-&nbsp;</span>Compte esp&egrave;ces TEST USER
	<span class="synthese_solde_compte_espece_associe ">
		+ 100,00&nbsp;EUR
	</span>
</a>
</td>
<td></td>
</tr>
<tr class="l2 synthese_compte_ligne_num_compte">
<td width="5%"><div class="synthese_spacer"></div></td>
<td width="81%">
<a href="/fr/prive/mes-comptes/pea/situation/portefeuille-temps-reel.jsp?ca=PEAWEBID"
   class="synthese_id_compte" title="Valorisation du compte au&nbsp;24/07/2026">
	<span class="synthese_numero_compte">
		N&deg;000000000002
	</span>
	<span class="synthese_compte_tiret">-&nbsp;</span>PEA TEST USER
			<span class="synthese_solde_compte ">
				+ 1 000,00&nbsp;EUR
			</span>
</a>
</td>
<td width="14%">shortcuts</td>
</tr>
</tbody>
</table>
'''


class BourseSyntheseTableTest(unittest.TestCase):
    def test_parses_each_securities_account_and_its_linked_cash_pocket(self):
        accounts = parse_bourse_synthese_table(SYNTHESE_BOURSE_FIXTURE)

        self.assertEqual(len(accounts), 2)
        cto, pea = accounts
        self.assertEqual(cto["webId"], "CTOWEBID")
        self.assertEqual(cto["type"], "COMPTE_TITRES")
        self.assertEqual(cto["label"], "Compte-titres TEST USER")
        self.assertEqual(cto["totalEur"], decimal_value("100.00"))
        self.assertEqual(cto["cashWebId"], "CASHWEBID")

        self.assertEqual(pea["webId"], "PEAWEBID")
        self.assertEqual(pea["type"], "PEA")
        self.assertEqual(pea["label"], "PEA TEST USER")
        self.assertEqual(pea["totalEur"], decimal_value("1000.00"))
        self.assertIsNone(pea["cashWebId"])

    def test_unrecognized_url_segment_rejects_the_whole_snapshot(self):
        bogus = SYNTHESE_BOURSE_FIXTURE.replace("/pea/situation/", "/unknown-type/situation/")
        with self.assertRaises(PortfolioFormatError):
            parse_bourse_synthese_table(bogus)


# Synthetic fixture mirroring the real `.../situation/` position-table
# structure observed in a live capture (fake ISINs/names/amounts). Includes
# the blank `<td class="numb"></td>` cells the real page renders for
# SRD-related columns this account type doesn't use, and one alternating
# "l2" row -- both are exactly what earlier, naive regexes matched
# incorrectly during development.
PORTFOLIO_POSITIONS_FIXTURE = '''
<tr name="FTN000023XX0000000001princip" class="l1" >
    <td class="txt bourse_table_actions bourse_table_portalonly"></td>
    <td class="txt">
      <a href="/fr/prive/bourse/fiche-valeur.jsp?cdReferentiel=FTN000023XX0000000001&activeTab=resume"><b class="libelle_valeur_tableau">TEST STOCK ONE (TSO)</b></a>
    </td>
    <td class="numb">
        <b>50,00
            <span title="26/07/2026 - 17:35:28"><b>V</b></span>
        </b>
    </td>
    <td class="up evol numb"><b>+1,00%</b></td>
    <td class="numb"><b>10</b></td>
    <td class="numb"></td>
    <td class="numb"></td>
    <td class="numb">40,000</td>
    <td class="numb" id="idValorisation_0_tab__categorie_0" >500,00</td>
    <td class="numb">+100,00&nbsp;<span class="augmente ">+25,00%</span></td>
    <td class="numb" id="idPoid_0_tab__categorie_0">50,0%</td>
</tr>
<tr name="FTN000023XX0000000002princip" class="l2" >
    <td class="txt bourse_table_actions bourse_table_portalonly"></td>
    <td class="txt">
      <a href="/fr/prive/bourse/fiche-valeur.jsp?cdReferentiel=FTN000023XX0000000002&activeTab=resume"><b class="libelle_valeur_tableau">TEST ETF TWO (TET)</b></a>
    </td>
    <td class="numb">
        <b>10,000
            <span title="26/07/2026 - 17:35:28"><b>V</b></span>
        </b>
    </td>
    <td class="down evol numb"><b>-0,10%</b></td>
    <td class="numb"><b>1 000</b></td>
    <td class="numb"></td>
    <td class="numb">10,000</td>
    <td class="numb" id="idValorisation_1_tab__categorie_0" >10 000,00</td>
    <td class="numb" id="idPoid_1_tab__categorie_0">50,0%</td>
</tr>
'''


class PortfolioPositionsTest(unittest.TestCase):
    def test_parses_price_quantity_pru_valuation_and_pnl(self):
        positions = parse_portfolio_positions(PORTFOLIO_POSITIONS_FIXTURE)

        self.assertEqual(len(positions), 2)
        stock = positions[0]
        self.assertEqual(stock["isin"], "XX0000000001")
        self.assertEqual(stock["symbol"], "XX0000000001")
        self.assertEqual(stock["label"], "TEST STOCK ONE")
        self.assertEqual(stock["quantity"], decimal_value("10"))
        self.assertEqual(stock["currentPrice"], decimal_value("50.00"))
        self.assertEqual(stock["buyingPriceEur"], decimal_value("40.000"))
        self.assertEqual(stock["currentValueEur"], decimal_value("500.00"))
        self.assertEqual(stock["pnlEur"], decimal_value("100.00"))
        self.assertEqual(stock["quoteCurrency"], "EUR")

    def test_thousand_separated_quantity_and_missing_pnl_span(self):
        # The second fixture row has no augmente/baisse span at all (a
        # position exactly at cost legitimately renders with no coloured
        # P&L span, confirmed live) and a space-separated quantity ("1 000").
        positions = parse_portfolio_positions(PORTFOLIO_POSITIONS_FIXTURE)

        etf = positions[1]
        self.assertEqual(etf["isin"], "XX0000000002")
        self.assertEqual(etf["label"], "TEST ETF TWO")
        self.assertEqual(etf["quantity"], decimal_value("1000"))
        self.assertEqual(etf["currentValueEur"], decimal_value("10000.00"))
        self.assertIsNone(etf["pnlEur"])

    def test_blank_srd_cells_are_never_mistaken_for_the_real_value(self):
        # Both fixture rows have blank `<td class="numb"></td>` cells before
        # the real PRU cell -- a naive `[\d.,\s]+` pattern (no required
        # leading digit) would match that blank whitespace first and never
        # reach the actual number.
        positions = parse_portfolio_positions(PORTFOLIO_POSITIONS_FIXTURE)

        self.assertEqual(positions[0]["buyingPriceEur"], decimal_value("40.000"))
        self.assertEqual(positions[1]["buyingPriceEur"], decimal_value("10.000"))

    def test_missing_valuation_rejects_the_row(self):
        broken = PORTFOLIO_POSITIONS_FIXTURE.replace(
            '<td class="numb" id="idValorisation_0_tab__categorie_0" >500,00</td>',
            '<td class="numb" id="idValorisation_0_tab__categorie_0" ></td>',
        )
        with self.assertRaises(PortfolioFormatError):
            parse_portfolio_positions(broken)


# Synthetic fixture mirroring the real `Equipment` GraphQL multipart/mixed
# response structure observed in a live capture (fake ids/amounts/names),
# boundary="-" per the real `Content-Type: multipart/mixed; boundary="-"`.
EQUIPMENT_MULTIPART_FIXTURE = (
    '---\r\n'
    'content-type: application/json; charset=utf-8\r\n\r\n'
    '{"data":{"equipment":{"financialPortfolio":{'
    '"lifeInsurance":{"accounts":[]},'
    '"shareSavingsPlan":{"accounts":[{"id":"PEAWEBID","label":"PEA TEST USER",'
    '"type":{"label":"PEA","value":"share-savings-plan"}}]},'
    '"ordinarySecurities":{"accounts":[{"id":"CTOWEBID","label":"CTO TEST USER",'
    '"type":{"label":"Compte-titres","value":"ordinary-securities-account"}}]},'
    '"cash":{"accounts":[{"id":"CASHWEBID","label":"Compte especes TEST USER",'
    '"type":{"label":"Compte especes","value":"cash-account"}}]},'
    '"mortgages":{"accounts":[]},"external":{"accounts":[]}},'
    '"banking":{'
    '"current":{"accounts":[{"id":"CHECKINGWEBID","label":"CC TEST USER",'
    '"type":{"label":"Compte courant","value":"current-account"}}]},'
    '"savings":{"accounts":[]},"external":{"accounts":[]}}}},"hasNext":true}\r\n'
    '---\r\n'
    'content-type: application/json; charset=utf-8\r\n\r\n'
    '{"hasNext":true,"incremental":[{"data":{"balance":{"amount":"1000","currency":"EUR"}},'
    '"path":["equipment","financialPortfolio","shareSavingsPlan","accounts",0]}]}\r\n'
    '---\r\n'
    'content-type: application/json; charset=utf-8\r\n\r\n'
    '{"hasNext":true,"incremental":[{"data":{"balance":{"amount":"100","currency":"EUR"}},'
    '"path":["equipment","financialPortfolio","ordinarySecurities","accounts",0]}]}\r\n'
    '---\r\n'
    'content-type: application/json; charset=utf-8\r\n\r\n'
    '{"hasNext":true,"incremental":[{"data":{"balance":{"amount":"100","currency":"EUR"}},'
    '"path":["equipment","financialPortfolio","cash","accounts",0]}]}\r\n'
    '---\r\n'
    'content-type: application/json; charset=utf-8\r\n\r\n'
    '{"hasNext":false,"incremental":[{"data":{"balance":{"amount":"4.7","currency":"EUR"}},'
    '"path":["equipment","banking","current","accounts",0]}]}\r\n'
    '-----\r\n'
)


class EquipmentGraphqlTest(unittest.TestCase):
    def test_parse_equipment_multipart_merges_every_deferred_fragment(self):
        equipment = parse_equipment_multipart(EQUIPMENT_MULTIPART_FIXTURE)

        pea = equipment["financialPortfolio"]["shareSavingsPlan"]["accounts"][0]
        self.assertEqual(pea["balance"]["amount"], "1000")
        cto = equipment["financialPortfolio"]["ordinarySecurities"]["accounts"][0]
        self.assertEqual(cto["balance"]["amount"], "100")
        checking = equipment["banking"]["current"]["accounts"][0]
        self.assertEqual(checking["balance"]["amount"], "4.7")

    def test_malformed_multipart_never_yields_an_empty_snapshot(self):
        with self.assertRaises(PortfolioFormatError):
            parse_equipment_multipart("not a multipart response at all")

    def test_extract_accounts_covers_in_scope_categories_and_skips_the_rest(self):
        equipment = parse_equipment_multipart(EQUIPMENT_MULTIPART_FIXTURE)

        accounts = extract_accounts_from_equipment(equipment)

        by_web_id = {a["webId"]: a for a in accounts}
        self.assertEqual(set(by_web_id), {"PEAWEBID", "CTOWEBID", "CASHWEBID", "CHECKINGWEBID"})
        self.assertEqual(by_web_id["PEAWEBID"]["type"], "PEA")
        self.assertEqual(by_web_id["CTOWEBID"]["type"], "COMPTE_TITRES")
        self.assertEqual(by_web_id["CHECKINGWEBID"]["type"], "CHECKING")
        self.assertIsNone(by_web_id["CASHWEBID"]["type"])
        self.assertEqual(by_web_id["PEAWEBID"]["balanceEur"], decimal_value("1000"))

    def test_fold_cash_pockets_pairs_the_single_cto_with_the_single_pocket(self):
        equipment = parse_equipment_multipart(EQUIPMENT_MULTIPART_FIXTURE)
        accounts = extract_accounts_from_equipment(equipment)

        folded = fold_cash_pockets_into_securities_accounts(accounts)

        self.assertEqual({a["webId"] for a in folded}, {"PEAWEBID", "CTOWEBID", "CHECKINGWEBID"})
        cto = next(a for a in folded if a["webId"] == "CTOWEBID")
        self.assertEqual(cto["cashBalance"], decimal_value("100"))
        checking = next(a for a in folded if a["webId"] == "CHECKINGWEBID")
        self.assertEqual(checking["cashBalance"], checking["balanceEur"])

    def test_fold_cash_pockets_leaves_pea_cash_balance_unknown(self):
        # A PEA can hold real stock positions and has no separate cash-pocket
        # account (unlike CTO) -- guessing cashBalance = balanceEur would
        # silently misreport a PEA holding positions as 100% cash (confirmed
        # live before this was fixed). It must stay None so the caller
        # rejects the snapshot instead of reporting wrong data.
        equipment = parse_equipment_multipart(EQUIPMENT_MULTIPART_FIXTURE)
        accounts = extract_accounts_from_equipment(equipment)

        folded = fold_cash_pockets_into_securities_accounts(accounts)

        pea = next(a for a in folded if a["webId"] == "PEAWEBID")
        self.assertIsNone(pea["cashBalance"])

    def test_fold_cash_pockets_rejects_an_unpaired_ambiguous_case(self):
        accounts = [
            {"webId": "CTO1", "type": "COMPTE_TITRES", "label": "CTO 1", "balanceEur": decimal_value("10")},
            {"webId": "CTO2", "type": "COMPTE_TITRES", "label": "CTO 2", "balanceEur": decimal_value("20")},
            {"webId": "POCKET1", "type": None, "label": "Pocket", "balanceEur": decimal_value("10")},
        ]
        with self.assertRaises(PortfolioFormatError):
            fold_cash_pockets_into_securities_accounts(accounts)

    def test_unrecognized_url_segment_rejects_the_whole_snapshot(self):
        bogus = SYNTHESE_BOURSE_FIXTURE.replace("/pea/situation/", "/unknown-type/situation/")
        with self.assertRaises(PortfolioFormatError):
            parse_bourse_synthese_table(bogus)


class AccountPayloadContractTest(unittest.TestCase):
    def test_account_payload_accepts_all_four_supported_types(self):
        from main import AccountPayload

        for account_type in ("PEA", "COMPTE_TITRES", "CHECKING", "SAVINGS"):
            payload = AccountPayload.model_validate({
                "externalId": "acc-1",
                "name": "Account",
                "type": account_type,
                "balanceEur": "100.00",
                "cashBalance": "100.00",
                "positions": [],
                "transactions": [],
                "snapshotComplete": True,
            })
            self.assertEqual(payload.type, account_type)

    def test_account_payload_defaults_transactions_to_empty_list(self):
        from main import AccountPayload

        payload = AccountPayload.model_validate({
            "externalId": "acc-1",
            "name": "Account",
            "type": "PEA",
            "balanceEur": "100.00",
            "cashBalance": "0.00",
            "positions": [],
            "snapshotComplete": True,
        })
        self.assertEqual(payload.transactions, [])

    def test_transaction_payload_requires_iso_date(self):
        from pydantic import ValidationError

        from main import TransactionPayload

        with self.assertRaises(ValidationError):
            TransactionPayload.model_validate({
                "date": "26/07/2026",
                "label": "Virement",
                "amount": "10.00",
            })

        valid = TransactionPayload.model_validate({
            "date": "2026-07-26",
            "label": "Virement",
            "amount": "10.00",
        })
        self.assertEqual(valid.label, "Virement")


class SessionStateEnvelopeTest(unittest.TestCase):
    def test_parses_storage_state_api_key_and_session_storage_out_of_the_envelope(self):
        import json

        from main import _parse_session_state

        storage_state, api_key, session_storage = _parse_session_state(json.dumps({
            "storageState": {"cookies": [], "origins": []},
            "apiKey": "test-key",
            "sessionStorage": {"token": "abc"},
        }))

        self.assertEqual(storage_state, {"cookies": [], "origins": []})
        self.assertEqual(api_key, "test-key")
        self.assertEqual(session_storage, {"token": "abc"})

    def test_session_storage_defaults_to_empty_when_absent(self):
        import json

        from main import _parse_session_state

        _, _, session_storage = _parse_session_state(json.dumps({
            "storageState": {"cookies": [], "origins": []},
            "apiKey": "test-key",
        }))

        self.assertEqual(session_storage, {})

    def test_rejects_an_envelope_missing_the_api_key(self):
        import json

        from fastapi import HTTPException

        from main import _parse_session_state

        with self.assertRaises(HTTPException) as raised:
            _parse_session_state(json.dumps({"storageState": {}}))
        self.assertEqual(raised.exception.status_code, 400)
        self.assertEqual(raised.exception.detail, "INVALID_DATA")

    def test_rejects_a_non_object_payload(self):
        from fastapi import HTTPException

        from main import _parse_session_state

        with self.assertRaises(HTTPException):
            _parse_session_state("[]")


class AccountNameTest(unittest.TestCase):
    def test_prefers_type_label_and_account_number(self):
        from main import _account_name

        name = _account_name({
            "typeLabel": "PEA",
            "accountNumber": "XXXXXX000042",
            "label": "M TEST USER",
        })
        self.assertEqual(name, "PEA XXXXXX000042")

    def test_falls_back_to_holder_label_when_type_info_is_missing(self):
        from main import _account_name

        name = _account_name({"typeLabel": None, "accountNumber": None, "label": "M TEST USER"})
        self.assertEqual(name, "M TEST USER")


if __name__ == "__main__":
    unittest.main()


class LegacyAccountIdsTest(unittest.TestCase):
    """The legacy site keys accounts by its own id, which the modern
    Equipment API never exposes -- passing an Equipment id as `ca` renders
    the portfolio page with no holdings instead of failing, so getting this
    mapping right is what makes positions appear at all.
    """

    HOME = (
        '<div class="pea compte">'
        '<a href="/fr/prive/mes-comptes/pea/situation/?ca=' + "a" * 32 + '" rel="x">PEA</a></div>'
        '<div class="ppe compte">'
        '<a href="/fr/prive/mes-comptes/ppe/situation/?ca=' + "b" * 32 + '">PEA PME</a></div>'
        '<div class="cto compte">'
        '<a href="/fr/prive/mes-comptes/compte-titres-pea/situation/?ca=' + "c" * 32 + '">CTO</a></div>'
        '<a href="/fr/prive/mes-comptes/compte-courant/consulter-situation/?ca=' + "d" * 32 + '">CC</a>'
    )

    def test_maps_every_segment_to_its_legacy_id(self):
        ids = parse_legacy_account_ids(self.HOME)
        self.assertEqual(ids["pea"], "a" * 32)
        self.assertEqual(ids["ppe"], "b" * 32)
        self.assertEqual(ids["compte-titres-pea"], "c" * 32)
        # `consulter-situation` (cash accounts) is matched too.
        self.assertEqual(ids["compte-courant"], "d" * 32)

    def test_first_link_per_segment_wins(self):
        html = self.HOME + '<a href="/fr/prive/mes-comptes/pea/situation/?ca=' + "e" * 32 + '">dup</a>'
        self.assertEqual(parse_legacy_account_ids(html)["pea"], "a" * 32)

    def test_an_equipment_style_id_is_never_mistaken_for_a_legacy_one(self):
        # Equipment ids are 22-char base64-ish, not 32-hex; a link carrying
        # one must not be picked up as a legacy id.
        html = '<a href="/fr/prive/mes-comptes/pea/situation/?ca=Ab1Cd2Ef3Gh4Ij5Kl6Mn7O">x</a>'
        self.assertEqual(parse_legacy_account_ids(html), {})

    def test_no_links_yields_an_empty_mapping(self):
        self.assertEqual(parse_legacy_account_ids("<html><body>nothing</body></html>"), {})


class PortfolioSummaryTest(unittest.TestCase):
    """The page's own valuation summary is what makes an empty account
    distinguishable from a table that failed to render -- both parse to
    zero rows, but only one has a zero securities total.
    """

    def _page(self, securities, cash, total):
        return (
            "<table>"
            f"<tr><td class='txt'>Évaluation Titres</td><td class='numb'>{securities}</td></tr>"
            "<tr><td class='txt'>dont +/- values latentes au comptant</td>"
            "<td class='numb'>0,00</td></tr>"
            f"<tr><td class='txt'>Solde espèces EUR</td><td class='numb'>{cash}</td></tr>"
            f"<tr><td class='txt'><strong>Valorisation totale</strong></td>"
            f"<td class='numb gras'>{total}</td></tr>"
            "</table>"
        )

    def test_reads_securities_cash_and_total(self):
        summary = parse_portfolio_summary(self._page("12 500,00", "1 234,56", "13 734,56"))
        self.assertEqual(str(summary["securitiesEur"]), "12500.00")
        self.assertEqual(str(summary["cashEur"]), "1234.56")
        self.assertEqual(str(summary["totalEur"]), "13734.56")

    def test_an_empty_account_reports_zero_securities_not_a_missing_summary(self):
        summary = parse_portfolio_summary(self._page("0,00", "250,00", "250,00"))
        self.assertEqual(str(summary["securitiesEur"]), "0.00")
        self.assertEqual(str(summary["cashEur"]), "250.00")

    def test_a_page_without_the_summary_yields_nones(self):
        # A login form / interstitial / SPA shell must not look like an
        # account worth zero -- the caller fails closed on these.
        summary = parse_portfolio_summary("<html><body>nothing here</body></html>")
        self.assertIsNone(summary["securitiesEur"])
        self.assertIsNone(summary["cashEur"])

    def test_labels_match_without_accents_and_ignore_trailing_text(self):
        html = ("<table><tr><td>EVALUATION TITRES au 26/07</td><td>1 000,00</td></tr>"
                "<tr><td>Solde especes EUR</td><td>2,50</td></tr></table>")
        summary = parse_portfolio_summary(html)
        self.assertEqual(str(summary["securitiesEur"]), "1000.00")
        self.assertEqual(str(summary["cashEur"]), "2.50")

    def test_the_wait_selector_targets_the_block_the_summary_is_parsed_from(self):
        # The fetch waits on PORTFOLIO_SUMMARY_SELECTOR to decide the page has
        # rendered, then parses the summary out of it. If the two ever drift
        # apart the fetch would wait on one element and read another -- so the
        # selector must be an id that really wraps the summary rows.
        from main import PORTFOLIO_SUMMARY_SELECTOR

        self.assertTrue(PORTFOLIO_SUMMARY_SELECTOR.startswith("#"))
        block_id = PORTFOLIO_SUMMARY_SELECTOR[1:]
        page = (
            f'<div class="block" id="{block_id}">'
            + self._page("12 500,00", "1 234,56", "13 734,56")
            + "</div>"
        )
        self.assertIn(f'id="{block_id}"', page)
        self.assertEqual(str(parse_portfolio_summary(page)["securitiesEur"]), "12500.00")

    def test_the_latent_gains_row_is_never_mistaken_for_the_securities_total(self):
        summary = parse_portfolio_summary(self._page("12 500,00", "1 234,56", "13 734,56"))
        self.assertNotEqual(str(summary["securitiesEur"]), "0.00")


class NbspRepresentationTest(unittest.TestCase):
    """The same page arrives either as raw HTTP text (literal U+00A0) or as
    browser-serialised DOM (`&nbsp;`). Both must parse identically -- a
    pattern that spells only one of them fails silently on the other, which
    is exactly how a page full of holdings once read as an empty account.
    """

    ROW = (
        '<tr name="FTN0001princip" class="l1">'
        '<td class="txt"><b class="libelle_valeur_tableau">TEST ONE (T1)</b></td>'
        '<td class="numb"><b>12,50<span title="x"><b>V</b></span></b></td>'
        '<td class="numb"><b>1{sep}000</b></td>'
        '<td class="numb"></td>'
        '<td class="numb">10,000</td>'
        '<td class="numb" id="idValorisation_0_tab__categorie_0">12{sep}500,00</td>'
        '<td class="numb">+2{sep}500,00&nbsp;<span class="augmente ">+25,00%</span></td>'
        "</tr>"
    )
    SUMMARY = (
        "<table>"
        '<tr><td class="txt">Évaluation Titres</td><td class="numb">12{sep}500,00</td></tr>'
        '<tr><td class="txt">Solde espèces EUR</td><td class="numb">1{sep}234,56</td></tr>'
        "</table>"
    )

    def _page(self, sep):
        return (self.ROW + self.SUMMARY).replace("{sep}", sep)

    def test_entity_and_literal_nbsp_parse_identically(self):
        entity = self._page("&nbsp;")
        literal = self._page("\xa0")
        self.assertEqual(
            parse_portfolio_positions(entity), parse_portfolio_positions(literal)
        )
        self.assertEqual(
            parse_portfolio_summary(entity), parse_portfolio_summary(literal)
        )

    def test_thousands_separated_values_survive_the_entity_form(self):
        positions = parse_portfolio_positions(self._page("&nbsp;"))
        self.assertEqual(len(positions), 1)
        self.assertEqual(str(positions[0]["quantity"]), "1000")
        self.assertEqual(str(positions[0]["currentValueEur"]), "12500.00")
        summary = parse_portfolio_summary(self._page("&nbsp;"))
        self.assertEqual(str(summary["securitiesEur"]), "12500.00")
        self.assertEqual(str(summary["cashEur"]), "1234.56")

