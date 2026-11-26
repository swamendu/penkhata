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
