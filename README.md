# DealFlow360

An Intelligent, Self-Governing Sales Operations Platform - hackathon build.

Stack: **Spring Boot 3 (Java 17) + Maven** on the backend, **plain HTML/CSS/JavaScript** on the
frontend (no build step, no framework - served directly by Spring Boot as static resources),
**PostgreSQL or MySQL** for the database, plus **Apache PDFBox** for the PDF-import automation
feature (see section 5).

---

## 1. Running it in IntelliJ IDEA

1. **Create the database** (pick one):

   PostgreSQL:
   ```sql
   CREATE DATABASE "dealflow360-db";
   ```
   MySQL:
   ```sql
   CREATE DATABASE `dealflow360-db`;
   ```

2. **Open the project**: `File -> Open...` and select the `dealflow360` folder (the one containing
   `pom.xml`). IntelliJ will detect it as a Maven project and download dependencies automatically.

3. **Configure the database connection** in `src/main/resources/application.properties`:
   - PostgreSQL is the default - just update `spring.datasource.username` / `password` if needed.
   - To use MySQL instead, comment out the PostgreSQL block and uncomment the MySQL block in that
     same file (both JDBC drivers are already in `pom.xml`, so no dependency changes are needed).

4. **Run it**: open `src/main/java/com/dealflow360/DealFlow360Application.java` and click the green
   Run arrow (or `mvn spring-boot:run` from a terminal). Hibernate creates all tables automatically
   (`spring.jpa.hibernate.ddl-auto=update`) and a `DataSeeder` populates demo data on first boot.

5. **Open the app**: go to `http://localhost:8080`. That single URL serves both the UI and the API
   (REST endpoints live under `/api/**`).

### Demo logins (seeded automatically)

| Role | Username | Password |
|---|---|---|
| Admin | `admin` | `admin123` |
| Sales Manager | `manager` | `manager123` |
| Finance | `finance` | `finance123` |
| Sales Rep | `rep1` | `rep123` |
| Sales Rep | `rep2` | `rep123` |
| Customer Portal - Acme Corp (Gold) | `acme` | `acme123` |
| Customer Portal - Beta Industries (Silver) | `beta` | `beta123` |
| Customer Portal - Bronze Traders (Bronze) | `bronze` | `bronze123` |
| Customer Portal - Delta Systems (Silver) | `delta` | `delta123` |
| Sales Reps (bulk demo set) | `rep3` … `rep8` | `rep123` |
| Customer Portal (bulk demo set, 60 accounts) | `cust01` … `cust60` | `cust123` |

A ready-to-use test file for the PDF-import feature is included at `sample-data/sample-customer-rfq.pdf`
(see section 5 / step 12 of the Quick Test Flow below).

---

## 2. Architecture (kept intentionally simple)

```
Browser (plain HTML/CSS/JS, served as static files)
        |  fetch() with "Authorization: Basic ..." on every call
        v
Spring Boot REST API  (/api/**)
        |
        |-- controller/   thin HTTP layer: routing, @PreAuthorize role checks, DTO in/out
        |-- service/       ALL business logic lives here (see table below), including
        |                  TrendService (analytics) and AutomationScheduler (background jobs)
        |-- repository/    Spring Data JPA interfaces (one per entity)
        |-- model/         JPA entities (18 tables)
        |-- dto/           request/response shapes - entities are never serialized directly,
        |                  so lazy-loading and password/cost leakage are avoided by construction
        |-- config/        Spring Security (HTTP Basic, stateless) + demo DataSeeder
        v
PostgreSQL / MySQL  (schema auto-created by Hibernate)
```

Authentication is a single mechanism for everyone: internal staff (`AppUser`) and customers
(`Customer.portalUsername` / `portalPassword`) are both resolved by one `UserDetailsService`, so the
whole app uses plain HTTP Basic auth with no sessions/cookies/CSRF plumbing. Role checks are done
with `@PreAuthorize` right on the controller methods, so it is easy to see exactly who can call what.

---

## 3. Where each PDF requirement lives

This is the map from the hackathon problem statement to the actual code, so it is quick to point to
during a demo or a judging Q&A.

| PDF section | Backend | Frontend |
|---|---|---|
| A1 - Authentication (login + sales-rep sign-up) | `SecurityConfig`, `CustomUserDetailsService`, `AuthController` (`/signup`), `GlobalExceptionHandler` | `index.html` (staff/portal tabs, "Create an account") |
| A2 - Product & Price List | `Product`, `ProductVariant`, `PriceListEntry` (tier pricing), `ProductController`, `PriceListController` | `admin-products.html` (catalog, variants, tier price lists) |
| A3 - Discount Tier & Approval Chain | `DiscountCeiling`, `ApprovalChainRule`, `DiscountRiskService` (blended risk score), `ApprovalService` (routing), `DiscountConfigController` | `admin-discounts.html` |
| A4 - Warehouse & Fulfillment | `Warehouse`, `StockLevel`, `FulfillmentService` (auto-split algorithm), `WarehouseController`, `FulfillmentController` | `admin-warehouses.html`, Fulfillment tab in `quotation-detail.html` |
| A5 - Subscription / Recurring Plan | `SubscriptionPlan`, `BillingScheduleEntry`, `BillingService` (schedule + proration + cancellation refund), `BillingController` | `admin-subscriptions.html`, Billing tab in `quotation-detail.html` |
| A6 / B5 - Live Product Recommendation Engine (cross-sell, upsell, product upgrade) | `UpsellRule` (+ type, priority, tag, reason, active), `RecommendationDismissal`, `UpsellService` (engine + ranking + rule admin), `PricingService`, `QuotationService.acceptRecommendation`, `UpsellRuleController`, `QuotationController` (`/recommendations`) | `admin-upsell.html` (rule editor), "Smart Recommendations" panel on the Cart tab of `quotation-detail.html` |
| A7 - Reporting & Dashboard config | `ReportService`, `DashboardController` | `reports.html` (period presets, XLS/CSV + PDF/print export) |
| B1/B2 - Sales workspace, quotation list/pipeline | `QuotationController` | `quotations.html` (list), `pipeline.html` (Kanban) |
| B3 - Quotation Builder (cart) | `QuotationService.addLine/updateLine/removeLine/applyOrderDiscount/reopenForRevision` | Cart tab in `quotation-detail.html` (qty +/-, line + order discounts, Submit / Confirm / Reopen) |
| B4 - Discount Approval Screen | `ApprovalController`, `ApprovalService`, `ApprovalLog` (audit trail) | Approval tab in `quotation-detail.html` |
| B5 - Upsell panel | `UpsellService.recommendFor` (ranked, explainable); `suggestFor` kept for the Copilot / Negotiation Assistant | Smart Recommendations panel in `quotation-detail.html` |
| B6 - Fulfillment & warehouse split | `FulfillmentService` (split, accept suggested split, manual override, backorder consolidation, mark delivered) | Fulfillment tab in `quotation-detail.html` |
| B7 - Subscription & Billing screen | `BillingService` (one-time invoice + recurring schedule, proration, cancellation credit, record payment, invoice status) | Billing tab in `quotation-detail.html` |
| B8 - Customer Portal Negotiation | `PortalController` (separate, `ROLE_CUSTOMER`-only, ownership-checked) | `portal.html`, `portal-quotation.html` |
| B9 - Deal Health & Anomaly Dashboard | `DealHealthService` (stalled deals, discount anomalies vs. rep history, delivery slippage) | `deal-health.html` |
| Section 10 - Blended Discount Risk Score | `DiscountRiskService.blendedRiskScore` (exact worked example reproduced in `DataSeeder`: Gold/Hardware=15%, Gold/Service=10%) | Risk meter on `quotation-detail.html` |

---

## 4. The "Quick Test Flow" from the PDF, mapped to this build

1. Log in as `rep1` (Sales Rep). Backend data (tiers, warehouses, subscription plan) is already
   seeded.
2. `Quotations -> New quotation` for **Acme Corp** (Gold tier), open it.
3. Add **Laptop Pro** (Hardware) with a discount higher than 15%, e.g. 20%.
4. Click **Submit for approval** - the Approval tab shows it was auto-routed (blended risk score > 0).
5. While building the quote, use the **Smart Recommendations** panel under the cart - accept the Setup Service upsell (or the Laptop Pro upgrade on a Laptop Basic line) and watch the total, margin and risk score update.
6. Log in as `manager` (Sales Manager) in another browser/incognito window, open the same quotation's
   **Approval** tab, and click **Approve**.
7. Back on the quotation, open **Fulfillment** - Laptop Pro (only 5 in Main Warehouse) automatically
   splits across Main Warehouse and East Depot (10 in stock) if you order more than 5.
8. Add a **Cloud Suite License** line as **Recurring**, confirm the quotation (internal Confirm button,
   or via the customer portal below) and check the **Billing** tab: the one-time lines appear as a single
   invoice (UNPAID) and the subscription as its own cycle schedule. As `finance`, click **Record payment**
   on the invoice and watch the invoice status move to PARTIALLY PAID / PAID.
9. Log in as `acme` (Customer Portal), open the same quotation, submit a counter-discount - the
   quotation returns to `UNDER_NEGOTIATION` and, on **Confirm Quotation**, automatically re-enters
   approval if the new terms exceed the threshold.
10. Log in as `manager` or `finance` and open **Deal Health** to see live stalled-deal, discount-
    anomaly and delivery-slippage alerts (backed by real data, not static numbers).
11. Open **Trends** and look at "Overall business" for the last 3 months, then switch the dimension to
    **A specific product** (Laptop Pro) - the numbers and the chart both update from the same real data.
12. On a **DRAFT** quotation's Cart tab, use **Import from PDF** with
    `sample-data/sample-customer-rfq.pdf` (included in this project) - it extracts 4 candidate lines
    (Laptop Pro qty 8 / 20%, Office Chair qty 15 / 10%, Setup & Installation Service qty 1, Onboarding
    Training qty 2 / 5%) from a real PDF instead of typing them in.
13. Open **Automation** and watch the activity feed - within a few minutes of the app running, the
    background jobs (stalled-deal nudges, backorder consolidation, low-stock flags) start logging
    entries there on their own, with no user action.

---

## 5. Added differentiators (beyond the PDF spec)

These three were added on top of the original problem statement - they are called out separately here
so it's clear in a demo/Q&A which parts are the PDF's required scope and which are the "make it
different from every other team's build" additions.

| Feature | Backend | Frontend |
|---|---|---|
| Trends & Analytics - "last N months of some particular thing, as a number and a graph" | `TrendService`, `TrendController` (`GET /api/reports/trends`) | `trends.html` - dimension picker (overall / product / category / sales rep), month window, KPIs, and a dependency-free inline SVG chart (`DF.renderChart` in `js/api.js` - no charting library, works fully offline) |
| Import quotation lines from a PDF instead of typing them in | `PdfImportService` (real text extraction via Apache PDFBox + a transparent regex heuristic to find product/qty/discount), `PdfImportController` (`/api/quotations/{id}/import/preview` then `/commit` - nothing is saved until the rep reviews and confirms) | "Import from PDF" panel on the Cart tab of `quotation-detail.html` |
| Self-governing background automation | `AutomationScheduler` (`@Scheduled`, interval set by `dealflow360.automation.interval-ms`) runs three jobs with no user action: auto-nudge stalled deals, auto-consolidate backorders against newly-arrived stock, and auto-flag low stock. Every automated action is written to the same `AuditEntry` trail as manual actions (prefixed `AUTO_`), so nothing runs silently. | `automation.html` - live activity feed (auto-refreshes), clearly badged "Automated" vs. manual actions |

**How the PDF import heuristic works** (so it's easy to explain to a judge): the uploaded PDF's text is
extracted with PDFBox, split into lines, and each line is checked against the product catalog for a
name match (longest name wins to avoid partial-match mistakes); quantity is pulled from patterns like
`qty: 3`, `x3`, `3x`, or `3 units` (defaulting to 1 if none is found); the discount is the first `NN%`
found on that line (defaulting to 0). The rep always sees and can edit every candidate line before
anything is added - the automation speeds up data entry, it does not silently commit it.

---

## 6. Deal Intelligence - the self-governing sales layer (added this round)

This is the upgrade from "a normal sales management system" to a system that also explains *why*
things are happening and *what to do next*. Every number, score, alert and alternative below is
computed from the same real data and the same existing calculators the app already had
(`DiscountRiskService`, `ApprovalService`, `FulfillmentService`, `UpsellService`) - nothing here is a
random or hardcoded "AI" message. No existing feature, model, endpoint or screen was removed or
rewritten to build this; it is additive.

### What already existed vs. what changed

| | |
|---|---|
| **Already existed and left intact** | Blended discount risk score, approval routing/chain, warehouse split + backorders, subscription billing/proration, upsell suggestions, customer portal negotiation, Deal Health's original 3 alert types (stalled deals, discount anomalies, delivery slippage), Trends/PDF-import/Automation. |
| **Existed but improved** | `ApprovalService` gained a pure `describeRequirement(score)` read (reused by every feature below instead of re-deriving approval logic); `DealHealthService` gained 3 more anomaly detectors and the per-deal 0-100 score, reusing its existing repositories; `Quotation.lines` fetch mode was corrected from an implicit LAZY (a latent bug that would have crashed every read-only view once `open-in-view=false` mattered) to an intentional EAGER, with a comment explaining why. |
| **Newly added** | Deal Copilot, What-If Simulator, Explainable Decision System, Deal Health Score + breakdown, Smart Negotiation Assistant, Warehouse Optimization Modes, Visual Deal Timeline, 3 new anomaly detectors, and the UI to show all of it inside the existing Quotation Builder/Dashboard rather than as separate pages. |

### New backend services (all in `service/`, business logic never lives in a controller)

| Service | Responsibility |
|---|---|
| `DealCopilotService` | Builds the Deal Copilot's Approval/Margin/Risk/Fulfillment/Recommendation/Upsell insights for the current quotation, and the "why" narrative behind approval routing. |
| `ScenarioService` | What-If Simulator: clones a quotation **in memory only** (Spring Data entities are already detached outside a `@Transactional` method, so mutating the clone can never be flushed to the database), applies hypothetical changes, and reuses the real risk/approval/fulfillment calculators to compare Current vs. Scenario. `apply()` commits by calling the same `QuotationService.addLine/updateLine/removeLine` the Cart tab uses. |
| `NegotiationAssistantService` | Given a customer's requested discount, calls `ScenarioService` three times (reduced discount / reduced discount + real upsell add-on / accept as requested) to produce genuine calculated alternatives - never invented numbers. |
| `FulfillmentOptimizationService` | Read-only preview of three warehouse strategies (Cheapest / Fastest / Fewest Shipments) over live stock - never writes to `StockLevel`, so it can never cause negative stock. The actual persisted split still goes through the existing `FulfillmentService`. |
| `TimelineService` | Merges the existing `AuditEntry`, `ApprovalLog` and `NegotiationMessage` tables into one chronological Deal Timeline - no separate/fake event log. |
| `DealHealthService.computeScore(...)` (added to the existing service) | Deterministic 0-100 score with a capped, explainable deduction per real factor (risk score, margin vs. target, inactivity, approval cycles/delay, negotiation rounds, backorders, multi-warehouse fulfillment, delivery slippage, discount anomaly). |

### New/extended API endpoints

All under the existing `/api/quotations/{id}/...` and `/api/quotations/{id}/fulfillment/...` namespaces, following the app's existing controller/DTO conventions (`IntelligenceDtos.java` holds the new DTOs, the same one-file-per-feature pattern as `QuotationDtos`/`DashboardDtos`):

| Method & path | Purpose |
|---|---|
| `GET /api/quotations/{id}/intelligence` | Deal Copilot insights (Feature 1) |
| `GET /api/quotations/{id}/approval-explanation` | "Why this approval decision?" (Feature 3) |
| `POST /api/quotations/{id}/scenario/simulate` | What-If Simulator preview - never persists (Feature 2) |
| `POST /api/quotations/{id}/scenario/apply` | Commits a simulated scenario via the real line-edit methods (Feature 2) |
| `GET /api/quotations/{id}/timeline` | Visual Deal Timeline (Feature 7) |
| `GET /api/quotations/{id}/health-score` | Deal Health Score + factor breakdown + recommended actions (Feature 4) |
| `GET /api/quotations/{id}/fulfillment/optimize` | Cheapest / Fastest / Fewest-Shipments previews (Feature 6) |
| `POST /api/quotations/{id}/negotiation/alternatives` | Smart Negotiation Assistant options (Feature 5) |

`GET /api/dashboard/deal-health` (already existed) now also returns `marginAnomalies`, `negotiationLoops`
and `approvalDelays` alongside the original `stalledDeals`/`discountAnomalies`/`deliverySlippages`
(Feature 8/9) - the response shape only grew, so nothing that already read the old fields broke.

### Database changes

**None.** Every score, insight, scenario and recommendation is computed on the fly from the existing
`quotation`, `quotation_line`, `stock_level`, `discount_ceiling`, `approval_chain_rule`, `approval_log`,
`negotiation_message`, `backorder` and `fulfillment_split` tables - no new entities or columns were
needed. (Hibernate's `ddl-auto=update` would have added anything required automatically; it simply had
nothing to add.)

### How to test each feature

1. **Deal Copilot** - open any quotation with lines and look at the "Deal Intelligence" card above the
   tabs. Change a line's discount and watch the insights, margin and score update immediately.
2. **What-If Simulator** - open a quotation, go to the **What-If Simulator** tab, pick a line, change its
   discount/quantity (or add a hypothetical product), click **Run scenario**: Current vs. Scenario are
   shown side by side (amount, discount, margin, risk score, approval level, shipments). The real
   quotation is untouched until you click **Apply Scenario**.
3. **Explainable Decision System** - Approval tab -> **Why this decision?** shows allowed vs. applied
   discount per line and the overage that drove the routing. Fulfillment tab -> each optimizer mode card
   explains its own recommendation. Upsell tab already showed its reasoning; it's unchanged.
4. **Deal Health Score** - visible on every quotation's Deal Intelligence card (0-100, HEALTHY/ATTENTION
   NEEDED/AT RISK); click it to expand the point-by-point factor breakdown and recommended actions.
5. **Smart Negotiation Assistant** - Negotiation tab -> pick a line, enter the customer's requested
   discount, click **Get alternatives** -> three real calculated options, each with an **Apply this
   option** button that goes through the normal line-edit endpoints (so approval is never bypassed).
6. **Warehouse Optimization Modes** - Fulfillment tab -> the three mode cards (Cheapest/Fastest/Fewest
   Shipments); the seeded 8-laptop Multi-Warehouse demo quotation (see below) shows all three genuinely
   disagreeing. **Accept** on a row applies it via the existing manual-override endpoint.
7. **Visual Deal Timeline** - Timeline tab on any quotation with history - automatically built from real
   audit/approval/negotiation events.
8. **Deal Anomaly Detection** - Deal Health dashboard (manager/finance/admin login) - Margin Anomalies,
   Negotiation Loops and Approval Delays tables, alongside the original three.
9. **Recommendation Explainability** - every insight/recommendation card in the app now carries its own
   short "why" line next to it; there is no unexplained score or suggestion anywhere in the build.
10. **Polished Deal Intelligence UI** - all of the above lives inside the existing Quotation Builder tabs
    and the existing Dashboard/Deal Health pages; no new pages, no redesign, same green/amber/red
    convention as the rest of the app.

### Demo data - ready on first boot, no manual setup

`DataSeeder` builds the following through the real `QuotationService` (so nothing is faked) every time
the database is empty, in addition to the original seed data:

- **3 historical confirmed deals** for `rep1` (so the anomaly detectors have a real baseline from the
  first login instead of showing nothing).
- **Scenario 1 - High Discount**: a 25% discount on a Service line (Gold ceiling 10%) - open as `rep1`
  and you'll find it already `PENDING_APPROVAL` needing Manager + Finance.
- **Scenario 2 - Healthy Deal**: a Draft quotation within every ceiling - submit it live to see it skip
  approval entirely.
- **Scenario 3 - Multi-Warehouse Order**: 8 Laptop Pro units (Main Warehouse only has 5) - already split
  across Main Warehouse + East Depot; open its Fulfillment tab and compare the three optimizer modes.
- **Scenario 4 - Customer Negotiation**: an approved Beta Industries deal with a live customer
  counter-discount thread, ready for the Negotiation Assistant.
- **Scenario 5 - Stalled Deal**: a Bronze Traders draft backdated 6 days - shows up on Deal Health
  immediately.
- **Scenario 6 - Discount Anomaly**: 15% off (within policy) but far above `rep1`'s own ~5.3% average.
- **Bonus - Margin Anomaly**: a low-discount Laptop deal whose margin % is still far below the rep's
  (chair-heavy) historical average.
- **Bonus - Negotiation Loop**: three customer counter-discount rounds on one line.
- **Bonus - Approval Delay**: a Manager-approval request backdated 4 days.

### Assumptions made

- "Blended risk score" and every approval/ceiling rule were treated as the single source of truth for
  "why" an approval is required - no second, competing risk model was introduced.
- Where the PDF's feature list implied a capability the codebase already has under a different name
  (e.g. "Recommendation Explainability" vs. the existing `UpsellService` reasoning), the existing
  mechanism was extended with a narrative rather than duplicated.
- The Deal Health Score's point weights (25/20/15/15/10/12/8/3/10/15) are a reasonable, documented,
  deterministic allocation designed to keep every deduction traceable to one real factor; they are not
  taken from an external scoring standard, since none was specified.
- The What-If Simulator's "detached entity" non-persistence guarantee depends on
  `spring.jpa.open-in-view=false` and Spring Data's per-call transaction boundary (already the project's
  configuration) - if that configuration ever changes, `ScenarioService` would need to clone explicitly
  before mutating rather than relying on entities already being detached.

---

## 7. Customer Portal e-commerce redesign + self-service requests (added this round)

The Customer Portal was originally a plain table of quotations with no way for a customer to act on
anything except negotiate a discount a sales rep had already offered. Two problems were fixed together:

- **The portal looked like a spreadsheet, not a storefront.** `portal.html` and `portal-quotation.html`
  were rebuilt as an actual e-commerce-style experience: order cards with product thumbnails instead of
  table rows, a progress stepper (Order Placed -> Seller Review -> Confirmed, with a dedicated rejected
  state), strikethrough pricing when a discount applies, chat-bubble negotiation thread, and KPI cards
  (total orders / in progress / confirmed value). Every product now carries an `imageUrl` (falls back to
  a clean category icon - hardware/service/subscription - when unset, rather than a mismatched stock
  photo), surfaced via a new shared `DF.productThumb()` helper everywhere a product appears: the portal,
  the Quotation Builder cart, upsell suggestions, and the admin catalog.
- **There was no way for a customer to actually give a list of what they want.** Previously a customer
  could only view and react to a quotation a sales rep had already built by hand - there was no "shop and
  request" path at all. A new **Browse & Request** page (`portal-catalog.html`) now lets a customer browse
  the live product catalog by category, add items and quantities to a running list (client-side, like a
  cart), attach an optional note, and submit it in one click.

### New backend surface (`PortalController`, still scoped to `ROLE_CUSTOMER` and the caller's own account)

| Method & path | Purpose |
|---|---|
| `GET /api/portal/products` | Customer-safe product catalog (name/category/unit/price/tax/description/image - no cost or margin, which stay internal-only). |
| `POST /api/portal/quotations` | Submits `{ items: [{productId, quantity}], note }`, creates a real `Quotation` via the existing `QuotationService.createQuotation`/`addLine` (the same methods the sales-rep Quotation Builder uses - no parallel pricing logic), and leaves it in `DRAFT` for a sales rep to review, adjust and submit through the normal approval flow. An optional note is posted to the existing negotiation thread as a `COMMENT` from the customer. |

Rep assignment: a returning customer's request goes to whichever sales rep last handled one of their
deals (continuity); a brand-new customer's first request goes to whichever active Sales Rep currently has
the fewest open (non-`CONFIRMED`/`REJECTED`) quotations, so requests don't pile up on one person. This is
plain Java in the controller, not a new service, since it is a small piece of routing logic with no
reuse potential elsewhere.

### Database changes

One additive column: `Product.imageUrl` (nullable `varchar`, optional catalog photo URL). Everything
else - the self-service request flow, the catalog, the redesigned portal - is built entirely from
existing tables and existing `QuotationService` methods.

### How to test

1. Log in as a customer with no open orders (`delta` / `delta123` already has one seeded, so use a fresh
   customer or delete `Delta Systems`' `selfServiceDeal` from the admin `Quotations` screen if you want to
   see the truly-empty state) and click **Browse & Request** in the nav (or the **+ New request** button
   on My Orders).
2. Pick a category pill, add a couple of products with quantities to the list on the right, optionally
   leave a note, click **Submit request**.
3. You're taken straight to the new order's detail page (`DRAFT` / "Being prepared"). Log in as the
   assigned sales rep (shown on the order) - the request appears in their normal Quotation Builder queue
   like any other quotation, with the customer's note already in the negotiation thread - and can be
   priced, discounted and submitted for approval exactly as before.
4. The seeded `delta` / `delta123` account already has one such request waiting (`Wireless Mouse` x10,
   `27-inch Monitor` x3, `Docking Station` x3, assigned to `rep2`) so the flow is visible immediately
   without creating one first.

### Assumptions made

- A self-service request is priced at full list price with no discount pre-applied - a customer isn't in
  a position to grant themselves a discount, so any negotiation still goes through the same
  ceiling/approval logic as a rep-built quotation.
- Subscription (recurring) products are intentionally left out of the self-service list builder for now:
  a recurring line requires picking a specific `SubscriptionPlan` (billing cycle), which is a sales
  conversation the existing Quotation Builder already handles well; the customer can still request a
  subscription via the note field or by asking their rep directly.
- No customer had an "assigned rep" concept before this change, so the least-open-deals rule is a
  reasonable default for a brand-new customer's first request rather than a random pick; it never
  reassigns a returning customer away from the rep they already have a relationship with.

---

## 8. Critical fix - the approval chain could be bypassed (fixed this round)

**Reported symptom:** when a line's discount was over its ceiling, the deal was not going to the
Sales Manager - a sales rep could push it through ("correct it") themselves.

**Root cause, found by tracing the actual code (not by guessing):** `QuotationService.confirmQuotation`
only checked the quotation's *status* (`APPROVED` or `UNDER_NEGOTIATION`) before locking it in - it
never re-checked whether the *current* discount still needed approval. `UNDER_NEGOTIATION` turned out
to be reachable without ever going through Manager/Finance approval at all: `addNegotiationMessage`
flipped a quotation to `UNDER_NEGOTIATION` on **any** message - even a plain internal comment from the
rep - regardless of its current status. So the exploit was simply: add an over-ceiling discount line,
leave any comment on the thread (status becomes `UNDER_NEGOTIATION`), then call the internal
"Confirm" endpoint - which the status check happily allowed, with no Manager ever involved. The same
hole let an already-approved deal be confirmed after a customer's counter-discount pushed it back
over ceiling post-approval, since nothing reset the approval state when a discount changed later.

**The fix (three changes, all in `QuotationService`/`ApprovalService`/`Quotation`):**

1. A new `Quotation.approvedRiskScore` field records the blended risk score that was actually cleared
   the last time the deal reached `APPROVED` (set by `ApprovalService` whenever that happens, whether
   automatically because the deal was clean or after a Manager/Finance sign-off).
2. `confirmQuotation` now recomputes the *current* blended risk score and compares it against
   `approvedRiskScore` right before locking the deal in. If the current discount is riskier than what
   was actually cleared, it re-routes the deal through the real approval chain (`Manager`/`Finance`)
   instead of confirming, and returns a clear error explaining why. A deal whose discount is the same
   or has gone down since its last real approval still confirms immediately - this never asks for a
   redundant second approval.
3. `addNegotiationMessage` no longer moves a quotation to `UNDER_NEGOTIATION` from `PENDING_APPROVAL`
   (a comment can no longer pull a deal out of the Manager's queue mid-review) or from `REJECTED` (a
   comment can no longer quietly resurrect a rejected deal); and the customer-portal confirm
   (`portalConfirm`) now explicitly refuses to confirm a `REJECTED` quotation at all.

A related gap found during the same audit: `addLine`/`updateLine`/`removeLine` had no status check at
all, so a line's price/discount/quantity could still be edited through the API after submission,
approval, or even after `CONFIRMED` (when fulfillment/billing had already been generated from the old
numbers) - the UI only *hid* the edit controls outside `DRAFT`. All three now reject editing a
quotation that isn't `DRAFT`, matching what the UI already assumed.

**How this was verified:** compiled the whole backend clean, then wrote a standalone reproduction
(`ApprovalBugVerify.java`, using the real `DiscountRiskService`/`ApprovalService` classes with
in-memory fake repositories - not reimplemented logic) that: (a) reproduces the exact reported exploit
and confirms it is now refused and correctly routed to Manager+Finance instead; (b) walks a deal
through real Manager then Finance approval and confirms a legitimately-approved deal still confirms
immediately with no redundant re-approval; (c) reproduces the "renegotiated after approval" variant
(discount grows further after being approved) and confirms that is also caught. All checks passed; a
control run against the old status-only guard confirmed it really would have let the exploit through,
so the test is a meaningful regression check, not a vacuous one.

**How to see it live:** the seeded Scenario 4 (Beta Industries, see section 6) already ends with a
customer counter-discount of 15% on a Silver/Hardware line whose ceiling is 10% - log in as `rep1`,
open that quotation, and try the internal Confirm button: it now correctly refuses and sends the deal
back to `PENDING_APPROVAL` for the Sales Manager, instead of confirming.

---

## 9. Follow-up fixes - editing and confirming self-service requests (fixed this round)

Fixing the approval bypass in section 8 surfaced two real regressions in how a customer's self-service
"Browse & Request" order behaved, both reported directly from testing:

**"We can't delete/edit a line on a request that came from the customer."** The very first fix in
section 8 blocked `addLine`/`updateLine`/`removeLine` on anything that wasn't `DRAFT` - reasonable in
isolation, but a self-service request moves to `UNDER_NEGOTIATION` the instant its optional note is
posted (any negotiation message does that from `DRAFT`), so it left every self-service request with a
note permanently uneditable by the assigned rep - the "Remove"/quantity/discount controls in the
Quotation Builder never even rendered, because they were (and always had been) gated to `status ===
"DRAFT"` only. Fixed by widening editability to `DRAFT` **or** `UNDER_NEGOTIATION` in three places that
all have to agree: `QuotationService.requireEditable` (the actual backend rule), and the Quotation
Builder's edit controls and "Submit" button in `quotation-detail.html` (previously DRAFT-only, so even
if the backend allowed it there was no button to trigger it). This does not reopen the section 8 fix -
`confirmQuotation` still re-validates the risk score at confirm time no matter how a line changed.

**"Order gets confirmed itself just by the customer clicking it."** A self-service request's lines
start at 0% discount, which is always "clean" - so with no other check, the very first click of
"Confirm Order" on a brand-new, never-priced request would go straight through to `CONFIRMED` without
any sales rep ever having looked at it. The missing rule: reaching `APPROVED`/`UNDER_NEGOTIATION`
should not be enough to confirm - the deal must have actually been *submitted* at least once.
`Quotation.currentApprovalStep` starts at `NONE` and only ever moves off it via
`ApprovalService.evaluateAndRoute` (i.e. a real submit), so `confirmQuotation` and `portalConfirm` now
both refuse to confirm while it's still `NONE`, with a message telling the customer their rep hasn't
reviewed the order yet. The portal UI reflects this too: the Confirm button and hint are hidden (with a
"your sales rep hasn't reviewed this yet" notice shown instead) until the order has actually been
submitted, and the order list shows "Awaiting review" instead of "Negotiation in progress" for that
specific state so it doesn't read as if a back-and-forth is already happening.

**Verified with a standalone reproduction** (`SelfServiceFlowVerify.java`, same approach as section 8 -
real `Quotation`/`ApprovalService`/`DiscountRiskService` classes, the service-layer branch conditions
mirrored by hand since wiring the full `QuotationService` needs ~12 repositories): built a self-service
request exactly the way `PortalController.requestQuote` does, posted its note, confirmed the line was
still editable and removable afterward, confirmed the customer could not confirm it yet, then ran it
through a real `evaluateAndRoute` submit and confirmed it became confirmable only after that. 9/9
checks passed. Re-ran the section 8 exploit test (`ApprovalBugVerify.java`) afterward too, unchanged -
10/10 still pass, so this didn't reopen that fix.

---

## 10. Critical fix - schema migration crashed the whole app on a real database (fixed this round)

Running the build against a real, already-populated PostgreSQL database (not a fresh empty one) failed
to even start up cleanly, and once it "started" every quotation-related screen was broken:

```
ERROR: column "approved_risk_score" of relation "quotation" contains null values
Caused by: alter table if exists quotation add column approved_risk_score numeric(6,2) not null
...
ERROR: column q1_0.approved_risk_score does not exist
```

**Root cause:** the new `Quotation.approvedRiskScore` field added for the section 8 fix was declared
`@Column(nullable = false, ...)`. With `spring.jpa.hibernate.ddl-auto=update`, Hibernate migrates an
*existing* table by generating a plain `ALTER TABLE quotation ADD COLUMN approved_risk_score numeric(6,2)
NOT NULL` - no `DEFAULT` clause. Postgres refuses that outright on a table that already has rows, because
it would have no value to backfill them with, so the `ALTER TABLE` fails and the column never actually
gets created. Hibernate doesn't know the migration failed, though, so every subsequent `SELECT` it
generates still references `approved_risk_score` - and since the column genuinely doesn't exist in the
database, *every single query touching `quotation`* then fails with "column does not exist," which is
why `DealHealthService.compute`, `AutomationScheduler.autoConsolidateBackorders`, and
`PortalController.myQuotations`/`QuotationService.listAll` all broke at once. This only shows up against
a database that already has quotation rows in it - a fresh empty database has nothing to fail to
backfill, which is why it wasn't caught earlier.

**Fix:** made `approvedRiskScore` nullable at the database level (dropped `nullable = false` from its
`@Column`) so the `ALTER TABLE` succeeds trivially - existing rows simply get `NULL` in the new column,
which Postgres always allows. `Quotation.getApprovedRiskScore()` now treats a `null` the same as
`BigDecimal.ZERO` (`return approvedRiskScore != null ? approvedRiskScore : BigDecimal.ZERO;`), so every
comparison against it elsewhere (`QuotationService.confirmQuotation`'s re-check, `portalConfirm`) keeps
working correctly for old rows exactly as if they'd been approved at a clean 0.00 score - which is the
correct assumption for any quotation that predates this field existing at all. Double-checked that no
other newly-added column this round has the same problem: `approvedRiskScore` was the only new
`nullable = false` column on a table (`quotation`) that can already hold rows; every other `nullable =
false` field across `Quotation`, `Product`, and `Customer` is part of the original schema.

**Verified with a standalone reproduction** (`LegacyRowNullSafetyVerify.java`): used reflection to set
`approvedRiskScore` to `null` on a real `Quotation` instance - exactly what hydrating a legacy row from
the database now looks like - and confirmed `getApprovedRiskScore()` neither throws nor misbehaves, and
that the confirm-time comparisons that read it produce the same result they would for an explicit `ZERO`.
4/4 checks passed. Also re-ran `ApprovalBugVerify.java` (10/10) and `SelfServiceFlowVerify.java` (9/9)
unchanged, since both construct quotations with an explicit score and so were never exposed to this bug,
but re-confirming they still pass rules out any regression from the getter's new null-check.

**If you already ran the previous build against your database:** the failed migration attempt doesn't
leave anything broken behind - Postgres rolled back the failed `ALTER TABLE`, so the `quotation` table is
exactly as it was before. Just drop in this build and start the app again; the (now nullable) column will
be added cleanly on the next startup.

---

## 11. Full audit against the PDF - fixes and completed features (this round)

Every screen and service was walked against the PDF's key points (A1-A7, B1-B9, the End-to-End Flow,
and the eight-step Quick Test Flow). Below is what was broken and what was missing, and what changed.

### 11.1 Bugs fixed

**Every error came back as "Request failed (400)" - the real reason never reached the screen.** Spring
Boot hides the reason text of a `ResponseStatusException` by default (`server.error.include-message=never`),
so every rule the services enforce - "hasn't been submitted yet", "discount exceeds policy", "only
Finance can act on this step" - showed up as a bare status code, which made a working rule look like a
bug. A `GlobalExceptionHandler` (`config/GlobalExceptionHandler.java`) now returns the same
`{status, error, message}` JSON for every failure, role denials come back as a readable 403, and
deleting a product/warehouse that a quotation still references answers "still referenced" (409) instead
of a raw database 500. Mistyping a password also no longer pops up the browser's own native login
dialog: the Basic-auth challenge header was replaced by a JSON 401 (`SecurityConfig`).

**"No option to submit our order after the discount" - the rejection dead end.** After a Manager
rejected a quotation, nothing could move it: the rep could not edit its lines (only DRAFT /
UNDER_NEGOTIATION are editable), the "Submit" button was hidden, the customer's counter-offer changed
the line but left the status REJECTED, and no one had any submit left. Three ways forward now exist:
`POST /api/quotations/{id}/reopen` ("Reopen for revision" button - REJECTED or APPROVED-but-unconfirmed
back to DRAFT, the old decision logged as `REOPENED`, reserved stock released); "Re-submit for approval"
directly from REJECTED; and a customer counter-discount on a REJECTED deal now reopens it as
UNDER_NEGOTIATION so the rep can submit the lower terms.

**A customer could change the terms of a deal that was under review or already confirmed.** A
counter-discount rewrote the line's discount in ANY status - while PENDING_APPROVAL the numbers moved
under the Manager mid-review (what got approved wasn't what was submitted), and on a CONFIRMED order
the price of an already-invoiced, already-shipping order changed. Counters are now refused in those two
states with a clear message (comments still post); the portal hides the counter form while approvers are
deciding and shows a "with the seller's approvers" banner instead.

**Confirming an already-approved over-ceiling deal from the portal sent it round the approval chain
again.** `portalConfirm` re-entered approval whenever the risk score was above zero - even when the
Manager/Finance had just approved exactly those terms - so a deal approved at 8 points could never be
confirmed by the customer. It now uses the same test as the internal confirm: has the discount grown
past what was cleared (`approvedRiskScore`)? If not, it confirms; if so, it re-enters approval. Confirming
while PENDING_APPROVAL (which reset a half-finished Manager -> Finance chain back to the Manager step) and
re-confirming a CONFIRMED order are refused.

**A Manager could approve a REJECTED quotation.** `ApprovalController` only checked the approval
*step* (which still read MANAGER after a rejection), never the status. Approve/reject/return now require
`PENDING_APPROVAL`.

**Submit had no status guard.** Re-submitting while PENDING_APPROVAL reset the chain; submitting an
APPROVED or CONFIRMED deal re-ran routing and re-generated fulfillment. Submit is allowed from DRAFT,
UNDER_NEGOTIATION and REJECTED only.

**Regenerating the warehouse split stacked duplicate backorders.** `generateSuggestedSplit` cleared the
old auto splits but not the backorders they had created, so submit -> approve -> confirm (three
regenerations) left three "N units pending" rows for one shortfall - which also inflated the automated
backorder-consolidation job. Unresolved backorders for recomputed products are now cleared first.

**Quote and billing disagreed on subscription lines.** A recurring line was priced at the product's
catalog price while its schedule billed the plan's price per cycle, so a quarterly/yearly plan quoted one
number and invoiced another. The line now carries the plan's per-cycle price, and a plan that belongs
to a different product is refused.

**Smaller ones:** discount must be 0-100 (a 150% "discount" produced a negative line total); billing
modify/cancel only after CONFIRMED (they used to change a draft's quantity through a side door that
skipped every editing rule) and a modified quantity must be >= 1; "Apply Scenario" and the Negotiation
Assistant's "Apply this option" were hidden on UNDER_NEGOTIATION deals even though the backend allows
it; rejecting or reopening a deal now releases the stock its approval had reserved; the login page's
demo-credentials table was missing `rep2` and `delta`.

### 11.2 PDF features that were missing, now implemented

| PDF | Feature | Where |
|---|---|---|
| A1 | Internal user sign-up ("Sales rep signs up (first time)") | `POST /api/auth/signup` (public; always creates a Sales Rep - Manager/Finance/Admin are granted by an Admin), "Create an account" on the login page |
| A2 | Price lists - customer tier based pricing | `PriceListEntry` entity, `/api/config/price-lists`, "Tier price lists" card on Backend Setup > Products; `QuotationService.addLine` and the customer catalog use the tier price, falling back to the catalog price |
| A2 | Variants - add from the UI (only listing existed) | "+ Variant" on the product row |
| A5 | Quarterly and yearly plans in the seed data (only monthly existed) | `DataSeeder` |
| A7 | Export options PDF / XLS; Period presets (today / week / month) | Reports: "Export XLS (CSV)" (opens in Excel), "Export PDF (print)", period buttons |
| B1/B2 | Pipeline - Kanban style deal view, cards showing customer, amount and stage; "Reload Data" | New `pipeline.html` (+ nav link); Reload buttons on Pipeline and Quotations |
| B3 | Order-level discount; quantity +/- | "Order-level discount %" on the Cart tab (`PUT /api/quotations/{id}/order-discount`); +/- buttons per line |
| B4 | Approval steps list - "Sales Manager, and Finance (only shown when required)" | Step tracker on the Approval tab, driven by the real approval-chain lookup (`requiresManager` / `requiresFinance` in the quotation response) |
| B6 | "Accept Suggested Split" button; mark a shipment delivered | `POST .../fulfillment/accept` (Finance/Admin, recorded on the quotation + audit trail); `POST .../fulfillment/splits/{id}/delivered` (clears the Deal Health delivery-slippage alert) |
| B7 / flow | One-time invoice alongside the recurring schedule; "record a payment, check the invoice status updates" | Confirmation now issues a `ONE_TIME_INVOICE` (BILLED, due) for the one-time lines and the cycle schedule for recurring lines; `GET .../billing/summary` (one-time / paid / outstanding / invoice status NOT_INVOICED, UNPAID, PARTIALLY_PAID, PAID); `POST .../billing/pay` (one entry or everything due, with a reference); Billing tab redesigned around the two groups |
| B8 | "Submit Request" button; honest status text | Portal counter button renamed "Submit request", with an explanation of what happens next; status badge reads "Awaiting review" / "Awaiting seller approval" / "Not approved" rather than raw enum names |
| everywhere | A plain-language "what happens next" line on the Quotation Builder for every status, so a missing button is never a mystery | `statusHint` on the Cart tab |

New demo data: Scenario 8 (a CONFIRMED hybrid order - laptops + setup service + Cloud Suite
subscription - with its one-time invoice already paid and the first subscription cycle still due, so the
Billing tab opens on a real PARTIALLY_PAID order) and Scenario 9 (a REJECTED Bronze deal, for
demonstrating "Reopen for revision" and the customer's lower counter-offer).

### 11.3 Database changes (all safe against an existing populated database)

New table `price_list_entry`. New nullable columns: `quotation.fulfillment_accepted_at`,
`quotation.fulfillment_accepted_by`, `billing_schedule_entry.paid_at`,
`billing_schedule_entry.payment_reference`. New enum values `PAID` (status) and `ONE_TIME_INVOICE` (entry
type) on `billing_schedule_entry`, and `REOPENED` on `approval_log`. Nothing is NOT NULL (see section 10
for why), so `ddl-auto=update` adds them cleanly to a database that already has rows.

### 11.4 Verified with a full in-memory run of the real services

`LifecycleVerify.java` wires the REAL `QuotationService`, `ApprovalService`, `FulfillmentService`,
`BillingService`, `DiscountRiskService`, `UpsellService` and `AuditService` to in-memory repositories
(no mirrored conditions this time) and drives 44 checks through them: tier pricing and discount
validation; submit -> auto-approve -> confirm with a two-warehouse split and exactly one backorder; the
one-time invoice + three-cycle schedule, UNPAID -> PARTIALLY_PAID -> PAID as payments are recorded; the
rejection dead end and both ways back (customer counter, rep reopen); counters refused while
PENDING_APPROVAL / CONFIRMED; portal confirm refused while PENDING_APPROVAL / REJECTED / CONFIRMED; an
approved deal whose customer counters past the ceiling being re-routed on confirm and then confirmed
after Manager approval; reserved stock released on reopen. 44/44 pass. The earlier harnesses
(`ApprovalBugVerify` 10/10, `SelfServiceFlowVerify` 9/9, `LegacyRowNullSafetyVerify` 4/4) still pass
unchanged, and every HTML page passes the tag-balance / duplicate-id / JavaScript-syntax check. All four
harnesses are included under `verification/` (outside `src/`, so they don't affect the Maven build) with
their own README on how to run them.

---

## 12. Live Product Recommendation Engine + subscription fix (this round)

### 12.1 What was built

The old Upsell tab (a flat "promoted first, then margin" list of pairings) is now a **Live Product
Recommendation Engine** with three recommendation types, explainable ranking, admin-configurable rules,
accept/dismiss actions, and full quotation integration. It lives inside the existing `UpsellRule` /
`UpsellService` / `UpsellRuleController` trio rather than a parallel module.

| Type | Meaning | Accepting it does |
|---|---|---|
| **CROSS_SELL** | *Existing product + related product* (Laptop -> Mouse -> Laptop Bag -> Keyboard -> Extended Warranty) | **Adds** the recommended product (qty 1, 0% discount, tier price); the original line stays |
| **UPSELL** | *A higher-value / premium offering alongside* what is there (Cloud Suite -> Analytics Add-on; Laptop -> Setup Service) | **Adds** it; if it is a pricier product of the same category the card also offers an in-place **Upgrade** |
| **PRODUCT_UPGRADE** | *Existing product -> premium version of the same thing* (10 x Laptop Basic -> 10 x Laptop Pro) | **Replaces** the source line's product, **preserving quantity and discount**; "Add both" keeps the original and adds the upgrade instead |

Nothing is ever applied automatically - every card is an explicit click by the Sales Rep, and every card
can be dismissed (persisted per quotation, restorable).

### 12.2 The complete recommendation flow

1. **Admin configures rules** (Backend Setup > Recommendation Rules): source product, recommended product,
   type, priority 0-100, promotion tag, minimum margin threshold, promoted flag, reason/benefits, active.
   Validation: a product cannot recommend itself; a PRODUCT_UPGRADE must point at a higher-priced product;
   priority and threshold ranges; no duplicate (source, target, type). Rules can be edited, deactivated
   (soft), re-activated, or deleted.
2. **The Sales Rep builds a quotation.** Every change to the cart - add, remove, quantity, discount, an
   accepted recommendation - goes through `refresh()` in the Quotation Builder, which reloads
   `GET /api/quotations/{id}/recommendations`, so the panel is live.
3. **`UpsellService.recommendFor(quotation)`** walks every cart line, loads the active rules for that
   line's product and builds a card for each, computing every figure from real data:
   - CROSS_SELL / UPSELL: price = customer's **tier price** (`PricingService.priceFor`, or the product's
     default plan price for a subscription), price impact = that price x 1, margin impact = (price - cost).
   - PRODUCT_UPGRADE: new unit price = tier price of the upgrade (or the plan with the **same billing
     cycle** for a recurring line); price impact = new line total - current line total at the line's
     **actual quantity and discount**; margin impact = new line margin - current line margin; plus
     `wouldNeedApproval` when the line's discount exceeds the upgrade's category ceiling.
   - Filters: inactive rule, inactive product, product already on the quotation, recommended margin %
     below the rule's threshold, an "upgrade" that is not more expensive for this customer, a recurring
     line "upgrading" to a product with no plan, and anything the rep dismissed on this quotation.
   - De-duplication: the same product recommended from several lines keeps the strongest card.
4. **Smart ranking** - additive and written into `scoreBreakdown` on each card: configured priority (0-100)
   + 20 if promoted + up to 30 from the recommended product's margin % + 8 if it adds margin (-15 if it
   loses margin) + 5 per extra cart line pointing at it (relationship strength, max 15) + tier affinity
   (Gold +8 / Silver +4 on premium recommendations) + type (upgrade +5, upsell +3) - 10 if it would push the
   deal into approval. Ties break on margin impact.
5. **The rep acts** on a card: *Add to quote*, *Upgrade product*, *Add both*, or *Dismiss*.
6. **`QuotationService.acceptRecommendation`** re-validates (quotation editable, rule active, product
   active, source product still on the quotation, not already added), then either calls the existing
   `addLine` (ADD / ADD_BOTH) or replaces the product on the source line in place (UPGRADE), re-deriving
   the unit price exactly as `addLine` would. It then recomputes the blended risk score
   (`DiscountRiskService`), looks up the resulting approval requirement (`ApprovalService.describeRequirement`),
   saves, clears any earlier dismissal of that rule, and writes an audit entry such as
   `Sales Rep accepted PRODUCT_UPGRADE recommendation: Laptop Basic -> Laptop Pro (10 unit(s) replaced in
   place, quantity and discount preserved). Total 8075.00 -> 10925.00, margin ... , risk score 0.00 (No
   approval required)`. Because the Deal Timeline is built from the audit trail, the event appears there
   automatically.
7. **The UI refreshes** lines, totals, margin, the risk meter, the status hint / approval requirement and
   the recommendation panel, and shows a one-line summary of what changed.

### 12.3 New / changed APIs

| Method & path | Roles | Purpose |
|---|---|---|
| `GET /api/quotations/{id}/recommendations?type=` | internal | Ranked live recommendations (optionally one type). Returns `recommendations[]` + counts + current total/margin. Each item: `recommendationId`, `ruleId`, `type`, `sourceLineId`, `sourceProduct`, `recommendedProduct`/`productName`, `currentPrice`, `price`, `quantitySuggestion`, `priceImpact`, `marginImpact`, `marginPercent`, `promotionTag`, `priorityScore`, `scoreBreakdown`, `reason`, `actions[]`, `wouldNeedApproval` |
| `POST /api/quotations/{id}/recommendations/accept` | Admin, Sales Rep | Body `{ruleId, sourceLineId?, mode: ADD | ADD_BOTH | UPGRADE, quantity?}`. Returns the updated quotation |
| `POST /api/quotations/{id}/recommendations/dismiss` | Admin, Sales Rep | Body `{ruleId, sourceLineId?}`. Persists the dismissal for this quotation; returns the refreshed panel |
| `POST /api/quotations/{id}/recommendations/restore` | Admin, Sales Rep | Un-dismisses everything on this quotation |
| `GET /api/upsell-rules`, `GET /{id}` | internal | Rules as DTOs (with a `warning` when a rule can never fire) |
| `POST /api/upsell-rules` | Admin, Sales Manager | Create rule (DTO: `baseProductId, suggestedProductId, recommendationType, priority, active, promotionTag, minMarginThreshold, promoted, reason`) |
| `PUT /api/upsell-rules/{id}` | Admin, Sales Manager | Update rule |
| `POST /api/upsell-rules/{id}/deactivate` / `/activate` | Admin, Sales Manager | Soft switch |
| `DELETE /api/upsell-rules/{id}` | Admin | Delete |
| `GET /api/quotations/{id}/upsell-suggestions` | internal | **Unchanged** - still served by `suggestFor` (now also honours the active flags) |

### 12.4 Data model changes (all migrate cleanly on an existing database)

- `upsell_rule`: new nullable columns `recommendation_type` (defaults to CROSS_SELL when null), `priority`
  (50), `active` (true), `promotion_tag`, `reason`, `created_at`, `updated_at`. Existing pairings keep
  working as cross-sells exactly as before.
- `product`: new nullable `active` (null = active). Inactive products stay on old quotations but are hidden
  from the Quotation Builder, the customer catalog and the engine; toggle on Backend Setup > Products.
- New table `recommendation_dismissal` (quotation, rule, optional source line, who, when).
- No new NOT NULL columns anywhere (see section 10).

### 12.5 Integration with DiscountRiskService and ApprovalService

- Every card that changes a discounted line (PRODUCT_UPGRADE) asks `DiscountRiskService.ceilingFor` whether
  the line's current discount would exceed the **upgrade's** category ceiling, and shows a warning
  (and loses 10 rank points) if so - so a rep is never surprised by an approval after an upgrade.
- After an accepted recommendation, `DiscountRiskService.blendedRiskScore` is recomputed and stored on the
  quotation, and `ApprovalService.describeRequirement` reports the resulting requirement in the audit
  entry and in the quotation response (`approvalRequirementLabel`, `needsReapproval`). The approval chain
  itself is not run at accept time: recommendations are accepted while the quotation is DRAFT or
  UNDER_NEGOTIATION, and the very next Submit (or Confirm, which re-checks the score against what was
  approved) routes it through Manager/Finance on the new numbers. Nothing bypasses section 8.

### 12.6 Subscription fix ("when we choose subscription there is no option coming")

Choosing a subscription product in the Quotation Builder left the line type on "One-time" and the plan
picker hidden - no plan options ever appeared and the product was quoted as a one-off at the catalog
price; the customer catalog could not choose a plan at all. Now: the selected product drives the line
type - a product with plans switches to *Recurring* and lists **its own** plans (cycle and price per
cycle, with a summary of what gets billed); a product without plans stays one-time and the Recurring
option is disabled with a hint. The backend (`QuotationService.addLine`) enforces the same rule: any
product that has plans is always a RECURRING line, using the product's default plan (monthly, else
cheapest) when none is chosen, and a plan belonging to another product is refused. The customer catalog
shows a plan selector on subscription products and sends the chosen plan with the request. Seed data now
includes quarterly/yearly Cloud Suite plans, Cloud Suite Pro, Laptop Basic, Laptop Bag, Mechanical
Keyboard, 17 typed recommendation rules, and Scenario 10 (10 x Laptop Basic + Cloud Suite, in Draft) to
demo the panel. **Note:** `DataSeeder` only runs on an empty database - to see the new demo products and
rules on an existing database, either drop and recreate it or add the products/rules from Backend Setup.

### 12.7 Files

New: `service/PricingService.java`, `model/RecommendationDismissal.java`,
`repository/RecommendationDismissalRepository.java`, `dto/RecommendationDtos.java`,
`verification/RecommendationVerify.java`.
Modified: `model/UpsellRule.java`, `model/Product.java`, `service/UpsellService.java`,
`service/QuotationService.java`, `controller/UpsellRuleController.java`, `controller/QuotationController.java`,
`controller/PortalController.java`, `controller/ProductController.java`, `repository/UpsellRuleRepository.java`,
`repository/SubscriptionPlanRepository.java`, `dto/QuotationDtos.java`, `config/DataSeeder.java`,
`static/quotation-detail.html`, `static/admin-upsell.html`, `static/admin-products.html`,
`static/portal-catalog.html`, `static/js/api.js`, the other `admin-*.html` (sub-nav label).

### 12.8 Verified

`verification/RecommendationVerify.java` drives the real `UpsellService` + `QuotationService` +
`PricingService` through in-memory repositories: 40/40 checks (rule validation, every filter, the
upgrade maths - e.g. 10 x Laptop Basic at 5% -> Laptop Pro at the Gold price gives exactly +2850.00 total
and +850.00 margin - ranking order, dismiss/restore, ADD vs UPGRADE replacing the same line with quantity
and discount preserved, the audit event text, risk-score recomputation and Manager routing on the next
submit, the legacy endpoint, and the subscription auto-RECURRING fix). The earlier harnesses still pass
(44/44, 10/10, 9/9, 4/4).

---

## 13. Managers/Admins build quotations + a manually-grown demo data set (this round)

### 13.1 Sales Manager and Admin can create and build quotations

Every quotation-building action (create, add/edit/remove lines, order discount, submit, reopen, PDF
import, What-If apply, negotiation reply, accept/dismiss recommendations) now allows `SALES_MANAGER` as
well as `ADMIN` and `SALES_REP`, on both the API (`@PreAuthorize`) and the screens. On the New quotation
form a Manager/Admin also gets an **"Owning sales rep"** picker: leave it on *Myself* to own the deal, or
pick a rep to open it on their behalf (the deal then appears in that rep's list and history). A rep can
only create quotations for themselves (`CreateQuotationRequest.salesRepUsername` is refused for reps).
One guard was added with it: **nobody can approve a quotation they own** - a Manager who builds a deal
needs another Manager (or an Admin) to approve it.

### 13.2 The demo data set - a small automatic base, everything else added manually

`service/DemoDataService.java` generates, through the real services, a six-month sales history. Nothing
is ever forced up to a fixed floor automatically - a small base catalog builds once, and every category
past that is added only when you explicitly ask for it, in whatever amount you choose:

| What | Automatic base | Grown manually via |
|---|---|---|
| Products | ~450 real, hand-curated (20 laptop models in Basic + Pro trim, 40 monitors across 8 brands, 24 accessory designs x 3 brand variants, ~66 infra items, 40 service kinds x Small/Mid/Enterprise, 35 SaaS families x Basic/Pro/Enterprise + 2 add-ons) | `extraProducts` - generates that many catalog variants (editions, refurbished, bulk packs, regional variants, etc. of real base products, each with its own plans/stock) |
| Quotations | 300 (the default `load()` batch) | `quotations` - adds that many MORE real quotations on top, every time you call it |
| Customers | 60 (`cust01`…`cust60` / `cust123`) | `extraCustomers` - generates that many more distinct company names (a combinatorial word + industry mix, e.g. "Cascade Software", "Ridge Automotive") |
| Sales reps | 8 (`rep3`…`rep8` / `rep123` added on top of `rep1`/`rep2`) | `extraReps` - generates that many more sales reps with unique names |
| Warehouses | +1 (North Hub, Delhi) | not extendable - not every warehouse stocks every product, so splits and backorders genuinely occur |
| Tier price lists | 70 rows | grows automatically with the catalog (Gold 5% / Silver 2.5% below catalog on selected hardware) |
| Recommendation rules | ~250 | grows automatically with the catalog (Basic→Pro→Enterprise product upgrades, laptop/monitor→accessory cross-sells, SaaS→add-on upsells, server→installation, plus a cross-sell rule for roughly a third of any generated product variants back to their base product) |

Confirmed deals carry real fulfillment splits (older ones marked delivered, a few left in transit for the
Deal Health slippage alert), one-time invoices and recurring schedules (older ones paid with a reference,
recent ones still due), pending deals have their approval log, rejected ones a reason, negotiated ones a
customer counter-offer - all produced by `QuotationService` / `ApprovalService` / `FulfillmentService` /
`BillingService`, never inserted by hand; only `createdAt` / `updatedAt` / `confirmedAt` / billing dates
are shifted into the past afterwards. The base catalog, customers and reps are generated from a fixed
seed so every fresh database gets the same base every time, and it is idempotent - skipped by
name/username on later runs, so nothing ever duplicates; growing any category further is a deliberate,
manual action, never automatic.

**How it loads - and how to grow it.** On an empty database `DataSeeder` runs the small default batch
(base catalog + 300 quotations) automatically after the hand-written scenarios. On any database, sign in
as `admin`, open **Backend Setup > Products > Demo data set**, and type exactly how much you want in each
of the four fields - **quotations to add**, **extra products to add**, **extra customers to add**, **extra
sales reps to add** (any of them can be left at 0) - then click **Add demo data**. Every click adds ONLY
what you asked for, on top of what is already there (a fresh random seed each time, so quotations are
never copies); run it as many times as you like, with whatever numbers you like (e.g. quotations=1000,
extraCustomers=1000, extraProducts=700), to push any or every category past 1000 yourself. API:
`POST /api/admin/demo-data/load?quotations=500&extraProducts=200&extraCustomers=500&extraReps=20` (Admin
only, all four params optional, default 0 except quotations which defaults to 300); `GET /api/admin/demo-data`
reports the current counts (including sales reps).

### 13.3 Verified

`verification/DemoDataVerify.java` runs the real generator against in-memory repositories: ~450 base
products, 300 quotations on the default call, 60 customers, 8 reps, 240+ rules, all six statuses
represented (~65% confirmed), every confirmed deal has an invoice/schedule, paid and unpaid invoices and
delivered / in-transit shipments both exist, pending deals have approval logs, rejected ones a rejection,
negotiated ones a counter-offer, dates spread over six months, and a second call with
`quotations=100, extraProducts=50, extraCustomers=30, extraReps=5` adds exactly 100 more quotations, 50
uniquely-named product variants, 30 more customers and 5 more reps - purely additive, nothing forced,
nothing duplicated - 23/23. All earlier harnesses still pass (40, 44, 10, 9, 4).

---

## 14. Notes

- All core business rules (discount ceilings, blended risk scoring, approval routing, warehouse
  splitting, proration, upsell ranking, deal-health alerts) are implemented in `service/*.java` as
  real application logic - nothing is hardcoded or faked for the demo.
- Warehouse stock is decremented exactly once when a fulfillment split reserves it, and restored if
  that split is later regenerated/replaced (e.g. clicking "Regenerate" or applying a manual override) -
  so re-opening the Fulfillment tab or re-running approval never double-allocates the same physical
  stock across quotations.
- The database schema is generated by Hibernate from the JPA entities in `model/`; no manual SQL
  scripts are needed.
- Multi-currency / multi-company support is explicitly a bonus in the PDF and is out of scope here.
