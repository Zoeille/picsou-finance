"""Parsing helpers for Fortuneo account/position/transaction pages.

STATUS: implemented and validated from real captures (two authenticated-
traffic HARs, a live login/2FA DOM, and a full-site HAR that happened to
include a PEA's real holdings -- login form and generic 2FA shape came
from the DOM directly, not a HAR; see docs/features/fortuneo.md "Discovery
status"). Confirmed from those:

- The primary, modern account listing + balance source is a GraphQL
  "Equipment" query: ``POST https://api.fortuneo.fr/account-items-bff/graphql``.
  It returns every account (life insurance, PER, PEA/PEA-PME, CTO, its
  linked cash pocket, Compte Courant, Livret, mortgages, external accounts)
  with id, label, masked account number, ``type.value`` (matches the
  ``productType`` strings below) and, via GraphQL ``@defer``, its balance.
  The response is HTTP chunked multipart (``Content-Type: multipart/mixed;
  boundary="-"``): a first part with the base structure (``hasNext: true``),
  then one part per deferred fragment (``{"incremental": [{"data": ...,
  "path": [...]}]}``) to merge in by JSON-pointer-like path.
  ``parse_equipment_multipart`` below parses and fully merges this into one
  plain dict; ``extract_accounts_from_equipment`` flattens the in-scope
  categories into raw account records.
- Every ``api.fortuneo.fr`` request (this GraphQL endpoint,
  ``account-api/v2/accounts``, ``fto-transaction-api``) sends a static
  ``apikey`` header. It was the same value across two capture sessions
  hours apart, but it is also the same value submitted as ``client_id`` in
  the ``/oauth-pkce/token`` request during login -- so the sidecar should
  capture it live from that login request rather than hardcoding it, to
  stay correct if Fortuneo ever rotates it.
- ``GET https://api.fortuneo.fr/fto-transaction-api/v1/accounts/{id}/transactions``
  returns a clean JSON array of transactions for cash-type accounts
  (``id``, ``label.simplifiedLabel``, ``amount.value``/``amount.currency``,
  ``bookingDate``).
- A CTO (``ordinary-securities-account``) has its own separate cash pocket,
  Fortuneo product type ``cash-account``, which is NOT a standalone account
  a user opens -- it must be folded into the parent CTO's ``cashBalance``,
  not synced as its own Picsou account. A PEA/PEA-PME has no such separate
  cash-account entry (its cash pocket is implicit). The Equipment query has
  no explicit CTO<->cash-pocket link field; ``fold_cash_pockets_into_securities_accounts``
  pairs them 1:1 when there is exactly one of each (the observed, and
  presumably common, case) and flags ambiguity otherwise. The legacy HTML
  fragment parsed by ``parse_bourse_synthese_table`` (from
  ``GET /AsynchAjax?div0=as_afficherSyntheseComptesBourse.do_codeProfilsTIT,COT&...``,
  a ``{"data":[{"flux": "<html>...</html>"}]}`` envelope) DOES have an
  explicit DOM-adjacency link and is kept as a documented fallback for that
  ambiguous multi-CTO case -- not yet wired into ``main.py``, since there is
  no multi-CTO account available to verify it against.

- Per-position holdings have no API at all: they are scraped from
  ``GET https://mabanque.fortuneo.fr/fr/prive/mes-comptes/{segment}/situation/``
  (``PRODUCT_TYPE_TO_PORTFOLIO_SEGMENT`` maps each account's Equipment
  ``type.value`` to ``pea``/``ppe``/``compte-titres-pea``).
  ``parse_portfolio_positions`` extracts each ``<tr name="{code}princip"
  class="l1|l2">`` row (the ISIN is ``{code}``'s trailing 12 characters) --
  validated against a real captured page with 14 real holdings, every
  price/quantity/valuation/P&L internally consistent to the cent (see
  docs/features/fortuneo.md "Verification boundaries"). A PEA's
  ``cashBalance`` is then *derived* as ``balanceEur - sum(positions)``
  (``main.py``'s ``/accounts`` handler) rather than scraped, since Fortuneo
  has no cash/position breakdown for a PEA anywhere else.

Still open (see docs/features/fortuneo.md "Discovery status" and
"Verification boundaries"): whether a real rejected login ever hits the
timeout-based ``INVALID_CREDENTIALS`` fallback, the live sidecar's own
``page.goto()`` navigation for positions (validated so far only by feeding
the parser real captured HTML directly), and multi-CTO / foreign-currency-
CTO handling.

The generic French-number decimal coercion below is reused as-is from
services/bourse-direct-auth/portfolio_parser.py (the format is common
across French bank/broker sites, not Bourse-Direct-specific).
"""

import html as html_lib
import json
import re
import unicodedata
from decimal import Decimal, InvalidOperation
from typing import Any


class PortfolioFormatError(ValueError):
    """Raised when an upstream monetary field is present but unusable."""


# Fortuneo `productType` (account-api/v2/accounts) -> Picsou AccountType.
# `cash-account` is deliberately excluded: it is a CTO's linked cash
# pocket, not a standalone Picsou account (see module docstring).
ACCOUNT_TYPE_BY_PRODUCT_TYPE: dict[str, str] = {
    "current-account": "CHECKING",
    "ordinary-securities-account": "COMPTE_TITRES",
    "share-savings-plan": "PEA",
    "sme-share-savings-plan": "PEA",  # PEA-PME; Picsou has no distinct type
}

# Fortuneo `type.value` -> the `/mes-comptes/{segment}/situation/` URL
# segment that shows that account's actual position holdings (confirmed
# live -- see `parse_portfolio_positions`). Distinct from
# ACCOUNT_TYPE_BY_PRODUCT_TYPE: PEA and PEA-PME both collapse to Picsou
# `PEA` but need different URL segments to fetch positions for the right
# account, since an individual can hold one of each simultaneously.
PRODUCT_TYPE_TO_PORTFOLIO_SEGMENT: dict[str, str] = {
    "share-savings-plan": "pea",
    "sme-share-savings-plan": "ppe",
    "ordinary-securities-account": "compte-titres-pea",
}

# The `/mes-comptes/{segment}/situation/portefeuille-temps-reel.jsp` URL
# segment for each securities-account family, as observed in the synthese-
# bourse HTML fragment -- NOT the "ORD"/"PEA"/"PPE" codes that also appear
# elsewhere in that fragment (in an unrelated `optAjax_...` element id),
# which this parser does not rely on.
_BOURSE_URL_SEGMENT_TO_TYPE = {
    "compte-titres-pea": "COMPTE_TITRES",  # CTO; segment name is historical/misleading
    "pea": "PEA",
    "ppe": "PEA",  # PEA-PME; Picsou has no distinct type
}


_CURRENCY_CODE = r"(?:EUR|USD|GBP|CHF|JPY|CAD|AUD|NZD|SEK|NOK|DKK|GBX)"


def decimal_value(raw: Any, field: str = "value") -> Decimal:
    """Parse a required decimal without turning protocol drift into a false zero."""
    if raw is None or isinstance(raw, bool):
        raise PortfolioFormatError(f"Missing {field}")
    value = str(raw).strip().replace("\xa0", " ").replace(" ", " ")
    negative_parentheses = value.startswith("(") and value.endswith(")")
    if negative_parentheses:
        value = value[1:-1].strip()
    # Accept presentation-only currency/percentage affixes, but reject letters
    # embedded in the numeric value instead of silently deleting them (for
    # example, "1e3" must never become "13").
    value = re.sub(
        rf"^(?:{_CURRENCY_CODE}|[€$£¥])\s*",
        "",
        value,
        flags=re.IGNORECASE,
    )
    while True:
        stripped = re.sub(
            rf"\s*(?:{_CURRENCY_CODE}|[€$£¥%])$",
            "",
            value,
            flags=re.IGNORECASE,
        )
        if stripped == value:
            break
        value = stripped
    cleaned = value.replace(" ", "").replace("'", "")
    if negative_parentheses:
        cleaned = "-" + cleaned
    if not re.fullmatch(r"[+-]?[0-9][0-9,.-]*", cleaned):
        raise PortfolioFormatError(f"Invalid {field}")
    if not cleaned or cleaned in {"-", ".", ",", "-.", "-,"}:
        raise PortfolioFormatError(f"Missing {field}")
    if "," in cleaned and "." in cleaned:
        if cleaned.rfind(",") > cleaned.rfind("."):
            cleaned = cleaned.replace(".", "").replace(",", ".")
        else:
            cleaned = cleaned.replace(",", "")
    elif cleaned.count(",") > 1:
        head, tail = cleaned.rsplit(",", 1)
        cleaned = head.replace(",", "") + "." + tail
    elif cleaned.count(".") > 1:
        head, tail = cleaned.rsplit(".", 1)
        cleaned = head.replace(".", "") + "." + tail
    else:
        cleaned = cleaned.replace(",", ".")
    try:
        return Decimal(cleaned)
    except InvalidOperation as exc:
        raise PortfolioFormatError(f"Invalid {field}") from exc


def optional_decimal_value(raw: Any, field: str = "value") -> Decimal | None:
    """Parse an optional decimal, while still rejecting malformed present values."""
    if raw is None or (isinstance(raw, str) and not raw.strip()):
        return None
    return decimal_value(raw, field)


def account_balance(account_json: dict[str, Any]) -> Decimal | None:
    """Extract the `balance`-named amount from an account-api account object.

    Cash-type accounts (`current-account`, `cash-account`) carry a
    `balances` array with one entry named "balance"; securities accounts
    have no `balances` key at all. (account-api/v2/accounts is not the
    primary data source -- see `extract_accounts_from_equipment` -- but this
    helper is kept since the REST shape is confirmed and may be useful as a
    cross-check or fallback.)
    """
    for entry in account_json.get("balances") or []:
        if entry.get("name") == "balance":
            amount = entry.get("amount") or {}
            return optional_decimal_value(amount.get("value"), "balance")
    return None


# Equipment GraphQL category path -> Picsou AccountType. Classifying by
# category path (rather than each account's own `type.value`) means a
# Livret account is correctly recognized as SAVINGS even though its exact
# `type.value` string was never observed (the captured account had no
# Livret) -- its category location is confirmed regardless.
_EQUIPMENT_CATEGORY_ACCOUNT_TYPE: dict[tuple[str, str], str] = {
    ("financialPortfolio", "shareSavingsPlan"): "PEA",
    ("financialPortfolio", "ordinarySecurities"): "COMPTE_TITRES",
    ("banking", "current"): "CHECKING",
    ("banking", "savings"): "SAVINGS",
}
# A CTO's linked cash pocket: not a standalone Picsou account (see module
# docstring) -- extracted separately so callers can fold its balance into
# the parent CTO via `fold_cash_pockets_into_securities_accounts`.
_EQUIPMENT_CASH_POCKET_PATH: tuple[str, str] = ("financialPortfolio", "cash")


def parse_equipment_multipart(raw: str) -> dict[str, Any]:
    """Merge a GraphQL `Equipment` query's chunked multipart/mixed response
    (deferred fragments, `Content-Type: multipart/mixed; boundary="-"`) into
    one plain dict -- the unwrapped contents of the response's top-level
    `data.equipment` key.

    Each part after the first is `{"hasNext": bool, "incremental": [{"data":
    {...}, "path": [...]}]}`; `data` is merged (dict.update, shallow) into
    the base structure at the location `path` points to. Raises
    PortfolioFormatError if the response has no parseable base part, so a
    transport glitch never silently yields an empty/partial equipment tree.
    """
    merged: dict[str, Any] | None = None
    for part in re.split(r"(?:\A|\r?\n)---\r?\n", raw):
        part = part.strip()
        if not part:
            continue
        body = re.split(r"\r?\n\r?\n", part, maxsplit=1)
        json_text = body[-1] if len(body) > 1 else body[0]
        json_text = re.sub(r"-+\s*$", "", json_text).strip()
        if not json_text or not json_text.startswith("{"):
            continue
        try:
            chunk = json.loads(json_text)
        except json.JSONDecodeError as exc:
            raise PortfolioFormatError("Malformed Equipment multipart chunk") from exc

        if merged is None:
            merged = chunk.get("data")
            continue
        for item in chunk.get("incremental", []):
            path = item.get("path") or []
            target = merged
            try:
                for key in path[:-1]:
                    target = target[key]
                if not path:
                    raise PortfolioFormatError("Equipment fragment has no path")
                last = path[-1]
                if isinstance(target[last], dict):
                    target[last].update(item.get("data") or {})
                else:
                    target[last] = item.get("data")
            except (KeyError, IndexError, TypeError) as exc:
                raise PortfolioFormatError("Equipment fragment path did not resolve") from exc

    if not merged or "equipment" not in merged:
        raise PortfolioFormatError("Equipment response had no base data")
    return merged["equipment"]


def extract_accounts_from_equipment(equipment: dict[str, Any]) -> list[dict[str, Any]]:
    """Flatten the merged `Equipment` tree into raw account records.

    Returns one dict per account under the categories Picsou syncs today:
    `{"webId", "type", "productType", "label", "accountNumber", "typeLabel",
    "balanceEur"}`, where `type` is a Picsou `AccountType` string, or `None`
    for `financialPortfolio.cash` entries (CTO cash pockets -- see
    `fold_cash_pockets_into_securities_accounts`). `productType` is
    Fortuneo's own `type.value` (e.g. `share-savings-plan` vs.
    `sme-share-savings-plan`) -- needed downstream to pick the right
    `.../situation/` URL segment for position scraping, since PEA and
    PEA-PME both collapse to Picsou `PEA` but live at different URLs (see
    `PRODUCT_TYPE_TO_PORTFOLIO_SEGMENT`). `typeLabel` is Fortuneo's own
    display label for the account type (e.g. "PEA", "Compte courant"), kept
    for building a human-readable account name -- Fortuneo's `label` field
    is just the holder's name, not a per-account name. `lifeInsurance`,
    `retirementSavingsPlan` (PER), `mortgages`, and `external` accounts are
    out of scope and skipped entirely.
    """
    accounts: list[dict[str, Any]] = []
    paths = list(_EQUIPMENT_CATEGORY_ACCOUNT_TYPE.items()) + [(_EQUIPMENT_CASH_POCKET_PATH, None)]
    for (top, category), account_type in paths:
        node = (equipment.get(top) or {}).get(category) or {}
        for account in node.get("accounts") or []:
            balance = account.get("balance") or {}
            account_type_info = account.get("type") or {}
            accounts.append({
                "webId": account.get("id"),
                "type": account_type,
                "productType": account_type_info.get("value"),
                "label": account.get("label"),
                "accountNumber": account.get("accountNumber"),
                "typeLabel": account_type_info.get("label"),
                "balanceEur": optional_decimal_value(balance.get("amount"), "balance"),
            })
    return accounts


def fold_cash_pockets_into_securities_accounts(
    accounts: list[dict[str, Any]],
) -> list[dict[str, Any]]:
    """Fold CTO cash-pocket entries (`type is None`) into their CTO's
    `cashBalance`, returning a new list with the cash-pocket entries removed.

    Pairs 1:1 when there is exactly one `COMPTE_TITRES` account and exactly
    one cash-pocket entry (the observed, and presumably common, case). Any
    other count raises PortfolioFormatError rather than guessing a pairing
    -- ambiguous multi-CTO cases should use `parse_bourse_synthese_table`'s
    explicit link instead (not yet wired in; see module docstring).

    CHECKING/SAVINGS accounts get `cashBalance = balanceEur` (genuinely no
    positions). CTO accounts get their paired cash pocket's own balance, or
    `None` if none is present, for the caller to reject as an incomplete
    snapshot. **PEA accounts also get `cashBalance = None`** -- unlike
    CHECKING/SAVINGS, a PEA can hold real stock positions, and unlike CTO
    there is no separate "cash pocket" account to source a true cash figure
    from. Without per-position data (not yet implemented, see module
    docstring), there is no way to know a PEA's actual cash-vs-position
    split, so guessing `cashBalance = balanceEur` would silently misreport
    a PEA holding real positions as 100% cash -- confirmed to happen
    against a live PEA before this was fixed. `None` here makes the caller
    reject the snapshot instead.
    """
    ctos = [a for a in accounts if a["type"] == "COMPTE_TITRES"]
    pockets = [a for a in accounts if a["type"] is None]
    if len(ctos) != len(pockets):
        raise PortfolioFormatError(
            f"Cannot pair {len(ctos)} CTO account(s) with {len(pockets)} cash pocket(s)"
        )

    pocket_balance_by_index = {id(cto): pocket["balanceEur"] for cto, pocket in zip(ctos, pockets)}
    cash_only_types = {"CHECKING", "SAVINGS"}
    result: list[dict[str, Any]] = []
    for account in accounts:
        if account["type"] is None:
            continue  # cash pocket, folded below
        folded = dict(account)
        if account["type"] == "COMPTE_TITRES":
            folded["cashBalance"] = pocket_balance_by_index[id(account)]
        elif account["type"] in cash_only_types:
            folded["cashBalance"] = account["balanceEur"]
        else:
            folded["cashBalance"] = None
        result.append(folded)
    return result


_SYNTHESE_ACCOUNT_BLOCK = re.compile(
    r'href="[^"]*?/mes-comptes/(?P<segment>[a-z-]+)/situation/portefeuille-temps-reel\.jsp\?ca=(?P<web_id>[A-Za-z0-9_-]+)"'
    r'[^>]*>\s*'
    r'(?:(?!</a>).)*?'
    r'<span class="synthese_numero_compte">\s*(?P<numero>[^<]*?)\s*</span>'
    r'(?:(?!</a>).)*?'
    r'<span class="synthese_compte_tiret">[^<]*</span>\s*(?P<label>[^\n<]*)\s*'
    r'(?:(?!</a>).)*?'
    r'<span class="synthese_solde_compte\s*">\s*(?P<solde>[^<]*?)\s*</span>'
    r'(?:(?!</a>).)*?</a>',
    re.DOTALL,
)

_SYNTHESE_CASH_POCKET_BLOCK = re.compile(
    r'href="[^"]*?consulter-solde\.jsp\?ca=(?P<web_id>[A-Za-z0-9_-]+)"'
    r'[^>]*class="synthese_compte_associe"'
    r'(?:(?!</a>).)*?'
    r'<span class="synthese_solde_compte_espece_associe\s*">\s*(?P<solde>[^<]*?)\s*</span>'
    r'(?:(?!</a>).)*?</a>',
    re.DOTALL,
)


def parse_bourse_synthese_table(html: str) -> list[dict[str, Any]]:
    """Parse the `famille_bourse` HTML fragment into per-account totals.

    Returns one dict per securities account:
    ``{"webId": str, "type": "PEA" | "COMPTE_TITRES", "label": str,
    "totalEur": Decimal, "cashWebId": str | None}``.

    ``cashWebId`` is set only for a CTO row that has an associated
    "Compte espèces" sub-row immediately following it in the fragment
    (`synthese_compte_ligne_autre`) -- PEA/PEA-PME never have one, their
    cash pocket is implicit. The associated cash account's own balance
    must be fetched separately via account-api and folded into the CTO's
    `cashBalance`; this function only resolves *which* cash account id
    belongs to which securities account.

    Raises PortfolioFormatError if an account block's total is missing or
    malformed -- never silently drops or zeroes a valuation.
    """
    accounts: list[dict[str, Any]] = []
    account_matches = list(_SYNTHESE_ACCOUNT_BLOCK.finditer(html))
    cash_matches = list(_SYNTHESE_CASH_POCKET_BLOCK.finditer(html))

    for i, match in enumerate(account_matches):
        segment = match.group("segment")
        account_type = _BOURSE_URL_SEGMENT_TO_TYPE.get(segment)
        if account_type is None:
            raise PortfolioFormatError(f"Unrecognized Fortuneo bourse account segment: {segment!r}")
        total = decimal_value(html_lib.unescape(match.group("solde")), "totalEur")

        # A cash-pocket block belongs to this account if it appears after
        # this account's block and before the next one.
        block_end = match.end()
        next_start = account_matches[i + 1].start() if i + 1 < len(account_matches) else len(html)
        cash_web_id = next(
            (c.group("web_id") for c in cash_matches if block_end <= c.start() < next_start),
            None,
        )

        accounts.append({
            "webId": match.group("web_id"),
            "type": account_type,
            "label": html_lib.unescape(match.group("label").strip()),
            "totalEur": total,
            "cashWebId": cash_web_id,
        })

    return accounts


# Each holding is a `<tr name="{code}princip" class="l1"|"l2">` row
# (zebra-striped) on a securities account's `.../situation/` page --
# confirmed live; there is no JSON API for this (the Equipment GraphQL
# query has no position field). `{code}`'s trailing 12 characters are the
# ISIN (e.g. "FTN000000XX0000000001" -> "XX0000000001").
_POSITION_ROW = re.compile(
    r'<tr name="(?P<code>[A-Za-z0-9]+)princip" class="l[12]"\s*>(?P<body>.*?)</tr>',
    re.DOTALL,
)
_ISIN_FROM_CODE = re.compile(r"([A-Z]{2}[A-Z0-9]{10})$")
_POSITION_LABEL = re.compile(r'libelle_valeur_tableau">([^<]+)<')
# Every numeric cell below requires a leading digit in the capture group --
# several *other* `<td class="numb">` cells in the same row are blank
# (whitespace-only, for SRD-related columns this account type doesn't use)
# and would otherwise be matched first by a naive `[\d.,\s]+` pattern,
# silently producing a spurious zero-ish value instead of the real one
# (confirmed against a real captured page during development).
_POSITION_PRICE = re.compile(r'<td class="numb">\s*<b>\s*(\d[\d.,\s\xa0]*?)\s*<span title=', re.DOTALL)
_POSITION_QUANTITY = re.compile(r'<td class="numb">\s*<b>\s*(\d[\d\s\xa0]*)\s*</b>\s*</td>', re.DOTALL)
_POSITION_PRU = re.compile(r'<td class="numb">\s*(\d[\d.,\s\xa0]*)\s*</td>')
_POSITION_VALUATION = re.compile(
    r'<td class="numb" id="idValorisation_\d+_tab__categorie_\d+"\s*>\s*(\d[\d.,\s\xa0]*)\s*</td>'
)
_POSITION_PNL = re.compile(
    # No `&nbsp;` here: _normalize_nbsp has already folded it to \xa0, which
    # the character class covers. Spelling the entity would match only the
    # raw-HTTP form and silently drop P&L from browser-serialised DOM.
    r'<td class="numb">\s*([+-]\s*\d[\d\s.,\xa0]*)<span class="(?:augmente|baisse)\s*"',
    re.DOTALL,
)


def _normalize_nbsp(html: str) -> str:
    """Fold non-breaking-space entities to the literal character.

    The same page reaches this module in two forms: raw HTTP text, where
    Fortuneo's thousands separator is a literal U+00A0, and
    browser-serialised DOM (Playwright's `content()`), where it is the
    entity `&nbsp;`. Every numeric pattern below would otherwise have to
    spell both, and missing one fails *silently* -- confirmed live twice,
    on a page that did contain the data. Only this entity is folded, not
    the whole document: a blanket `html.unescape` would turn `&lt;` into a
    real `<` and invent tags that were never in the markup.
    """
    return (
        html.replace("&nbsp;", "\xa0")
        .replace("&#160;", "\xa0")
        .replace("&#xa0;", "\xa0")
        .replace("&#xA0;", "\xa0")
    )


def parse_portfolio_positions(html: str) -> list[dict[str, Any]]:
    """Parse a securities account's `.../situation/` page into per-position
    holdings: `{"isin", "symbol", "label", "quantity", "buyingPriceEur",
    "currentPrice", "quoteCurrency", "currentValueEur", "pnlEur"}` per
    holding.

    `symbol` is set to the ISIN, not a parsed ticker -- Fortuneo's own
    ticker suffix (e.g. "BNP PARIBAS ACT.A (BNP)") is stripped from
    `label` rather than used, since the existing OpenFIGI ISIN->ticker
    resolution downstream (mirroring Bourse Direct) is the authoritative
    path; a security with no real ticker shows "(-)" here; both cases are
    resolved (or safely fall back to the ISIN) on the Java side.
    `quoteCurrency` is hardcoded "EUR": a PEA can only hold EU-domiciled,
    EUR-quoted securities by French regulation. This has not been verified
    for a CTO, which can hold foreign-currency securities -- revisit if a
    CTO with real holdings is ever captured.

    `pnlEur` is optional: a position exactly at cost (no gain/loss) has no
    coloured span to match and legitimately parses as `None` (confirmed
    live for a security whose valuation matches its cost basis exactly).
    `quantity` and `currentValueEur` are mandatory; missing either raises
    PortfolioFormatError rather than silently dropping a holding's value.
    """
    html = _normalize_nbsp(html)
    positions: list[dict[str, Any]] = []
    for match in _POSITION_ROW.finditer(html):
        code = match.group("code")
        body = match.group("body")
        isin_match = _ISIN_FROM_CODE.search(code)
        isin = isin_match.group(1) if isin_match else None

        label_match = _POSITION_LABEL.search(body)
        label = html_lib.unescape(label_match.group(1)).strip() if label_match else code
        label = re.sub(r"\s*\([^()]*\)\s*$", "", label).strip() or label

        quantity_match = _POSITION_QUANTITY.search(body)
        valuation_match = _POSITION_VALUATION.search(body)
        if not quantity_match or not valuation_match:
            raise PortfolioFormatError(
                f"Fortuneo position row {code!r} is missing quantity or valuation"
            )
        price_match = _POSITION_PRICE.search(body)
        pru_match = _POSITION_PRU.search(body)
        pnl_match = _POSITION_PNL.search(body)

        positions.append({
            "isin": isin,
            "symbol": isin or code,
            "label": label,
            "quantity": decimal_value(quantity_match.group(1), "quantity"),
            "buyingPriceEur": optional_decimal_value(
                pru_match.group(1) if pru_match else None, "buyingPriceEur"
            ),
            "currentPrice": optional_decimal_value(
                price_match.group(1) if price_match else None, "currentPrice"
            ),
            "quoteCurrency": "EUR",
            "currentValueEur": decimal_value(valuation_match.group(1), "currentValueEur"),
            "pnlEur": optional_decimal_value(
                pnl_match.group(1).replace(" ", "") if pnl_match else None, "pnlEur"
            ),
        })
    return positions


# The legacy site keys accounts by its own 32-hex `ca` identifier, which is
# a DIFFERENT identifier space from the modern Equipment API's `id` (a
# 22-char base64-ish token like "Ab1Cd2Ef3Gh4Ij5Kl6Mn7O"). Confirmed
# against a real capture: no Equipment `id` is 32-hex, and the `ca` values
# appear nowhere in the GraphQL response. Passing an Equipment id as `ca`
# does not error -- the portfolio page still renders, just without any
# holdings -- so this has to be read from the legacy side.
#
# The legacy home page (LEGACY_HOME_URL) links to every account's own
# situation page, which is where the mapping comes from.
_LEGACY_ACCOUNT_LINK = re.compile(
    r"/fr/prive/mes-comptes/(?P<segment>[a-z0-9-]+)/(?:consulter-)?situation/"
    r"\?ca=(?P<ca>[0-9a-f]{32})"
)


def parse_legacy_account_ids(html: str) -> dict[str, str]:
    """Map legacy URL segment -> that account's legacy `ca` id, read from
    the legacy home page's own account links.

    First occurrence wins per segment. An individual can hold only one PEA
    and one PEA-PME by French regulation, so those are unambiguous; a
    customer with several CTOs would need disambiguating beyond the segment
    (the same open question as the multi-CTO cash-pocket case).
    """
    ids: dict[str, str] = {}
    for match in _LEGACY_ACCOUNT_LINK.finditer(html):
        ids.setdefault(match.group("segment"), match.group("ca"))
    return ids


# The situation page carries its own authoritative summary table:
#
#     Évaluation Titres                 75 463,36
#     dont +/- values latentes ...       9 651,71
#     Solde espèces EUR                  8 821,38
#     Valorisation totale               84 284,74
#
# This is worth far more than the row list alone. "Évaluation Titres"
# reconciles against the parsed holdings -- catching the case where the
# page renders but its table doesn't, which is otherwise indistinguishable
# from a genuinely empty account. And "Solde espèces EUR" is the account's
# real cash, so a PEA's cash no longer has to be derived by subtraction.
# The value cell is captured loosely and validated afterwards, rather than
# matched with a numeric pattern inline. The same page reaches us in two
# forms: raw HTTP text, where the thousands separator is a literal U+00A0,
# and browser-serialised DOM (frame.content()), where it is the entity
# `&nbsp;`. A numeric-only pattern silently fails to match the entity
# form -- confirmed live, on a page that did contain the summary.
_SUMMARY_ROW = re.compile(
    r"<tr[^>]*>\s*<td[^>]*>\s*(?:<strong>\s*)?(?P<label>[^<]+?)\s*(?:</strong>\s*)?</td>\s*"
    r"<td[^>]*>\s*(?P<value>[^<]*?)\s*</td>",
    re.DOTALL,
)
_SUMMARY_NUMBER = re.compile(r"-?\d[\d\s.,\xa0]*")
_SUMMARY_FIELDS = (
    ("securitiesEur", ("evaluation titres",)),
    ("cashEur", ("solde especes",)),
    ("totalEur", ("valorisation totale",)),
)


def _strip_accents(value: str) -> str:
    return "".join(
        c for c in unicodedata.normalize("NFD", value) if unicodedata.category(c) != "Mn"
    )


def parse_portfolio_summary(html: str) -> dict[str, Any]:
    """Read the situation page's own valuation summary.

    Returns `{"securitiesEur", "cashEur", "totalEur"}`, each `None` when
    that row is absent. Labels are matched accent- and case-insensitively
    on a prefix, so "Solde espèces EUR" still matches if Fortuneo appends
    or re-cases anything; the ordering of rows is not relied on.
    """
    html = _normalize_nbsp(html)
    found: dict[str, Any] = {key: None for key, _ in _SUMMARY_FIELDS}
    for match in _SUMMARY_ROW.finditer(html):
        label = _strip_accents(html_lib.unescape(match.group("label"))).lower().strip()
        for key, prefixes in _SUMMARY_FIELDS:
            if found[key] is None and any(label.startswith(p) for p in prefixes):
                value = html_lib.unescape(match.group("value")).strip()
                if _SUMMARY_NUMBER.fullmatch(value):
                    found[key] = optional_decimal_value(value, key)
                break
    return found
