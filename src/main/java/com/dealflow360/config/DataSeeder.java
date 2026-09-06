package com.dealflow360.config;

import com.dealflow360.dto.QuotationDtos.AddLineRequest;
import com.dealflow360.dto.QuotationDtos.NegotiationMessageRequest;
import com.dealflow360.model.*;
import com.dealflow360.repository.*;
import com.dealflow360.service.BillingService;
import com.dealflow360.service.DemoDataService;
import com.dealflow360.service.QuotationService;
import org.springframework.boot.CommandLineRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Seeds the database with demo data on first run, so the app is
 * immediately usable for a live demo: one user per role, a customer per
 * tier (with portal logins), a small product catalog across the
 * Hardware/Service/Subscription categories used throughout the PDF's own
 * examples, two warehouses with uneven stock (so the fulfillment split
 * actually has something to split), discount ceilings that reproduce the
 * PDF's worked example exactly (Gold: Hardware 15%, Service 10%), an
 * approval chain, a subscription plan, and a couple of upsell rules.
 * <p>
 * Runs only when the database is empty, so it is safe to restart the app
 * without duplicating data.
 */
@Component
public class DataSeeder implements CommandLineRunner {

    private final AppUserRepository appUserRepository;
    private final CustomerRepository customerRepository;
    private final ProductRepository productRepository;
    private final ProductVariantRepository productVariantRepository;
    private final WarehouseRepository warehouseRepository;
    private final StockLevelRepository stockLevelRepository;
    private final DiscountCeilingRepository discountCeilingRepository;
    private final ApprovalChainRuleRepository approvalChainRuleRepository;
    private final SubscriptionPlanRepository subscriptionPlanRepository;
    private final UpsellRuleRepository upsellRuleRepository;
    private final PasswordEncoder passwordEncoder;
    private final QuotationRepository quotationRepository;
    private final QuotationService quotationService;
    private final PriceListEntryRepository priceListEntryRepository;
    private final BillingService billingService;
    private final DemoDataService demoDataService;

    public DataSeeder(AppUserRepository appUserRepository, CustomerRepository customerRepository,
                       ProductRepository productRepository, ProductVariantRepository productVariantRepository,
                       WarehouseRepository warehouseRepository, StockLevelRepository stockLevelRepository,
                       DiscountCeilingRepository discountCeilingRepository,
                       ApprovalChainRuleRepository approvalChainRuleRepository,
                       SubscriptionPlanRepository subscriptionPlanRepository,
                       UpsellRuleRepository upsellRuleRepository,
                       PasswordEncoder passwordEncoder,
                       QuotationRepository quotationRepository,
                       QuotationService quotationService,
                       PriceListEntryRepository priceListEntryRepository,
                       BillingService billingService,
                       DemoDataService demoDataService) {
        this.appUserRepository = appUserRepository;
        this.customerRepository = customerRepository;
        this.productRepository = productRepository;
        this.productVariantRepository = productVariantRepository;
        this.warehouseRepository = warehouseRepository;
        this.stockLevelRepository = stockLevelRepository;
        this.discountCeilingRepository = discountCeilingRepository;
        this.approvalChainRuleRepository = approvalChainRuleRepository;
        this.subscriptionPlanRepository = subscriptionPlanRepository;
        this.upsellRuleRepository = upsellRuleRepository;
        this.passwordEncoder = passwordEncoder;
        this.quotationRepository = quotationRepository;
        this.quotationService = quotationService;
        this.priceListEntryRepository = priceListEntryRepository;
        this.billingService = billingService;
        this.demoDataService = demoDataService;
    }

    @Override
    public void run(String... args) {
        if (appUserRepository.count() > 0) {
            return; // already seeded
        }

        // ---------------------------------------------------------- users
        AppUser admin = appUserRepository.save(new AppUser("admin", passwordEncoder.encode("admin123"), "Sam Admin", "admin@dealflow360.com", Role.ADMIN));
        appUserRepository.save(new AppUser("manager", passwordEncoder.encode("manager123"), "Priya Shah", "priya@dealflow360.com", Role.SALES_MANAGER));
        appUserRepository.save(new AppUser("finance", passwordEncoder.encode("finance123"), "Raj Mehta", "raj@dealflow360.com", Role.FINANCE));
        AppUser rep = appUserRepository.save(new AppUser("rep1", passwordEncoder.encode("rep123"), "Ananya Verma", "ananya@dealflow360.com", Role.SALES_REP));
        AppUser rep2 = appUserRepository.save(new AppUser("rep2", passwordEncoder.encode("rep123"), "Vikram Nair", "vikram@dealflow360.com", Role.SALES_REP));

        // ---------------------------------------------------------- customers (one per tier, each with a portal login)
        Customer acme = customerRepository.save(new Customer("Acme Corp", "buyer@acme.com", CustomerTier.GOLD, "acme", passwordEncoder.encode("acme123")));
        Customer beta = customerRepository.save(new Customer("Beta Industries", "buyer@beta.com", CustomerTier.SILVER, "beta", passwordEncoder.encode("beta123")));
        Customer bronze = customerRepository.save(new Customer("Bronze Traders", "buyer@bronzetraders.com", CustomerTier.BRONZE, "bronze", passwordEncoder.encode("bronze123")));
        Customer delta = customerRepository.save(new Customer("Delta Systems", "buyer@deltasystems.com", CustomerTier.SILVER, "delta", passwordEncoder.encode("delta123")));

        // ---------------------------------------------------------- products (a wider spread across
        // Hardware/Service/Subscription so the customer portal's "Browse & Request" catalog has real variety)
        Product laptop = productRepository.save(new Product("Laptop Pro", "Hardware", new BigDecimal("1200.00"), new BigDecimal("900.00"), "unit", new BigDecimal("18.00"), "15-inch business laptop"));
        Product chair = productRepository.save(new Product("Office Chair", "Hardware", new BigDecimal("150.00"), new BigDecimal("90.00"), "unit", new BigDecimal("18.00"), "Ergonomic office chair"));
        Product mouse = productRepository.save(new Product("Wireless Mouse", "Hardware", new BigDecimal("25.00"), new BigDecimal("12.00"), "unit", new BigDecimal("18.00"), "Ergonomic wireless mouse"));
        Product monitor = productRepository.save(new Product("27-inch Monitor", "Hardware", new BigDecimal("280.00"), new BigDecimal("190.00"), "unit", new BigDecimal("18.00"), "27-inch QHD monitor"));
        Product dockingStation = productRepository.save(new Product("Docking Station", "Hardware", new BigDecimal("120.00"), new BigDecimal("70.00"), "unit", new BigDecimal("18.00"), "USB-C docking station with dual display support"));
        Product setupService = productRepository.save(new Product("Setup & Installation Service", "Service", new BigDecimal("300.00"), new BigDecimal("100.00"), "engagement", new BigDecimal("18.00"), "On-site setup and installation"));
        Product training = productRepository.save(new Product("Onboarding Training", "Service", new BigDecimal("500.00"), new BigDecimal("150.00"), "engagement", new BigDecimal("18.00"), "Half-day onboarding training session"));
        Product warranty = productRepository.save(new Product("Extended Warranty (1 year)", "Service", new BigDecimal("150.00"), new BigDecimal("40.00"), "engagement", new BigDecimal("18.00"), "Additional 12 months of hardware coverage"));
        Product cloudSuite = productRepository.save(new Product("Cloud Suite License", "Subscription", new BigDecimal("50.00"), new BigDecimal("20.00"), "seat/month", new BigDecimal("18.00"), "Per-seat monthly SaaS license"));
        Product analyticsAddon = productRepository.save(new Product("Analytics Add-on", "Subscription", new BigDecimal("20.00"), new BigDecimal("8.00"), "seat/month", new BigDecimal("18.00"), "Advanced reporting add-on for Cloud Suite"));
        // Recommendation-engine demo catalog: a basic tier below the Laptop Pro (for PRODUCT_UPGRADE),
        // the accessories the PDF example lists (Laptop -> Mouse -> Laptop Bag -> Keyboard -> Warranty),
        // and a Pro subscription above Cloud Suite.
        Product laptopBasic = productRepository.save(new Product("Laptop Basic", "Hardware", new BigDecimal("850.00"), new BigDecimal("700.00"), "unit", new BigDecimal("18.00"), "14-inch entry-level laptop, 8 GB RAM, 256 GB SSD"));
        Product laptopBag = productRepository.save(new Product("Laptop Bag", "Hardware", new BigDecimal("45.00"), new BigDecimal("18.00"), "unit", new BigDecimal("18.00"), "Padded 15-inch laptop bag"));
        Product keyboard = productRepository.save(new Product("Mechanical Keyboard", "Hardware", new BigDecimal("70.00"), new BigDecimal("32.00"), "unit", new BigDecimal("18.00"), "Compact mechanical keyboard"));
        Product cloudSuitePro = productRepository.save(new Product("Cloud Suite Pro License", "Subscription", new BigDecimal("80.00"), new BigDecimal("28.00"), "seat/month", new BigDecimal("18.00"), "Cloud Suite with SSO, audit logs and priority support"));

        productVariantRepository.save(new ProductVariant(laptop, "RAM", "16GB", BigDecimal.ZERO));
        productVariantRepository.save(new ProductVariant(laptop, "RAM", "32GB", new BigDecimal("200.00")));

        // ---------------------------------------------------------- warehouses + uneven stock (so splitting has a reason to happen)
        Warehouse mainWarehouse = warehouseRepository.save(new Warehouse("Main Warehouse", "Ahmedabad", new BigDecimal("1.00")));
        Warehouse eastDepot = warehouseRepository.save(new Warehouse("East Depot", "Pune", new BigDecimal("1.50")));

        stockLevelRepository.save(new StockLevel(mainWarehouse, laptop, 5, 5));
        stockLevelRepository.save(new StockLevel(eastDepot, laptop, 10, 5));
        stockLevelRepository.save(new StockLevel(mainWarehouse, chair, 20, 10));
        stockLevelRepository.save(new StockLevel(eastDepot, chair, 5, 10));
        stockLevelRepository.save(new StockLevel(mainWarehouse, mouse, 60, 20));
        stockLevelRepository.save(new StockLevel(eastDepot, mouse, 40, 20));
        stockLevelRepository.save(new StockLevel(mainWarehouse, monitor, 15, 8));
        stockLevelRepository.save(new StockLevel(eastDepot, monitor, 12, 8));
        stockLevelRepository.save(new StockLevel(mainWarehouse, dockingStation, 25, 10));
        stockLevelRepository.save(new StockLevel(eastDepot, dockingStation, 10, 10));
        stockLevelRepository.save(new StockLevel(mainWarehouse, laptopBasic, 12, 5));
        stockLevelRepository.save(new StockLevel(eastDepot, laptopBasic, 8, 5));
        stockLevelRepository.save(new StockLevel(mainWarehouse, laptopBag, 40, 10));
        stockLevelRepository.save(new StockLevel(mainWarehouse, keyboard, 30, 10));
        stockLevelRepository.save(new StockLevel(eastDepot, keyboard, 15, 10));

        // ---------------------------------------------------------- discount ceilings - reproduces the PDF's own worked example:
        // "A Gold customer is normally allowed up to 15%... Hardware allowed up to 15%... Service allowed only up to 10%"
        discountCeilingRepository.save(new DiscountCeiling(CustomerTier.GOLD, DiscountCeiling.DEFAULT_CATEGORY, new BigDecimal("15.00")));
        discountCeilingRepository.save(new DiscountCeiling(CustomerTier.GOLD, "Hardware", new BigDecimal("15.00")));
        discountCeilingRepository.save(new DiscountCeiling(CustomerTier.GOLD, "Service", new BigDecimal("10.00")));
        discountCeilingRepository.save(new DiscountCeiling(CustomerTier.GOLD, "Subscription", new BigDecimal("12.00")));

        discountCeilingRepository.save(new DiscountCeiling(CustomerTier.SILVER, DiscountCeiling.DEFAULT_CATEGORY, new BigDecimal("10.00")));
        discountCeilingRepository.save(new DiscountCeiling(CustomerTier.SILVER, "Hardware", new BigDecimal("10.00")));
        discountCeilingRepository.save(new DiscountCeiling(CustomerTier.SILVER, "Service", new BigDecimal("7.00")));
        discountCeilingRepository.save(new DiscountCeiling(CustomerTier.SILVER, "Subscription", new BigDecimal("8.00")));

        discountCeilingRepository.save(new DiscountCeiling(CustomerTier.BRONZE, DiscountCeiling.DEFAULT_CATEGORY, new BigDecimal("5.00")));
        discountCeilingRepository.save(new DiscountCeiling(CustomerTier.BRONZE, "Hardware", new BigDecimal("5.00")));
        discountCeilingRepository.save(new DiscountCeiling(CustomerTier.BRONZE, "Service", new BigDecimal("3.00")));
        discountCeilingRepository.save(new DiscountCeiling(CustomerTier.BRONZE, "Subscription", new BigDecimal("4.00")));

        // ---------------------------------------------------------- approval chain
        approvalChainRuleRepository.save(new ApprovalChainRule(new BigDecimal("0.00"), new BigDecimal("0.01"), false, false, "Within limits - no approval required"));
        approvalChainRuleRepository.save(new ApprovalChainRule(new BigDecimal("0.01"), new BigDecimal("10.00"), true, false, "Sales Manager approval"));
        approvalChainRuleRepository.save(new ApprovalChainRule(new BigDecimal("10.00"), new BigDecimal("999999.00"), true, true, "Sales Manager, then Finance approval"));

        // ---------------------------------------------------------- subscription plans (billing cycles, PDF A5)
        subscriptionPlanRepository.save(new SubscriptionPlan("Cloud Suite - Monthly", cloudSuite, BillingCycle.MONTHLY, new BigDecimal("50.00"), true, true));
        subscriptionPlanRepository.save(new SubscriptionPlan("Analytics Add-on - Monthly", analyticsAddon, BillingCycle.MONTHLY, new BigDecimal("20.00"), true, true));
        // PDF A5 - "Define recurring plans (monthly, quarterly, yearly)": longer commitments carry a built-in saving.
        subscriptionPlanRepository.save(new SubscriptionPlan("Cloud Suite - Quarterly", cloudSuite, BillingCycle.QUARTERLY, new BigDecimal("142.50"), true, true));
        subscriptionPlanRepository.save(new SubscriptionPlan("Cloud Suite - Yearly", cloudSuite, BillingCycle.YEARLY, new BigDecimal("540.00"), true, false));
        subscriptionPlanRepository.save(new SubscriptionPlan("Cloud Suite Pro - Monthly", cloudSuitePro, BillingCycle.MONTHLY, new BigDecimal("80.00"), true, true));
        subscriptionPlanRepository.save(new SubscriptionPlan("Cloud Suite Pro - Yearly", cloudSuitePro, BillingCycle.YEARLY, new BigDecimal("864.00"), true, false));

        // ---------------------------------------------------------- tier price lists (PDF A2)
        // Gold accounts have negotiated list prices on the big-ticket hardware; everything without a
        // row simply uses the catalog price. Discounts on top of these are still governed by the ceilings.
        priceListEntryRepository.save(new PriceListEntry(CustomerTier.GOLD, laptop, new BigDecimal("1150.00")));
        priceListEntryRepository.save(new PriceListEntry(CustomerTier.GOLD, monitor, new BigDecimal("265.00")));
        priceListEntryRepository.save(new PriceListEntry(CustomerTier.SILVER, laptop, new BigDecimal("1180.00")));

        // ---------------------------------------------------------- upsell / cross-sell rules
        // Recommendation rules (A6 / Live Recommendation Engine). Type decides what accepting does:
        // CROSS_SELL adds alongside, UPSELL adds a premium offering, PRODUCT_UPGRADE replaces the line.
        UpsellRule.RecommendationType CROSS = UpsellRule.RecommendationType.CROSS_SELL;
        UpsellRule.RecommendationType UP = UpsellRule.RecommendationType.UPSELL;
        UpsellRule.RecommendationType UPGRADE = UpsellRule.RecommendationType.PRODUCT_UPGRADE;
        // Laptop Pro -> accessories chain from the PDF example (Mouse -> Bag -> Keyboard -> Warranty), plus services
        upsellRuleRepository.save(new UpsellRule(laptop, mouse, CROSS, 90, new BigDecimal("30.00"), true, "Bundle offer", "Almost every laptop order ships with a mouse"));
        upsellRuleRepository.save(new UpsellRule(laptop, laptopBag, CROSS, 80, new BigDecimal("30.00"), false, null, "Protects the laptop in transit - frequently bought together"));
        upsellRuleRepository.save(new UpsellRule(laptop, keyboard, CROSS, 70, new BigDecimal("30.00"), false, null, "Desk setup companion for the laptop"));
        upsellRuleRepository.save(new UpsellRule(laptop, warranty, CROSS, 75, new BigDecimal("15.00"), false, "Peace of mind", "Extends hardware coverage to 24 months"));
        upsellRuleRepository.save(new UpsellRule(laptop, dockingStation, CROSS, 85, new BigDecimal("35.00"), true, "Promoted", "Dual-display docking for the Laptop Pro"));
        upsellRuleRepository.save(new UpsellRule(laptop, setupService, UP, 60, new BigDecimal("20.00"), true, null, "On-site setup gets the fleet productive on day one"));
        upsellRuleRepository.save(new UpsellRule(laptop, training, UP, 40, new BigDecimal("50.00"), false, null, "Half-day onboarding for the new hardware"));
        // Laptop Basic -> same accessories, and the upgrade to Laptop Pro
        upsellRuleRepository.save(new UpsellRule(laptopBasic, laptop, UPGRADE, 95, new BigDecimal("20.00"), true, "Recommended Upgrade", "More RAM (16 GB), faster processor, 512 GB storage and a 15-inch display"));
        upsellRuleRepository.save(new UpsellRule(laptopBasic, mouse, CROSS, 85, new BigDecimal("30.00"), true, "Bundle offer", "Almost every laptop order ships with a mouse"));
        upsellRuleRepository.save(new UpsellRule(laptopBasic, laptopBag, CROSS, 70, new BigDecimal("30.00"), false, null, "Protects the laptop in transit"));
        upsellRuleRepository.save(new UpsellRule(laptopBasic, warranty, CROSS, 65, new BigDecimal("15.00"), false, null, "Extends hardware coverage to 24 months"));
        // Monitor / chair pairings
        upsellRuleRepository.save(new UpsellRule(monitor, dockingStation, CROSS, 80, new BigDecimal("30.00"), true, "Promoted", "Drives the monitor from any laptop with one cable"));
        upsellRuleRepository.save(new UpsellRule(monitor, keyboard, CROSS, 50, new BigDecimal("30.00"), false, null, "Completes the desk setup"));
        upsellRuleRepository.save(new UpsellRule(chair, training, CROSS, 30, new BigDecimal("10.00"), false, null, "Workplace onboarding often accompanies furniture refreshes"));
        // Subscriptions: Basic -> Pro upgrade, and the analytics add-on as an upsell
        upsellRuleRepository.save(new UpsellRule(cloudSuite, cloudSuitePro, UPGRADE, 90, new BigDecimal("20.00"), true, "Recommended Upgrade", "Adds SSO, audit logs and priority support"));
        upsellRuleRepository.save(new UpsellRule(cloudSuite, analyticsAddon, UP, 75, new BigDecimal("20.00"), true, "Popular add-on", "Advanced reporting on top of Cloud Suite"));
        upsellRuleRepository.save(new UpsellRule(cloudSuitePro, analyticsAddon, UP, 75, new BigDecimal("20.00"), false, null, "Advanced reporting on top of Cloud Suite Pro"));

        // ==================================================================
        // Deal Intelligence demo scenarios - built through the SAME real
        // services the UI itself calls (QuotationService, which in turn drives
        // DiscountRiskService/ApprovalService/FulfillmentService), so every
        // Copilot insight, health score, anomaly alert and approval routing a
        // demo shows is backed by genuinely computed data, never a hardcoded
        // score or invented message. Six scenarios are guaranteed reproducible
        // on every fresh boot, plus three more that exercise the additional
        // Deal Health anomaly types added alongside them:
        //   1) High Discount        -> Manager + Finance approval required
        //   2) Healthy Deal          -> no approval required
        //   3) Multi-Warehouse Order -> split across Main Warehouse + East Depot
        //   4) Customer Negotiation  -> active counter-discount thread
        //   5) Stalled Deal          -> no activity for several days
        //   6) Discount Anomaly      -> discount far above the rep's own average
        //   +  Margin Anomaly, Negotiation Loop, Approval Delay

        // ---- baseline history: three unremarkable, confirmed deals for rep1,
        // so the anomaly detectors (which compare a deal to the REP'S OWN past
        // average, per Feature 8) have real history to compare against from the
        // very first login instead of silently showing nothing.
        seedHistoricalDeal(acme, rep, chair, 2, "5.00", 40);
        seedHistoricalDeal(acme, rep, chair, 2, "6.00", 33);
        seedHistoricalDeal(acme, rep, chair, 2, "5.00", 27);

        // ---- Scenario 1: High Discount - a 25% discount on a Service line blows
        // past the Gold/Service ceiling (10%), a 15-point blended risk score that
        // the seeded approval chain routes to Manager + Finance. Left pending so
        // the approval chain can be walked live.
        Quotation scenario1 = quotationService.createQuotation(acme.getId(), rep.getUsername());
        quotationService.addLine(scenario1.getId(), lineRequest(setupService.getId(), 2, "25.00"));
        quotationService.submitForApproval(scenario1.getId(), rep.getUsername());

        // ---- Scenario 2: Healthy Deal - both lines are comfortably within their
        // ceilings. Left in Draft so the demo can Submit it live and watch the
        // Copilot / health score confirm "no approval required".
        Quotation scenario2 = quotationService.createQuotation(acme.getId(), rep.getUsername());
        quotationService.addLine(scenario2.getId(), lineRequest(laptop.getId(), 1, "5.00"));
        quotationService.addLine(scenario2.getId(), lineRequest(chair.getId(), 1, "5.00"));

        // ---- Scenario 3: Multi-Warehouse Order - 8 laptops is more than Main
        // Warehouse's stock (5) alone can cover, so the real split - and every
        // What-If / warehouse-optimizer preview - has to span Main Warehouse and
        // East Depot.
        Quotation scenario3 = quotationService.createQuotation(acme.getId(), rep.getUsername());
        quotationService.addLine(scenario3.getId(), lineRequest(laptop.getId(), 8, "5.00"));
        quotationService.submitForApproval(scenario3.getId(), rep.getUsername());

        // ---- Scenario 4: Customer Negotiation - an approved deal the customer
        // then re-opens from the portal, asking for more discount than their
        // tier allows, with a live negotiation thread ready for the Smart
        // Negotiation Assistant to respond to.
        Quotation scenario4 = quotationService.createQuotation(beta.getId(), rep.getUsername());
        quotationService.addLine(scenario4.getId(), lineRequest(chair.getId(), 2, "5.00"));
        quotationService.submitForApproval(scenario4.getId(), rep.getUsername());
        negotiate(scenario4.getId(), "CUSTOMER", "Beta Industries", "COUNTER_DISCOUNT",
                "Can you do better than this? We were hoping for closer to 15% off.",
                new BigDecimal("15.00"), firstLineId(scenario4.getId()));
        negotiate(scenario4.getId(), "SALES_REP", rep.getFullName(), "COMMENT",
                "Let me see what I can put together for you.", null, null);

        // ---- Scenario 5: Stalled Deal - a draft with no activity for 6 days
        // (Deal Health flags anything open past 3 days, HIGH severity past 6).
        Quotation scenario5 = quotationService.createQuotation(bronze.getId(), rep.getUsername());
        quotationService.addLine(scenario5.getId(), lineRequest(chair.getId(), 1, "2.00"));
        backdate(scenario5.getId(), 8, 6);

        // ---- Scenario 6: Discount Anomaly - 15% sits right at the Gold/Hardware
        // ceiling (so it needs no approval) but is far above rep1's own ~5.3%
        // historical average discount, so it should still surface as an anomaly.
        Quotation scenario6 = quotationService.createQuotation(acme.getId(), rep.getUsername());
        quotationService.addLine(scenario6.getId(), lineRequest(chair.getId(), 2, "15.00"));

        // ---- Extra: Margin Anomaly - only 8% off (comfortably under the 15%
        // ceiling, so no discount anomaly) but laptops carry a much lower margin
        // % than the chair-heavy history above, so it's the margin - not the
        // discount - that is unusual for this rep.
        Quotation marginAnomalyDeal = quotationService.createQuotation(acme.getId(), rep.getUsername());
        quotationService.addLine(marginAnomalyDeal.getId(), lineRequest(laptop.getId(), 1, "8.00"));

        // ---- Extra: Negotiation Loop - three customer counter-discount rounds
        // on the same line is exactly the "structured alternative" trigger.
        Quotation negotiationLoopDeal = quotationService.createQuotation(acme.getId(), rep.getUsername());
        quotationService.addLine(negotiationLoopDeal.getId(), lineRequest(chair.getId(), 2, "5.00"));
        quotationService.submitForApproval(negotiationLoopDeal.getId(), rep.getUsername());
        Long loopLineId = firstLineId(negotiationLoopDeal.getId());
        negotiate(negotiationLoopDeal.getId(), "CUSTOMER", "Acme Corp", "COUNTER_DISCOUNT", "Could we get 8% instead?", new BigDecimal("8.00"), loopLineId);
        negotiate(negotiationLoopDeal.getId(), "CUSTOMER", "Acme Corp", "COUNTER_DISCOUNT", "What about 10%?", new BigDecimal("10.00"), loopLineId);
        negotiate(negotiationLoopDeal.getId(), "CUSTOMER", "Acme Corp", "COUNTER_DISCOUNT", "We'd really like 12%.", new BigDecimal("12.00"), loopLineId);

        // ---- Extra: Approval Delay - stuck waiting on Manager approval for 4
        // days (the dashboard flags PENDING_APPROVAL deals waiting 2+ days).
        Quotation approvalDelayDeal = quotationService.createQuotation(acme.getId(), rep.getUsername());
        quotationService.addLine(approvalDelayDeal.getId(), lineRequest(setupService.getId(), 1, "12.00"));
        quotationService.submitForApproval(approvalDelayDeal.getId(), rep.getUsername());
        backdate(approvalDelayDeal.getId(), 4, 4);

        // ---- Scenario 7: Customer self-service request - Delta Systems used the new "Browse & Request"
        // catalog page to build their own list (no sales rep involved yet), exactly the way
        // POST /api/portal/quotations builds it: a plain DRAFT quotation at list price, routed to a
        // rep, with the customer's note already sitting in the negotiation thread waiting for a reply.
        Quotation selfServiceDeal = quotationService.createQuotation(delta.getId(), rep2.getUsername());
        quotationService.addLine(selfServiceDeal.getId(), lineRequest(mouse.getId(), 10, "0.00"));
        quotationService.addLine(selfServiceDeal.getId(), lineRequest(monitor.getId(), 3, "0.00"));
        quotationService.addLine(selfServiceDeal.getId(), lineRequest(dockingStation.getId(), 3, "0.00"));
        negotiate(selfServiceDeal.getId(), "CUSTOMER", delta.getName(), "COMMENT",
                "Hi - putting together a workstation refresh for a new team. Could you confirm delivery timing and see if there's a bundle discount available?", null, null);
        backdate(selfServiceDeal.getId(), 1, 1);

        // ---- Scenario 8: Hybrid Billing - a CONFIRMED order that mixes one-time hardware, a service
        // and a recurring Cloud Suite subscription. Confirmation issues the one-time invoice and the
        // recurring cycle schedule separately (PDF B7); the one-time invoice is then paid, leaving the
        // subscription cycle still due - so the Billing tab opens on a real PARTIALLY_PAID order.
        SubscriptionPlan cloudMonthly = subscriptionPlanRepository.findAll().stream()
                .filter(pl -> pl.getName().equals("Cloud Suite - Monthly")).findFirst().orElseThrow();
        Quotation hybridDeal = quotationService.createQuotation(acme.getId(), rep.getUsername());
        quotationService.addLine(hybridDeal.getId(), lineRequest(laptop.getId(), 2, "5.00"));
        quotationService.addLine(hybridDeal.getId(), lineRequest(setupService.getId(), 1, "0.00"));
        AddLineRequest cloudLine = lineRequest(cloudSuite.getId(), 5, "0.00");
        cloudLine.lineType = QuotationLine.LineType.RECURRING;
        cloudLine.subscriptionPlanId = cloudMonthly.getId();
        quotationService.addLine(hybridDeal.getId(), cloudLine);
        quotationService.submitForApproval(hybridDeal.getId(), rep.getUsername());
        quotationService.confirmQuotation(hybridDeal.getId(), rep.getUsername());
        billingService.scheduleForQuotation(hybridDeal.getId()).stream()
                .filter(e -> e.getEntryType() == BillingScheduleEntry.EntryType.ONE_TIME_INVOICE)
                .findFirst()
                .ifPresent(inv -> billingService.recordPayment(hybridDeal.getId(), inv.getId(), false, "NEFT-000417", "finance"));
        backdate(hybridDeal.getId(), 2, 1);

        // ---- Scenario 9: Rejected deal - a Bronze customer asked for 12% on training (Bronze/Service
        // ceiling is 3%), the Manager rejected it. Left REJECTED so "Reopen for revision" (rep) and a
        // lower counter-offer from the portal (customer) can both be demonstrated as the way back.
        Quotation rejectedDeal = quotationService.createQuotation(bronze.getId(), rep2.getUsername());
        quotationService.addLine(rejectedDeal.getId(), lineRequest(training.getId(), 1, "12.00"));
        quotationService.submitForApproval(rejectedDeal.getId(), rep2.getUsername());
        quotationService.rejectStep(rejectedDeal.getId(), Role.SALES_MANAGER, "manager",
                "12% on a Service line for a Bronze account is well past policy - please revise to 5% or less");
        backdate(rejectedDeal.getId(), 3, 2);

        // ---- Scenario 10: Recommendation demo - 10 x Laptop Basic plus a Cloud Suite subscription, in
        // Draft, so the Smart Recommendations panel opens with a PRODUCT_UPGRADE (10 x Laptop Basic ->
        // 10 x Laptop Pro), the accessory CROSS_SELLs and the Cloud Suite Pro / Analytics UPSELLs.
        Quotation recoDeal = quotationService.createQuotation(acme.getId(), rep.getUsername());
        quotationService.addLine(recoDeal.getId(), lineRequest(laptopBasic.getId(), 10, "5.00"));
        AddLineRequest recoCloud = lineRequest(cloudSuite.getId(), 10, "0.00");
        recoCloud.lineType = QuotationLine.LineType.RECURRING;
        recoCloud.subscriptionPlanId = cloudMonthly.getId();
        quotationService.addLine(recoDeal.getId(), recoCloud);

        // ---- Bulk demo set: the base catalog (~450 products, 60 customers, 8 reps) plus 300
        // quotations across the last six months, all built through the real services (see
        // DemoDataService). More of any of these can be added manually, any time, from Backend
        // Setup > Products > "Add demo data" (quotations / extra products / extra customers / extra
        // reps are all independent, additive counts you choose).
        DemoDataService.Summary bulk = demoDataService.load("seeder");

        System.out.println("=================================================================");
        System.out.println(" DealFlow360 demo data loaded. Sign in with any of:");
        System.out.println("   Admin          -> admin / admin123");
        System.out.println("   Sales Manager  -> manager / manager123");
        System.out.println("   Finance        -> finance / finance123");
        System.out.println("   Sales Rep      -> rep1 / rep123");
        System.out.println("   Sales Rep      -> rep2 / rep123");
        System.out.println("   Customer Portal (Acme, Gold)     -> acme / acme123");
        System.out.println("   Customer Portal (Beta, Silver)   -> beta / beta123");
        System.out.println("   Customer Portal (Bronze)         -> bronze / bronze123");
        System.out.println("   Customer Portal (Delta, Silver)  -> delta / delta123 (has a self-service request pending - try 'Browse & Request' to add another)");
        System.out.println("   Bulk demo: reps rep3..rep" + bulk.salesReps + " / rep123, customers cust01..cust" + String.format("%02d", bulk.customers) + " / cust123 - "
                + bulk.quotations + " quotations (" + bulk.totalQuotations + " total), " + bulk.products + " products, " + bulk.rules + " recommendation rules"
                + " (add more any time from Backend Setup > Products > Add demo data)");
        System.out.println("=================================================================");
    }

    // ---------------------------------------------------------------- demo-scenario helpers
    // These build seed quotations through the real QuotationService (the same entry point the
    // UI/API use), so the resulting discount routing, risk scores and fulfillment splits are
    // genuinely computed - never hardcoded - and only the historical timestamps are adjusted
    // afterwards so the anomaly/stalled-deal detectors have something to compare against.

    private AddLineRequest lineRequest(Long productId, int quantity, String discountPercent) {
        AddLineRequest request = new AddLineRequest();
        request.productId = productId;
        request.quantity = quantity;
        request.discountPercent = new BigDecimal(discountPercent);
        return request;
    }

    private Long firstLineId(Long quotationId) {
        return quotationService.getEntity(quotationId).getLines().get(0).getId();
    }

    private void negotiate(Long quotationId, String senderType, String senderName, String messageType,
                            String content, BigDecimal proposedDiscountPercent, Long quotationLineId) {
        NegotiationMessageRequest request = new NegotiationMessageRequest();
        request.content = content;
        request.messageType = messageType;
        request.proposedDiscountPercent = proposedDiscountPercent;
        request.quotationLineId = quotationLineId;
        quotationService.addNegotiationMessage(quotationId, request, senderType, senderName);
    }

    /** Backdates a quotation's created/updated timestamps so "stalled" and "approval delay" alerts are visible immediately. */
    private void backdate(Long quotationId, long createdDaysAgo, long updatedDaysAgo) {
        Quotation quotation = quotationRepository.findById(quotationId).orElseThrow();
        quotation.setCreatedAt(LocalDateTime.now().minusDays(createdDaysAgo));
        quotation.setUpdatedAt(LocalDateTime.now().minusDays(updatedDaysAgo));
        quotationRepository.save(quotation);
    }

    /** Creates, submits and confirms a clean deal, then backdates it so it reads as settled history for the rep. */
    private void seedHistoricalDeal(Customer customer, AppUser salesRep, Product product, int quantity,
                                     String discountPercent, long daysAgo) {
        Quotation quotation = quotationService.createQuotation(customer.getId(), salesRep.getUsername());
        quotationService.addLine(quotation.getId(), lineRequest(product.getId(), quantity, discountPercent));
        quotationService.submitForApproval(quotation.getId(), salesRep.getUsername());
        quotationService.confirmQuotation(quotation.getId(), salesRep.getUsername());
        // Settled history: the one-time invoice was paid long ago.
        billingService.recordPayment(quotation.getId(), null, true, "HIST-" + daysAgo, "finance");

        Quotation saved = quotationRepository.findById(quotation.getId()).orElseThrow();
        LocalDateTime when = LocalDateTime.now().minusDays(daysAgo);
        saved.setCreatedAt(when);
        saved.setUpdatedAt(when);
        saved.setConfirmedAt(when);
        quotationRepository.save(saved);
    }
}
