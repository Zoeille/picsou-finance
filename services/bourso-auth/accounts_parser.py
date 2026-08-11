"""Turns BoursoBank's dashboard HTML and trading JSON into the sidecar contract.

Two upstream shapes are parsed here:

* the account summary at `/dashboard/liste-comptes`, which is HTML grouped into
  `data-summary-bank` / `-savings` / `-trading` / `-loan` sections;
* the trading board's `accounts/summary/{id}` JSON, which carries the cash, the
  portfolio valuation, the account total and every open position.

Kept free of FastAPI and httpx, because these rules are the part that silently
breaks when BoursoBank reskins a page, and a silent break here would overwrite a
correct portfolio with a partial one. Nothing is ever coerced to zero and no row
is ever skipped to "recover": a card that looks like an account but does not
parse fails the whole sync, so the last known-good data survives.
"""

import re
import unicodedata
from decimal import Decimal, InvalidOperation
from typing import Any

FORMAT_CHANGED = "UPSTREAM_FORMAT_CHANGED"
INCOMPLETE = "PORTFOLIO_INCOMPLETE"
INVALID_DATA = "INVALID_DATA"

# BoursoBank reports the account total, its cash and every line. If they
# disagree the read is partial. Same tolerance as the Bourse Direct connector.
MONEY_ABSOLUTE_TOLERANCE = Decimal("0.05")
MONEY_RELATIVE_TOLERANCE = Decimal("0.001")

# A sanity ceiling, not a business rule: a dashboard with hundreds of accounts
# means the regex matched something other than account cards.
MAX_ACCOUNTS = 60

# The dashboard also lists accounts BoursoBank aggregates from other banks. They
# are labelled with their real bank and are deliberately out of scope: they
# would duplicate an Enable Banking connection with worse freshness.
OWN_BANK_LABELS = {"BOURSOBANK", "BOURSORAMA", "BOURSORAMA BANQUE"}

# `</ul>` for savings, `</div>` for the rest -- BoursoBank's own markup, mirrored
# from the reference implementation. Loans are parsed so they can be counted and
# skipped explicitly rather than silently missed.
SECTION_PATTERNS = {
    "banking": re.compile(r"data-summary-bank>(.*?)</div>", re.DOTALL),
    "savings": re.compile(r"data-summary-savings>(.*?)</ul>", re.DOTALL),
    "trading": re.compile(r"data-summary-trading>(.*?)</div>", re.DOTALL),
    "loans": re.compile(r"data-summary-loan>(.*?)</div>", re.DOTALL),
}

_ACCOUNT_RE = re.compile(
    r"/compte/(.*?)?/?(?P<id>[a-f0-9]{32})/(.*?)"
    r"Solde\s:\s(?P<balance>[\d\s\u00a0\u2212-]+,?\d{0,2})\s€"
    r".+?c-info-box__account-label.+?>(?P<name>.+?)</span>"
    r".+?c-info-box__account-sub-label.+?>(?P<bank>.+?)</span>",
    re.DOTALL,
)

# Used only to count the account cards a section *should* have yielded, so a card
# that stopped matching the full pattern is a hard failure instead of a silent drop.
_ACCOUNT_LINK_RE = re.compile(r"/compte/[^\"']*?(?P<id>[a-f0-9]{32})/")

_TAG_RE = re.compile(r"<[^>]+>")


class AccountsFormatError(Exception):
    """A payload that cannot be trusted, carrying the stable code to surface."""

    def __init__(self, code: str, message: str) -> None:
        super().__init__(message)
        self.code = code


# ─── Value helpers ──────────────────────────────────────────────────────────


def _finite(value: Decimal) -> Decimal | None:
    return value if value.is_finite() else None


def decimal_value(value: Any) -> Decimal | None:
    """Parse money without going through float.

    Non-finite values are refused: `json.loads` accepts a bare `NaN`/`Infinity`
    and comparing against one raises `InvalidOperation`, which would escape the
    typed-error handling and surface as an opaque INTERNAL_ERROR.
    """
    if value is None or isinstance(value, bool):
        return None
    if isinstance(value, Decimal):
        return _finite(value)
    if isinstance(value, int):
        return Decimal(value)
    if isinstance(value, float):
        return _finite(Decimal(str(value)))
    if isinstance(value, str):
        return parse_amount(value)
    return None


def parse_amount(raw: str) -> Decimal | None:
    """Parse a French-formatted amount: '11 010,00', '-1 234,5', '−9 495,82'.

    U+2212 MINUS SIGN is what BoursoBank renders for negatives, not ASCII '-'.
    """
    cleaned = (
        raw.strip()
        .replace("\u00a0", "")
        .replace("\u202f", "")
        .replace(" ", "")
        .replace("\u2212", "-")
        .replace("€", "")
        .replace(",", ".")
    )
    if not cleaned or cleaned in {"-", "."}:
        return None
    try:
        return _finite(Decimal(cleaned))
    except InvalidOperation:
        return None


def text_value(value: Any, limit: int) -> str | None:
    if not isinstance(value, str):
        return None
    cleaned = " ".join(_TAG_RE.sub(" ", value).split())
    if not cleaned:
        return None
    return cleaned[:limit]


def money_close(actual: Decimal, expected: Decimal) -> bool:
    difference = abs(actual - expected)
    if difference <= MONEY_ABSOLUTE_TOLERANCE:
        return True
    return difference <= abs(expected) * MONEY_RELATIVE_TOLERANCE


def _deaccent(value: str) -> str:
    return "".join(
        char
        for char in unicodedata.normalize("NFD", value)
        if unicodedata.category(char) != "Mn"
    ).upper()


# ─── Dashboard ──────────────────────────────────────────────────────────────


def account_type(section: str, name: str) -> str:
    """Map a BoursoBank account onto a Picsou AccountType.

    PEA-PME folds into PEA: Picsou has no separate envelope for it, and the two
    share a tax regime and a reporting shape.
    """
    label = _deaccent(name)
    if section == "trading":
        return "PEA" if re.search(r"\bPEA\b|\bPEA-?PME\b", label) else "COMPTE_TITRES"
    if section == "savings":
        if re.search(r"\bLEP\b", label) or "EPARGNE POPULAIRE" in label:
            return "LEP"
        return "SAVINGS"
    return "CHECKING"


def is_own_account(bank_label: str) -> bool:
    return _deaccent(bank_label).strip() in OWN_BANK_LABELS


def parse_dashboard(html: str) -> tuple[list[dict[str, Any]], int]:
    """Parse the account summary page.

    Returns the in-scope accounts and how many aggregated third-party accounts
    were skipped -- the caller logs that count, because a connector that quietly
    drops accounts is indistinguishable from one that is broken.
    """
    if not any(pattern.search(html) for pattern in SECTION_PATTERNS.values()):
        # Every real dashboard carries at least the banking section. None at all
        # means the page moved or we were served something else entirely.
        raise AccountsFormatError(FORMAT_CHANGED, "Dashboard carried no account section")

    accounts: list[dict[str, Any]] = []
    accounted_ids: set[str] = set()
    third_party = 0

    for section, pattern in SECTION_PATTERNS.items():
        for block in pattern.findall(html):
            for match in _ACCOUNT_RE.finditer(block):
                account_id = match.group("id")

                name = text_value(match.group("name"), 200)
                bank = text_value(match.group("bank"), 200)
                balance = parse_amount(match.group("balance"))
                if name is None or bank is None or balance is None:
                    raise AccountsFormatError(
                        FORMAT_CHANGED,
                        f"Account card {account_id[:8]}… is missing a required field",
                    )

                if account_id in accounted_ids:
                    continue
                accounted_ids.add(account_id)

                # Loans are out of scope but still have to be *seen*, or the
                # completeness check below would read them as parse failures.
                if section == "loans":
                    continue
                if not is_own_account(bank):
                    third_party += 1
                    continue

                accounts.append(
                    {
                        "id": account_id,
                        "name": name,
                        "type": account_type(section, name),
                        "balanceEur": balance,
                        "section": section,
                    }
                )

    # Counted over the whole page rather than per section on purpose: the
    # section patterns stop at the first closing tag, so a card gaining a nested
    # list would truncate its section and silently drop everything after it.
    # Comparing against every account link on the page catches that too.
    linked_ids = {match.group("id") for match in _ACCOUNT_LINK_RE.finditer(html)}
    missing = linked_ids - accounted_ids
    if missing:
        raise AccountsFormatError(
            FORMAT_CHANGED,
            f"{len(missing)} account card(s) on the dashboard did not parse",
        )

    if not accounts:
        raise AccountsFormatError(INCOMPLETE, "Dashboard held no BoursoBank account")
    if len(accounts) > MAX_ACCOUNTS:
        raise AccountsFormatError(INCOMPLETE, "Dashboard held more accounts than supported")
    return accounts, third_party


# ─── Trading board ──────────────────────────────────────────────────────────


def _summary_money(raw: Any, field: str, *, required: bool = True) -> Decimal | None:
    """Read one `{value, decimals, currency}` node."""
    if raw is None and not required:
        return None
    if not isinstance(raw, dict):
        raise AccountsFormatError(FORMAT_CHANGED, f"Trading field {field} is not an object")
    parsed = decimal_value(raw.get("value"))
    if parsed is None and required:
        raise AccountsFormatError(FORMAT_CHANGED, f"Trading field {field} has no usable value")
    return parsed


def _summary_currency(raw: Any) -> str | None:
    if not isinstance(raw, dict):
        return None
    currency = text_value(raw.get("currency"), 3)
    return currency.upper() if currency else None


def _parse_position(raw: Any) -> dict[str, Any] | None:
    if not isinstance(raw, dict):
        raise AccountsFormatError(FORMAT_CHANGED, "Trading position is not an object")

    symbol = text_value(raw.get("symbol"), 100)
    label = text_value(raw.get("label"), 200)
    quantity = _summary_money(raw.get("quantity"), "quantity")
    if symbol is None or label is None:
        raise AccountsFormatError(FORMAT_CHANGED, "Trading position is missing symbol or label")

    # A line BoursoBank still lists after it was fully sold.
    if quantity == 0:
        return None

    value_node = raw.get("amount")
    value_eur = _summary_money(value_node, "amount")
    value_currency = _summary_currency(value_node)
    if value_currency is not None and value_currency != "EUR":
        # The connector's whole contract is that valuations are EUR. Guessing an
        # FX rate here would misreport the account; refusing keeps the last good one.
        raise AccountsFormatError(
            INVALID_DATA, f"Position {symbol} is valued in {value_currency}, not EUR"
        )

    last_node = raw.get("last")
    buying_node = raw.get("buyingPrice")
    buying_currency = _summary_currency(buying_node)
    return {
        "symbol": symbol,
        "label": label,
        "quantity": quantity,
        # Only when the broker says it is EUR: a native-currency cost basis
        # recorded as EUR reports a fictitious gain the size of the FX spread.
        "buyingPriceEur": (
            _summary_money(buying_node, "buyingPrice", required=False)
            if buying_currency in (None, "EUR")
            else None
        ),
        "currentPrice": _summary_money(last_node, "last", required=False),
        "quoteCurrency": _summary_currency(last_node),
        "currentValueEur": value_eur,
        "pnlEur": _summary_money(raw.get("gainLoss"), "gainLoss", required=False),
    }


def parse_trading_summary(payload: Any, account_id: str) -> dict[str, Any]:
    """Normalise one trading account's summary and prove it is complete.

    Two reconciliations, both of which a partial read fails:
      total    ~= cash + portfolio valuation
      sum(line valuations) ~= portfolio valuation
    """
    items = payload if isinstance(payload, list) else [payload]
    entry = next((item for item in items if isinstance(item, dict)), None)
    if entry is None:
        raise AccountsFormatError(FORMAT_CHANGED, "Trading summary is empty")

    account = entry.get("account")
    if not isinstance(account, dict):
        raise AccountsFormatError(FORMAT_CHANGED, "Trading summary has no account node")

    cash = _summary_money(account.get("cash"), "cash")
    valuation = _summary_money(account.get("valuation"), "valuation")
    total = _summary_money(account.get("total"), "total")

    currency = _summary_currency(account.get("total"))
    if currency is not None and currency != "EUR":
        raise AccountsFormatError(
            INVALID_DATA, f"Trading account {account_id[:8]}… is denominated in {currency}"
        )

    raw_positions = entry.get("positions")
    if raw_positions is None:
        raw_positions = []
    if not isinstance(raw_positions, list):
        raise AccountsFormatError(FORMAT_CHANGED, "Trading positions are not a list")

    positions = [parsed for parsed in (_parse_position(item) for item in raw_positions) if parsed]

    if not money_close(cash + valuation, total):
        raise AccountsFormatError(
            INCOMPLETE, "Trading account total does not reconcile with cash plus valuation"
        )

    lines_total = sum((position["currentValueEur"] for position in positions), Decimal("0"))
    if not money_close(lines_total, valuation):
        raise AccountsFormatError(
            INCOMPLETE, "Trading positions do not reconcile with the portfolio valuation"
        )

    return {"cashEur": cash, "totalEur": total, "positions": positions}


def assign_tickers(positions: list[dict[str, Any]], isins: dict[str, str]) -> list[dict[str, Any]]:
    """Attach the resolved ISIN to each position, falling back to its symbol.

    BoursoBank's trading board only exposes its own instrument symbol, so the
    ISIN is looked up separately and may legitimately be missing -- an unresolved
    line is still a line the user holds. Two positions collapsing onto the same
    fallback would merge two different instruments into one holding downstream,
    so that is refused instead.
    """
    resolved = []
    fallbacks: set[str] = set()
    for position in positions:
        isin = isins.get(position["symbol"])
        if isin is None:
            fallback = position["symbol"].upper()
            if fallback in fallbacks:
                raise AccountsFormatError(
                    INVALID_DATA,
                    f"Two positions without an ISIN share the symbol {fallback}",
                )
            fallbacks.add(fallback)
        resolved.append({**position, "isin": isin})
    return resolved
