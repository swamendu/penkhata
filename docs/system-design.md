# PenKhata — console and Android app system design

Design decisions taken in discussion (Nov 2026). The PDF beside this file carries the
three flowcharts; this is the durable text.

## The rule
A brand sales rep **captures** a sale on the Android app; the outlet manager **bills** it
on the console. Only the counter issues invoice numbers, so numbering can never collide.

## Surfaces
- **Console** — responsive web app (PWA). Owner, manager, outlet manager, duty manager,
  stock auditor. Bills, takes payment, closes the day, runs audits.
- **Android app** — brand sales reps only. Capture, IMEI reservation, delivery challan,
  cash custody, sync. No invoicing, no numbers.
- **Server** — identity, roles, outlets, events, numbering, sync, audit trail.

## Numbering
One series per outlet (e.g. `MOBMAN/S11/00001`). Issued by the server at billing time.
Reps never hold numbers.

## Three dates
| Date | Means | Used by |
|---|---|---|
| Challan date | when the rep issued the challan | evidence |
| Delivery date | when the customer received the goods | warranty, brand claims, printed bill |
| Invoice date | when the tax invoice was raised | GST return, monthly sales |

Warranty runs from **delivery**, not invoice. Month-end countdown from the 25th; a challan
raised on the last day must be billed that day.

## Request lifecycle
`PENDING → BILLED / CANCELLED`. Never expires; it ages and escalates (2–3 days amber,
7+ days red on the owner's exceptions).

## Cash custody
Collected → handover (rep declares) → received (manager acknowledges) → deposited.
Mismatch is flagged, never silently accepted. If the manager is absent the rep taps
HOLDING and it becomes a visible liability.

## Stock states
in stock · reserved · sold · defective/DOA · with brand (RTV) · out for service · written off.
The IMEI keeps its identity through every state.


## Schemes (brand offers)

A scheme is **three amounts wearing one label**:

| Amount | Nature |
|---|---|
| Full discount on the invoice | Reduces taxable value; the customer sees it |
| Company share (e.g. ₹2,200 of ₹3,000) | **Receivable** — claim it or lose it |
| Dealer share (e.g. ₹800) | **Margin forgone**, not cash paid out |

Example: Galaxy F56 at ₹22,499, cash upgrade offer ₹3,000 = ₹2,200 company + ₹800 dealer.
Finance slab differs: ₹1,500 = ₹1,000 + ₹500. **The slab carries the amounts, not the scheme.**

### A scheme record holds
1. Brand, code, type (upgrade, exchange, cashback, subvention, gift, buyback, volume)
2. Validity window and **claim deadline** (often later than the sale window)
3. Scope — category, model, SKU, and attributes (43-inch+, 1.5T, 260L)
4. Slabs — company and dealer amounts per payment mode
5. Stacking rule — which other schemes may run alongside

### Claim ledger
`PENDING → SUBMITTED → APPROVED → CREDITED` (or `REJECTED` with reason).
Brand pays by **credit note**, so the company share reduces payables or lands in the bank.

Console must show three different numbers, because they differ:
applied · claimed · credited — plus **claimed-but-not-received** (chase it) and
**not-claimed-past-deadline** (act now). That gap is where dealers lose money to paperwork.

### Who does what
| Action | Data entry | Outlet manager | Manager above | Owner |
|---|---|---|---|---|
| Enter scheme from circular | draft | draft | yes | yes |
| Approve for use | no | no | yes | yes |
| Apply at billing | no | yes | yes | yes |
| File the claim | yes | yes | yes | yes |

### Margin, told honestly
Gross margin − dealer scheme share = **net margin**. Company share is a separate receivable.

## Authority
| Action | Outlet manager | Manager above | Owner |
|---|---|---|---|
| Bill | yes | yes | yes |
| Grant discount | request only | within owner's limit | always |
| Approve variance | submit only | yes | yes |
| Close month | no | yes | yes |
| Create/revoke user | no | request only | yes |
| Set discount limit | no | no | yes |

Duty manager: granted by manager or owner, end of shift, max 12 hours, auto-revoked,
actions tagged "duty".

## Open
- Discount limit value (owner only)
- Defective stock valuation timing
- Whether a rep may bill another brand
- Counter offline strategy (PWA offline queue vs fallback phone)
