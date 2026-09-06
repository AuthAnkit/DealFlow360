package com.dealflow360.service;

import com.dealflow360.dto.QuotationDtos.AddLineRequest;
import com.dealflow360.dto.QuotationDtos.NegotiationMessageRequest;
import com.dealflow360.model.*;
import com.dealflow360.model.UpsellRule.RecommendationType;
import com.dealflow360.repository.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Bulk demo data: a real base catalog (~450 products), 60 customers, 8 sales reps, a third
 * warehouse, tier prices, recommendation rules, negotiations, approvals, payments and deliveries
 * spread over the last six months - enough volume for the reports, trends, pipeline and Deal
 * Health screens to look like a real sales operation, built once from a fixed seed.
 * <p>
 * Everything past that base is added MANUALLY, in whatever amount you ask for, on top - as many
 * quotations, extra products, extra customers and/or extra reps as you want, as many times as you
 * want (nothing is ever forced up to a fixed floor automatically): {@code quotationCount} real
 * quotations, {@code extraProducts} generated catalog variants (editions, refurbished, bulk packs,
 * etc. of real base products - see {@link #generateExtraProducts}), {@code extraCustomers}
 * generated companies (a combinatorial mix of distinct-sounding names, e.g. "Cascade Software",
 * "Ridge Automotive" - see {@link #generateExtraCustomers}) and {@code extraReps} generated sales
 * reps (see {@link #generateExtraReps}). Run it as many times as you like with whatever numbers you
 * want (e.g. quotations=1000, extraProducts=700, extraCustomers=1000) to grow the data set to any
 * size, including well past 1000 in every category.
 * <p>
 * Every quotation is built through the REAL {@link QuotationService} (and therefore
 * DiscountRiskService / ApprovalService / FulfillmentService / BillingService), so every status,
 * risk score, approval log, warehouse split, invoice and payment in the demo set was genuinely
 * computed by the same code the UI uses - only the timestamps are adjusted afterwards.
 * <p>
 * Runs automatically on a fresh database (after {@code DataSeeder}'s hand-written scenarios) with
 * the small default batch, and can be loaded on demand onto an existing database from Backend Setup
 * ("Add demo data") - it is idempotent: it skips anything already present by name/username and only
 * ever adds on top.
 */
@Service
public class DemoDataService {

    private static final Logger log = LoggerFactory.getLogger(DemoDataService.class);
    public static final String MARKER_PRODUCT = "Dell Latitude 5440 Laptop"; // first generated product - presence means "already loaded"

    private final AppUserRepository appUserRepository;
    private final CustomerRepository customerRepository;
    private final ProductRepository productRepository;
    private final WarehouseRepository warehouseRepository;
    private final StockLevelRepository stockLevelRepository;
    private final SubscriptionPlanRepository subscriptionPlanRepository;
    private final PriceListEntryRepository priceListEntryRepository;
    private final UpsellRuleRepository upsellRuleRepository;
    private final QuotationRepository quotationRepository;
    private final FulfillmentSplitRepository fulfillmentSplitRepository;
    private final BillingScheduleEntryRepository billingScheduleEntryRepository;
    private final QuotationService quotationService;
    private final BillingService billingService;
    private final AuditService auditService;
    private final PasswordEncoder passwordEncoder;

    public DemoDataService(AppUserRepository appUserRepository, CustomerRepository customerRepository,
                           ProductRepository productRepository, WarehouseRepository warehouseRepository,
                           StockLevelRepository stockLevelRepository, SubscriptionPlanRepository subscriptionPlanRepository,
                           PriceListEntryRepository priceListEntryRepository, UpsellRuleRepository upsellRuleRepository,
                           QuotationRepository quotationRepository, FulfillmentSplitRepository fulfillmentSplitRepository,
                           BillingScheduleEntryRepository billingScheduleEntryRepository,
                           QuotationService quotationService, BillingService billingService, AuditService auditService,
                           PasswordEncoder passwordEncoder) {
        this.appUserRepository = appUserRepository;
        this.customerRepository = customerRepository;
        this.productRepository = productRepository;
        this.warehouseRepository = warehouseRepository;
        this.stockLevelRepository = stockLevelRepository;
        this.subscriptionPlanRepository = subscriptionPlanRepository;
        this.priceListEntryRepository = priceListEntryRepository;
        this.upsellRuleRepository = upsellRuleRepository;
        this.quotationRepository = quotationRepository;
        this.fulfillmentSplitRepository = fulfillmentSplitRepository;
        this.billingScheduleEntryRepository = billingScheduleEntryRepository;
        this.quotationService = quotationService;
        this.billingService = billingService;
        this.auditService = auditService;
        this.passwordEncoder = passwordEncoder;
    }

    /** Result summary for the admin screen / log. */
    public static class Summary {
        public int products, quotations, customers, salesReps, rules, plans, priceListEntries;
        public int newProducts;        // products created by THIS call (extra products)
        public int newCustomers;       // customers created by THIS call (extra customers)
        public int newReps;            // sales reps created by THIS call (extra reps)
        public int totalQuotations;    // quotations in the database after this call
        public boolean alreadyLoaded;  // the base catalog already existed before this call
        public long millis;
    }

    public boolean isLoaded() {
        return productRepository.findAll().stream().anyMatch(p -> MARKER_PRODUCT.equals(p.getName()));
    }

    /** Default (small) batch: the base catalog plus 300 quotations - nothing forced beyond that. */
    public Summary load(String actor) {
        return load(actor, 300, 0);
    }

    /** Back-compat 3-arg form: no extra customers/reps requested. */
    public Summary load(String actor, int quotationCount, int extraProducts) {
        return load(actor, quotationCount, extraProducts, 0, 0);
    }

    /**
     * Adds demo data manually, in whatever amounts you ask for. The first call builds the base
     * catalog (~450 products, 60 customers, 8 reps, rules - deterministic, always the same) plus
     * {@code quotationCount} quotations; every later call adds {@code quotationCount} MORE
     * quotations (a fresh random seed each time, so they are not copies) and, if asked,
     * {@code extraProducts} generated catalog variants, {@code extraCustomers} generated companies
     * and {@code extraReps} generated sales reps - all purely additive, nothing is ever forced up to
     * a floor automatically. Everything that already exists is skipped by name, so the base catalog
     * part is safe to re-run.
     */
    public Summary load(String actor, int quotationCount, int extraProducts, int extraCustomers, int extraReps) {
        long start = System.currentTimeMillis();
        Summary summary = new Summary();
        quotationCount = Math.max(0, Math.min(5000, quotationCount));
        extraProducts = Math.max(0, Math.min(5000, extraProducts));
        extraCustomers = Math.max(0, Math.min(5000, extraCustomers));
        extraReps = Math.max(0, Math.min(500, extraReps));
        summary.alreadyLoaded = isLoaded();
        // Catalog/people are built from a fixed seed so names and prices never drift between runs;
        // quotations (and extra products) draw from a seed that moves with what is already there.
        Random catalogRnd = new Random(42);
        Random rnd = new Random(42 + quotationRepository.count() * 7L + productRepository.count());

        // ------------------------------------------------------------------ people (base, fixed) + manual extras
        ensureSalesReps();
        summary.newReps = extraReps > 0 ? generateExtraReps(extraReps) : 0;
        List<AppUser> reps = appUserRepository.findByRole(Role.SALES_REP);
        summary.salesReps = reps.size();

        ensureCustomers();
        summary.newCustomers = extraCustomers > 0 ? generateExtraCustomers(extraCustomers) : 0;
        List<Customer> customers = new ArrayList<>(customerRepository.findAll());
        summary.customers = customers.size();

        // ------------------------------------------------------------------ warehouses
        List<Warehouse> warehouses = warehouseRepository.findAll();
        if (warehouses.stream().noneMatch(w -> w.getName().equals("North Hub"))) {
            warehouses.add(warehouseRepository.save(new Warehouse("North Hub", "Delhi", new BigDecimal("1.25"))));
        }

        // ------------------------------------------------------------------ catalog
        Map<String, Product> byName = productRepository.findAll().stream().collect(Collectors.toMap(Product::getName, p -> p, (a, b) -> a));
        List<Product> hardware = new ArrayList<>(), services = new ArrayList<>(), subscriptions = new ArrayList<>();
        Map<Product, List<Product>> accessoriesFor = new HashMap<>();   // laptop/desktop -> accessories (cross-sell)
        Map<Product, Product> upgradeFor = new HashMap<>();             // basic -> premium (product upgrade)
        Map<Product, Product> addOnFor = new HashMap<>();               // subscription -> add-on (upsell)
        int created = 0;

        // Hardware: brands x models per line, three tiers each where sensible
        String[][] laptops = {{"Dell", "Latitude 5440"}, {"Dell", "XPS 15"}, {"HP", "EliteBook 840"}, {"HP", "ProBook 450"}, {"Lenovo", "ThinkPad T14"},
                {"Lenovo", "IdeaPad 5"}, {"Apple", "MacBook Air M3"}, {"Apple", "MacBook Pro 14"}, {"Asus", "ZenBook 14"}, {"Acer", "TravelMate P4"},
                {"Microsoft", "Surface Laptop 6"}, {"Samsung", "Galaxy Book4 Pro"}, {"MSI", "Modern 14"}, {"LG", "Gram 16"}, {"Dynabook", "Portege X40"},
                {"Fujitsu", "LifeBook U9"}, {"Toshiba", "Tecra A50"}, {"Acer", "Swift Go 14"}, {"Asus", "ExpertBook B5"}, {"Lenovo", "Yoga Slim 7"}};
        List<Product> laptopBasics = new ArrayList<>(), laptopPros = new ArrayList<>();
        for (String[] l : laptops) {
            Product basic = product(byName, l[0] + " " + l[1] + " Laptop", "Hardware", price(catalogRnd, 700, 1400), 0.78, "unit", l[1] + " - 8 GB RAM, 256 GB SSD");
            Product pro = product(byName, l[0] + " " + l[1] + " Laptop (Pro, 16 GB / 1 TB)", "Hardware", basic.getPrice().multiply(new BigDecimal("1.35")).setScale(0, RoundingMode.HALF_UP), 0.74, "unit", l[1] + " - 16 GB RAM, 1 TB SSD, 3-year warranty");
            laptopBasics.add(basic); laptopPros.add(pro); hardware.add(basic); hardware.add(pro);
            upgradeFor.put(basic, pro);
        }
        String[] monitorSizes = {"24", "27", "32", "34 ultrawide", "49 super-ultrawide"};
        String[] monitorBrands = {"Dell", "LG", "Samsung", "BenQ", "Philips", "Acer", "ViewSonic", "AOC"};
        List<Product> monitors = new ArrayList<>();
        for (String b : monitorBrands) for (String sz : monitorSizes) {
            monitors.add(product(byName, b + " " + sz + "-inch Monitor", "Hardware", price(catalogRnd, 150, 650), 0.72, "unit", sz + "-inch display, USB-C"));
        }
        hardware.addAll(monitors);
        List<Product> accessories = new ArrayList<>();
        String[][] accessoryDefs = {{"Wireless Mouse Pro", "18", "45"}, {"Bluetooth Keyboard", "35", "90"}, {"USB-C Docking Station", "90", "220"}, {"Laptop Backpack", "30", "80"},
                {"Laptop Sleeve", "15", "35"}, {"Noise-cancelling Headset", "80", "260"}, {"HD Webcam", "40", "120"}, {"USB-C Hub 7-in-1", "25", "60"},
                {"Wireless Presenter", "20", "45"}, {"Laptop Stand", "25", "70"}, {"65W USB-C Charger", "30", "60"}, {"Privacy Screen Filter", "25", "55"},
                {"External SSD 1TB", "80", "150"}, {"External SSD 2TB", "140", "260"}, {"Portable Monitor 15.6", "150", "300"},
                {"Wireless Trackball Mouse", "30", "70"}, {"Mechanical Keyboard TKL", "60", "140"}, {"Thunderbolt 4 Dock", "150", "320"}, {"Laptop Lock Cable", "15", "30"},
                {"Ring Light for Video Calls", "20", "55"}, {"Portable SSD 500GB", "45", "90"}, {"Bluetooth Speaker Small", "30", "80"}, {"Ergonomic Wrist Rest", "10", "25"},
                {"Cable Management Kit", "10", "25"}, {"Multi-port Travel Adapter", "20", "40"}};
        String[] accBrands = {"Logitech", "Anker", "Targus", "Belkin", "Jabra"};
        for (String[] a : accessoryDefs) for (int i = 0; i < 3; i++) {
            // Deterministic names (never depend on what already exists) so a re-run of the catalog
            // section finds every product by name instead of inventing a new one.
            String brand = accBrands[(catalogRnd.nextInt(accBrands.length) + i) % accBrands.length];
            String name = brand + " " + a[0] + (i == 0 ? "" : " (gen " + (i + 1) + ")");
            accessories.add(product(byName, name, "Hardware", price(catalogRnd, Integer.parseInt(a[1]), Integer.parseInt(a[2])), 0.6, "unit", a[0]));
        }
        hardware.addAll(accessories);
        String[][] infra = {{"Cisco Catalyst 9200 Switch 24-port", "1800", "2600"}, {"Cisco Catalyst 9200 Switch 48-port", "3200", "4200"}, {"Ubiquiti Access Point WiFi 6", "150", "260"},
                {"Fortinet FortiGate 60F Firewall", "600", "900"}, {"Dell PowerEdge R650 Server", "4500", "7000"}, {"HPE ProLiant DL380 Server", "5000", "8000"},
                {"Synology 8-bay NAS", "900", "1400"}, {"APC Smart-UPS 1500VA", "500", "800"}, {"APC Smart-UPS 3000VA", "1100", "1600"}, {"HP LaserJet Pro Printer", "250", "450"},
                {"Canon imageRUNNER Copier", "1800", "3000"}, {"Zebra Label Printer", "300", "500"}, {"Honeywell Barcode Scanner", "120", "260"}, {"Samsung Galaxy Tab S9", "550", "900"},
                {"Apple iPad 10th gen", "380", "520"}, {"Samsung Galaxy S24", "650", "950"}, {"Google Pixel 8", "550", "800"}, {"Polycom Conference Phone", "400", "700"},
                {"Logitech Rally Video Bar", "1500", "2400"}, {"Samsung 65-inch Meeting Room Display", "900", "1500"}, {"Standing Desk (electric)", "350", "600"},
                {"Ergonomic Office Chair Pro", "200", "450"}, {"Rack Cabinet 42U", "700", "1100"}, {"Cat6 Patch Cable Pack (50)", "40", "80"}, {"KVM Switch 8-port", "150", "300"},
                {"Server RAM 32GB DDR5", "120", "220"}, {"Enterprise SSD 3.84TB", "400", "700"}, {"Ruckus Wireless Controller", "1200", "2000"}, {"Meraki MX68 Security Appliance", "900", "1400"}, {"Dell Precision Workstation", "2200", "3500"}, {"HP Z2 Mini Workstation", "1200", "1900"},
                {"Lenovo ThinkCentre Desktop", "600", "950"}, {"Dell OptiPlex Desktop", "550", "900"}, {"Apple Mac mini M2", "600", "800"}, {"Intel NUC Mini PC", "450", "700"},
                {"Epson EcoTank Printer", "200", "350"}, {"Brother Mono Laser Printer", "150", "280"}, {"Fujitsu Document Scanner", "350", "600"}, {"Wacom Drawing Tablet", "250", "450"},
                {"Sony 4K Projector", "1200", "2200"}, {"Epson Meeting Room Projector", "600", "1100"}, {"Bose Conference Speaker", "300", "500"}, {"Jabra Speak Speakerphone", "120", "260"},
                {"TP-Link 16-port PoE Switch", "180", "320"}, {"Netgear 5G Router", "300", "500"}, {"Seagate 8TB Backup Drive", "150", "260"}, {"LaCie Rugged 4TB", "150", "280"}, {"Yubikey Security Key (10-pack)", "400", "550"},
                {"Aruba Instant On Switch 24-port", "900", "1400"}, {"Palo Alto PA-220 Firewall", "1500", "2400"}, {"Netgear ReadyNAS 4-bay", "500", "800"}, {"CyberPower UPS 1000VA", "180", "300"},
                {"Brother Label Printer QL-820", "150", "260"}, {"Datalogic Handheld Scanner", "150", "300"}, {"Microsoft Surface Hub 2S", "4500", "7000"}, {"Cisco Webex Room Kit", "3500", "6000"},
                {"Poly Studio Video Bar", "1200", "2000"}, {"Dell UltraSharp Color Calibrator", "200", "400"}, {"Rack PDU 24-outlet", "250", "450"}, {"Fiber Patch Panel 24-port", "80", "150"},
                {"Server RAM 64GB DDR5", "220", "380"}, {"Enterprise SSD 7.68TB", "700", "1200"}, {"Extreme Networks Access Point", "300", "500"}, {"Barracuda Email Security Gateway", "1000", "1800"},
                {"Lenovo ThinkStation Workstation", "2500", "4000"}, {"Asus ExpertCenter Mini PC", "500", "800"}, {"HP EliteDesk Desktop", "600", "950"}, {"Canon PIXMA Business Printer", "180", "320"},
                {"Epson WorkForce Pro Printer", "220", "380"}, {"HP Scanjet Document Scanner", "300", "500"}, {"Wacom Cintiq Pro Tablet", "1200", "2000"}, {"BenQ Meeting Room Projector", "700", "1200"},
                {"Sennheiser Conference Microphone", "350", "600"}, {"Yealink Conference Speakerphone", "150", "300"}, {"D-Link 24-port Managed Switch", "300", "500"}, {"Asus 5G Mesh Router", "250", "450"},
                {"Western Digital 10TB Backup Drive", "180", "300"}, {"G-Technology Rugged 5TB", "180", "320"}, {"RSA SecurID Hardware Token (10-pack)", "350", "500"}};
        for (String[] d : infra) hardware.add(product(byName, d[0], "Hardware", price(catalogRnd, Integer.parseInt(d[1]), Integer.parseInt(d[2])), 0.7, "unit", d[0]));
        for (Product basic : laptopBasics) accessoriesFor.put(basic, pick(catalogRnd, accessories, 4));
        for (Product pro : laptopPros) accessoriesFor.put(pro, pick(catalogRnd, accessories, 4));
        for (Product m : monitors) accessoriesFor.put(m, pick(catalogRnd, accessories, 2));

        // Services
        String[] serviceKinds = {"Installation & Setup", "Network Cabling", "Data Migration", "Security Audit", "Onboarding Training", "Admin Training (advanced)",
                "Managed Support - Bronze", "Managed Support - Silver", "Managed Support - Gold", "Cloud Migration Assessment", "Disaster Recovery Planning", "Hardware Refresh Consulting",
                "Rack & Stack (per rack)", "Asset Tagging", "E-waste Disposal", "Warranty Extension 1 year", "Warranty Extension 3 years", "On-site Engineer (per day)",
                "Remote Helpdesk Hours (10-pack)", "Penetration Test", "Office Move IT Service", "Printer Fleet Setup", "Video Conferencing Room Setup", "Wi-Fi Site Survey", "Firewall Configuration",
                "Server Health Check", "Backup Restore Drill", "Email Migration", "Endpoint Rollout (per 50 devices)", "IT Policy Workshop",
                "Cloud Cost Optimization Review", "Identity & Access Setup", "SD-WAN Rollout", "Database Performance Tuning", "API Integration Engagement",
                "Compliance Readiness Audit", "Change Management Workshop", "Legacy System Decommission", "Remote Site Survey", "Vendor Migration Support"};
        String[] serviceScopes = {"Small office", "Mid-size", "Enterprise"};
        for (String k : serviceKinds) for (String sc : serviceScopes) {
            int base = 150 + catalogRnd.nextInt(900);
            int mult = sc.equals("Small office") ? 1 : sc.equals("Mid-size") ? 2 : 4;
            services.add(product(byName, k + " - " + sc, "Service", BigDecimal.valueOf((long) base * mult), 0.45, "engagement", k + " for a " + sc.toLowerCase() + " environment"));
        }

        // Subscriptions: SaaS families in Basic / Pro / Enterprise tiers, each with monthly + yearly plans
        String[] saas = {"CRM Cloud", "HR Suite", "Backup Vault", "Endpoint Security", "Email & Collaboration", "Project Tracker", "Helpdesk Desk", "BI Analytics",
                "E-signature", "Password Manager", "Cloud Storage", "VPN Access", "Payroll Cloud", "Inventory Cloud", "Marketing Automation", "Video Meetings",
                "Document Management", "Expense Manager", "Learning Platform", "Contract Lifecycle", "Fleet Tracking", "Field Service", "Data Warehouse", "API Gateway", "Monitoring & Alerts",
                "Identity Management", "DevOps Pipeline", "Customer Support Desk", "Survey & Feedback", "Asset Management Cloud", "Procurement Suite", "Time Tracking",
                "Knowledge Base", "Chat & Messaging", "Website Builder"};
        Map<String, List<Product>> saasTiers = new LinkedHashMap<>();
        for (String s : saas) {
            int basePrice = 8 + catalogRnd.nextInt(30);
            Product basic = product(byName, s + " - Basic", "Subscription", BigDecimal.valueOf(basePrice), 0.4, "seat/month", s + " starter tier");
            Product pro = product(byName, s + " - Pro", "Subscription", BigDecimal.valueOf(basePrice * 2), 0.35, "seat/month", s + " with automation and integrations");
            Product ent = product(byName, s + " - Enterprise", "Subscription", BigDecimal.valueOf(basePrice * 4), 0.32, "seat/month", s + " with SSO, audit logs and SLA");
            for (Product p : List.of(basic, pro, ent)) {
                subscriptions.add(p);
                ensurePlan(p.getName() + " (Monthly)", p, BillingCycle.MONTHLY, p.getPrice());
                ensurePlan(p.getName() + " (Yearly)", p, BillingCycle.YEARLY, p.getPrice().multiply(BigDecimal.valueOf(10)));
            }
            upgradeFor.put(basic, pro);
            upgradeFor.put(pro, ent);
            saasTiers.put(s, List.of(basic, pro, ent));
        }
        // add-ons as upsells
        Product analytics = product(byName, "Advanced Analytics Add-on", "Subscription", new BigDecimal("15.00"), 0.3, "seat/month", "Advanced reporting add-on");
        Product premiumSupport = product(byName, "Premium Support Add-on", "Subscription", new BigDecimal("12.00"), 0.3, "seat/month", "24x7 priority support");
        for (Product p : List.of(analytics, premiumSupport)) {
            subscriptions.add(p);
            ensurePlan(p.getName() + " (Monthly)", p, BillingCycle.MONTHLY, p.getPrice());
        }
        for (List<Product> tiers : saasTiers.values()) { addOnFor.put(tiers.get(0), analytics); addOnFor.put(tiers.get(1), catalogRnd.nextBoolean() ? analytics : premiumSupport); }
        summary.products = (int) productRepository.count();
        summary.plans = (int) subscriptionPlanRepository.count();

        // ------------------------------------------------------------------ stock
        Set<String> stocked = stockLevelRepository.findAll().stream().map(s -> s.getWarehouse().getId() + ":" + s.getProduct().getId()).collect(Collectors.toSet());
        for (Product p : hardware) {
            for (Warehouse w : warehouses) {
                if (catalogRnd.nextInt(10) < 3) continue; // not every warehouse carries everything - that is what makes splits happen
                if (stocked.contains(w.getId() + ":" + p.getId())) continue;
                int qty = p.getPrice().compareTo(BigDecimal.valueOf(1500)) > 0 ? 5 + catalogRnd.nextInt(20) : 20 + catalogRnd.nextInt(200);
                stockLevelRepository.save(new StockLevel(w, p, qty, Math.max(3, qty / 8)));
            }
        }

        // ------------------------------------------------------------------ tier price lists
        int priceRows = 0;
        for (Product p : pick(catalogRnd, hardware, 45)) {
            if (priceListEntryRepository.findByTierAndProductId(CustomerTier.GOLD, p.getId()).isEmpty()) {
                priceListEntryRepository.save(new PriceListEntry(CustomerTier.GOLD, p, p.getPrice().multiply(new BigDecimal("0.95")).setScale(2, RoundingMode.HALF_UP))); priceRows++;
            }
        }
        for (Product p : pick(catalogRnd, hardware, 25)) {
            if (priceListEntryRepository.findByTierAndProductId(CustomerTier.SILVER, p.getId()).isEmpty()) {
                priceListEntryRepository.save(new PriceListEntry(CustomerTier.SILVER, p, p.getPrice().multiply(new BigDecimal("0.975")).setScale(2, RoundingMode.HALF_UP))); priceRows++;
            }
        }
        summary.priceListEntries = priceRows;

        // ------------------------------------------------------------------ recommendation rules
        int rules = 0;
        for (Map.Entry<Product, Product> e : upgradeFor.entrySet()) {
            rules += rule(e.getKey(), e.getValue(), RecommendationType.PRODUCT_UPGRADE, 85 + catalogRnd.nextInt(15), "20", catalogRnd.nextInt(3) == 0, "Recommended Upgrade",
                    e.getKey().getCategory().equals("Subscription") ? "Automation, integrations and a higher SLA" : "More memory, more storage, longer warranty");
        }
        for (Map.Entry<Product, List<Product>> e : accessoriesFor.entrySet()) {
            int prio = 80;
            for (Product acc : e.getValue()) {
                rules += rule(e.getKey(), acc, RecommendationType.CROSS_SELL, prio, "25", prio >= 80, prio >= 80 ? "Bundle offer" : null, "Frequently bought together with " + e.getKey().getName());
                prio -= 10;
            }
        }
        for (Map.Entry<Product, Product> e : addOnFor.entrySet()) {
            rules += rule(e.getKey(), e.getValue(), RecommendationType.UPSELL, 70, "20", true, "Popular add-on", "Most " + e.getKey().getName() + " customers add this within the first quarter");
        }
        Product installSmall = byName.get("Installation & Setup - Small office");
        Product installMid = byName.get("Installation & Setup - Mid-size");
        for (Product lp : laptopPros) if (installSmall != null) rules += rule(lp, installSmall, RecommendationType.UPSELL, 55, "20", false, null, "On-site setup for the new fleet");
        for (Product inf : hardware) if (inf.getName().contains("Server") && installMid != null) rules += rule(inf, installMid, RecommendationType.CROSS_SELL, 75, "20", true, "Recommended", "Servers ship with rack & configuration service");
        summary.rules = rules;

        // ------------------------------------------------------------------ extra products (manual batch only - never forced)
        summary.newProducts = extraProducts > 0 ? generateExtraProducts(rnd, extraProducts, warehouses) : 0;

        // Quotations draw from EVERYTHING sellable in the catalog (including products from earlier batches).
        hardware.clear(); services.clear(); subscriptions.clear();
        for (Product p : productRepository.findAll()) {
            if (!p.isActive()) continue;
            switch (p.getCategory()) {
                case "Hardware" -> hardware.add(p);
                case "Service" -> services.add(p);
                case "Subscription" -> subscriptions.add(p);
                default -> hardware.add(p);
            }
        }
        summary.products = (int) productRepository.count();

        // ------------------------------------------------------------------ quotations (batch)
        List<SubscriptionPlan> allPlans = subscriptionPlanRepository.findAll();
        Map<Long, List<SubscriptionPlan>> plansByProduct = allPlans.stream().collect(Collectors.groupingBy(p -> p.getProduct().getId()));
        Map<Long, AppUser> repOfCustomer = new HashMap<>();
        for (Quotation existing : quotationRepository.findAll()) repOfCustomer.putIfAbsent(existing.getCustomer().getId(), existing.getSalesRep());
        int made = 0;
        LocalDateTime now = LocalDateTime.now();
        for (int i = 0; i < quotationCount; i++) {
            Customer customer = customers.get(rnd.nextInt(customers.size()));
            AppUser rep = repOfCustomer.computeIfAbsent(customer.getId(), id -> reps.get(rnd.nextInt(reps.size())));
            if (rnd.nextInt(8) == 0) rep = reps.get(rnd.nextInt(reps.size())); // occasionally another rep picks the account up
            long daysAgo = (long) Math.floor(Math.pow(rnd.nextDouble(), 1.3) * 180); // more recent deals than old ones
            LocalDateTime createdAt = now.minusDays(daysAgo).minusHours(rnd.nextInt(9)).minusMinutes(rnd.nextInt(60));

            // target status mix: 50% confirmed, the rest spread over the open states
            int roll = rnd.nextInt(100);
            String target = roll < 50 ? "CONFIRMED" : roll < 62 ? "DRAFT" : roll < 72 ? "PENDING_APPROVAL" : roll < 82 ? "APPROVED" : roll < 92 ? "UNDER_NEGOTIATION" : "REJECTED";
            if (daysAgo > 60 && !target.equals("CONFIRMED") && rnd.nextInt(3) > 0) target = rnd.nextInt(5) == 0 ? "REJECTED" : "CONFIRMED"; // old deals are mostly settled
            // a deal can only be waiting for / refused by an approver if something is over its ceiling
            boolean overCeiling = target.equals("PENDING_APPROVAL") || target.equals("REJECTED") || rnd.nextInt(4) == 0;

            Quotation q = quotationService.createQuotation(customer.getId(), rep.getUsername());
            int lineCount = 1 + rnd.nextInt(4);
            Set<Long> used = new HashSet<>();
            for (int l = 0; l < lineCount; l++) {
                int kind = rnd.nextInt(10);
                Product p = kind < 6 ? hardware.get(rnd.nextInt(hardware.size())) : kind < 8 ? services.get(rnd.nextInt(services.size())) : subscriptions.get(rnd.nextInt(subscriptions.size()));
                if (!used.add(p.getId())) continue;
                AddLineRequest req = new AddLineRequest();
                req.productId = p.getId();
                req.quantity = p.getCategory().equals("Subscription") ? 5 + rnd.nextInt(60) : p.getCategory().equals("Service") ? 1 + rnd.nextInt(2)
                        : p.getPrice().compareTo(BigDecimal.valueOf(1000)) > 0 ? 1 + rnd.nextInt(6) : 1 + rnd.nextInt(25);
                BigDecimal ceiling = ceilingFor(customer.getTier(), p.getCategory());
                BigDecimal disc = overCeiling && (l == 0 || rnd.nextBoolean())
                        ? ceiling.add(BigDecimal.valueOf(2 + rnd.nextInt(10)))
                        : BigDecimal.valueOf(rnd.nextInt(Math.max(1, ceiling.intValue() + 1)));
                req.discountPercent = disc.setScale(2, RoundingMode.HALF_UP);
                if (p.getCategory().equals("Subscription")) {
                    req.lineType = QuotationLine.LineType.RECURRING;
                    List<SubscriptionPlan> pl = plansByProduct.getOrDefault(p.getId(), List.of());
                    if (pl.isEmpty()) continue;
                    req.subscriptionPlanId = pl.get(rnd.nextInt(pl.size())).getId();
                }
                quotationService.addLine(q.getId(), req);
            }
            q = quotationService.getEntity(q.getId());
            if (q.getLines().isEmpty()) { quotationRepository.delete(q); continue; }

            LocalDateTime updatedAt = createdAt.plusHours(1 + rnd.nextInt(72));

            try {
                switch (target) {
                    case "DRAFT" -> { /* leave as built */ }
                    case "PENDING_APPROVAL" -> {
                        quotationService.submitForApproval(q.getId(), rep.getUsername());
                        // if it came back clean it is simply APPROVED - fine, it still counts as a live deal
                    }
                    case "APPROVED", "CONFIRMED", "UNDER_NEGOTIATION" -> {
                        quotationService.submitForApproval(q.getId(), rep.getUsername());
                        q = quotationService.getEntity(q.getId());
                        approveThrough(q, rep);
                        q = quotationService.getEntity(q.getId());
                        if (target.equals("UNDER_NEGOTIATION") && q.getStatus() == Quotation.Status.APPROVED) {
                            QuotationLine line = q.getLines().get(0);
                            NegotiationMessageRequest counter = new NegotiationMessageRequest();
                            counter.messageType = "COUNTER_DISCOUNT";
                            counter.quotationLineId = line.getId();
                            counter.proposedDiscountPercent = line.getDiscountPercent().add(BigDecimal.valueOf(2 + rnd.nextInt(6)));
                            counter.content = pick(rnd, List.of("Can you do a little better on this line?", "Our budget needs a bigger discount here.", "Competitor quoted lower - can you match?"));
                            quotationService.addNegotiationMessage(q.getId(), counter, "CUSTOMER", customer.getName());
                            if (rnd.nextBoolean()) {
                                NegotiationMessageRequest reply = new NegotiationMessageRequest();
                                reply.messageType = "COMMENT";
                                reply.content = "Let me check with my manager and come back to you.";
                                quotationService.addNegotiationMessage(q.getId(), reply, "SALES_REP", rep.getFullName());
                            }
                        } else if (target.equals("CONFIRMED") && q.getStatus() == Quotation.Status.APPROVED) {
                            quotationService.confirmQuotation(q.getId(), rnd.nextBoolean() ? rep.getUsername() : customer.getName() + " (customer portal)");
                            q = quotationService.getEntity(q.getId());
                            // older confirmed orders: invoices paid, shipments delivered
                            if (daysAgo > 10 || rnd.nextInt(3) == 0) {
                                billingService.recordPayment(q.getId(), null, true, "PAY-" + (100000 + rnd.nextInt(900000)), "finance");
                            }
                            for (FulfillmentSplit split : fulfillmentSplitRepository.findByQuotationId(q.getId())) {
                                split.setExpectedDeliveryDate(createdAt.toLocalDate().plusDays(3 + rnd.nextInt(5)));
                                if (daysAgo > 7 && rnd.nextInt(10) < 9) split.setDelivered(true);
                                fulfillmentSplitRepository.save(split);
                            }
                            for (BillingScheduleEntry entry : billingScheduleEntryRepository.findByQuotationLine_Quotation_IdOrderByBillingDateAsc(q.getId())) {
                                entry.setBillingDate(entry.getBillingDate().minusDays(daysAgo));
                                if (entry.getPaidAt() != null) entry.setPaidAt(entry.getPaidAt().minusDays(daysAgo).plusDays(rnd.nextInt(5)));
                                billingScheduleEntryRepository.save(entry);
                            }
                        }
                    }
                    case "REJECTED" -> {
                        quotationService.submitForApproval(q.getId(), rep.getUsername());
                        q = quotationService.getEntity(q.getId());
                        if (q.getStatus() == Quotation.Status.PENDING_APPROVAL) {
                            quotationService.rejectStep(q.getId(), q.getCurrentApprovalStep() == Quotation.ApprovalStep.FINANCE ? Role.FINANCE : Role.SALES_MANAGER,
                                    q.getCurrentApprovalStep() == Quotation.ApprovalStep.FINANCE ? "finance" : "manager",
                                    pick(rnd, List.of("Discount too deep for this tier", "Margin below policy on the service line", "Please revise - customer history doesn't justify this level")));
                        }
                    }
                    default -> { }
                }
            } catch (RuntimeException ex) {
                log.warn("Demo quotation #{} stopped at {}: {}", q.getId(), target, ex.getMessage());
            }

            Quotation saved = quotationRepository.findById(q.getId()).orElseThrow();
            saved.setCreatedAt(createdAt);
            saved.setUpdatedAt(saved.getStatus() == Quotation.Status.CONFIRMED ? updatedAt.plusDays(rnd.nextInt(3)) : updatedAt);
            if (saved.getConfirmedAt() != null) saved.setConfirmedAt(saved.getUpdatedAt());
            quotationRepository.save(saved);
            made++;
        }
        summary.quotations = made;
        summary.totalQuotations = (int) quotationRepository.count();
        summary.millis = System.currentTimeMillis() - start;
        auditService.log("System", 0L, "DEMO_DATA_LOADED", actor,
                "+" + summary.quotations + " quotations (now " + summary.totalQuotations + "), +" + summary.newProducts + " products (now " + summary.products + "), "
                        + "+" + summary.newCustomers + " customers (now " + summary.customers + "), +" + summary.newReps + " reps (now " + summary.salesReps + "), "
                        + summary.rules + " new rules in " + summary.millis + " ms");
        log.info("Demo data loaded: +{} quotations (now {}), +{} products (now {}), +{} customers (now {}), +{} reps (now {}), +{} rules ({} ms)",
                summary.quotations, summary.totalQuotations, summary.newProducts, summary.products, summary.newCustomers, summary.customers,
                summary.newReps, summary.salesReps, summary.rules, summary.millis);
        return summary;
    }

    /**
     * Manually generates {@code count} more products by varying the existing catalog - a base
     * product in an edition/pack/refurbished variant with a nearby price, same category
     * (subscriptions get their own plans, hardware gets stock). Names are unique, so repeated calls
     * keep adding.
     */
    private int generateExtraProducts(Random rnd, int count, List<Warehouse> warehouses) {
        List<Product> bases = productRepository.findAll().stream().filter(Product::isActive).collect(Collectors.toList());
        if (bases.isEmpty()) return 0;
        String[] suffixes = {"2025 edition", "Refurbished", "Bulk pack of 5", "Education edition", "Enterprise edition", "Extended-warranty bundle",
                "Trade-in offer", "Regional variant", "Colour: Space Grey", "Colour: Silver", "Lite", "Plus", "Max", "Bundle with support", "Government edition"};
        Set<String> names = bases.stream().map(Product::getName).collect(Collectors.toSet());
        int created = 0, k = (int) productRepository.count();
        while (created < count) {
            Product base = bases.get(rnd.nextInt(bases.size()));
            String suffix = suffixes[rnd.nextInt(suffixes.length)];
            String name = base.getName() + " (" + suffix + ")";
            int n = 2;
            while (names.contains(name)) name = base.getName() + " (" + suffix + " #" + (n++) + ")";
            double factor = suffix.equals("Refurbished") || suffix.equals("Lite") ? 0.7 + rnd.nextDouble() * 0.15
                    : suffix.startsWith("Bulk") ? 4.5 : 0.9 + rnd.nextDouble() * 0.4;
            BigDecimal price = base.getPrice().multiply(BigDecimal.valueOf(factor)).setScale(2, RoundingMode.HALF_UP).max(BigDecimal.ONE);
            double costRatio = base.getPrice().signum() > 0 ? base.getCost().doubleValue() / base.getPrice().doubleValue() : 0.6;
            Product p = productRepository.save(new Product(name, base.getCategory(), price,
                    price.multiply(BigDecimal.valueOf(Math.min(0.95, costRatio))).setScale(2, RoundingMode.HALF_UP),
                    base.getUnit(), base.getTaxPercent(), (base.getDescription() == null ? base.getName() : base.getDescription()) + " - " + suffix.toLowerCase()));
            names.add(name);
            if ("Subscription".equals(p.getCategory())) {
                ensurePlan(p.getName() + " (Monthly)", p, BillingCycle.MONTHLY, p.getPrice());
                if (rnd.nextBoolean()) ensurePlan(p.getName() + " (Yearly)", p, BillingCycle.YEARLY, p.getPrice().multiply(BigDecimal.valueOf(10)));
            } else if ("Hardware".equals(p.getCategory())) {
                for (Warehouse w : warehouses) {
                    if (rnd.nextInt(10) < 3) continue;
                    stockLevelRepository.save(new StockLevel(w, p, 10 + rnd.nextInt(120), 5));
                }
            }
            // keep the recommendation graph growing too: the variant is a cross-sell of its base and vice versa is not needed
            if (rnd.nextInt(3) == 0) rule(base, p, RecommendationType.CROSS_SELL, 40 + rnd.nextInt(30), "15", false, null, "Alternative edition of " + base.getName());
            created++;
            k++;
        }
        return created;
    }

    // ------------------------------------------------------------------ helpers

    private void approveThrough(Quotation q, AppUser rep) {
        int guard = 0;
        while (q.getStatus() == Quotation.Status.PENDING_APPROVAL && guard++ < 3) {
            boolean finance = q.getCurrentApprovalStep() == Quotation.ApprovalStep.FINANCE;
            quotationService.approveStep(q.getId(), finance ? Role.FINANCE : Role.SALES_MANAGER, finance ? "finance" : "manager",
                    finance ? "Margin acceptable for the account" : "Approved - strategic account");
            q = quotationService.getEntity(q.getId());
        }
    }

    private BigDecimal ceilingFor(CustomerTier tier, String category) {
        // Mirrors the seeded ceilings (DiscountRiskService is the authority at runtime; this only shapes the demo mix).
        return switch (tier) {
            case GOLD -> category.equals("Service") ? BigDecimal.valueOf(10) : category.equals("Subscription") ? BigDecimal.valueOf(12) : BigDecimal.valueOf(15);
            case SILVER -> category.equals("Service") ? BigDecimal.valueOf(7) : category.equals("Subscription") ? BigDecimal.valueOf(8) : BigDecimal.valueOf(10);
            default -> category.equals("Service") ? BigDecimal.valueOf(3) : category.equals("Subscription") ? BigDecimal.valueOf(4) : BigDecimal.valueOf(5);
        };
    }

    // Word lists used only to build combinatorial, deterministic names (companies, reps) for the
    // MANUAL "extra customers" / "extra reps" batches - large enough (8000 / 400 combos) that you
    // can add as many as you like, as many times as you like, without ever repeating a name.
    private static final String[] COMPANY_FIRST = {
            "Nimbus", "Orion", "Vega", "Helios", "Kestrel", "Juniper", "Meridian", "Sable", "Quantum", "Harbor",
            "Zenith", "Aster", "Cobalt", "Lumen", "Pinnacle", "Summit", "Tidewater", "Cascade", "Ember", "Falcon",
            "Granite", "Ivory", "Jade", "Kite", "Lotus", "Mosaic", "Nectar", "Onyx", "Prism", "Quill",
            "Ridge", "Solace", "Terra", "Umbra", "Verdant", "Willow", "Xenon", "Yarrow", "Zephyr", "Atlas",
            "Beacon", "Cedar", "Dune", "Echo", "Fable", "Glacier", "Horizon", "Iris", "Juno", "Kraken",
            "Lark", "Maple", "Nova", "Opal", "Pioneer", "Quartz", "Raven", "Sierra", "Topaz", "Ultra",
            "Vertex", "Wisteria", "Xylo", "Yonder", "Zodiac", "Amber", "Bramble", "Copper", "Driftwood", "Elm",
            "Frost", "Gale", "Hollow", "Indigo", "Jasper", "Kindle", "Lattice", "Marble", "Nectarine", "Obsidian",
            "Palisade", "Rowan", "Slate", "Thistle", "Ursa", "Violet", "Wren", "Xerus", "Yeoman", "Zinnia",
            "Ashgrove", "Birchwood", "Clearwater", "Dovetail", "Everglade", "Foxglove", "Goldenrod", "Hazelwood", "Ironwood", "Silverline"
    };
    private static final String[] COMPANY_TYPE = {
            "Logistics", "Retail", "Pharma", "Energy", "Media", "Foods", "Bank", "Textiles", "Labs", "Freight Lines",
            "Hotels", "Healthcare", "Mining", "Studios", "Realty", "Insurance", "Marine", "Software", "Restaurants", "Aviation",
            "Construction", "Cosmetics", "Jewellers", "Education", "Wellness", "Design", "Beverages", "Security", "Analytics", "Publishing",
            "Automotive", "Care Homes", "Agritech", "Lighting", "Landscaping", "Furniture", "Semiconductors", "Organics", "Airlines", "Freight",
            "Telecom", "Legal", "Apparel", "Audio", "Toys", "Water", "Travel", "Optics", "Fitness", "Gaming",
            "Bakery", "Dental", "Robotics", "Events", "Steel", "Watches", "Books", "Outdoor", "Interiors", "Motors",
            "Consulting", "Systems", "Solutions", "Ventures", "Holdings", "Partners", "Group", "Industries", "Enterprises", "Technologies",
            "Foundries", "Distillers", "Cargo", "Shipping", "Packaging", "Print", "Dairy", "Timber", "Metals", "Robotics Labs"
    };
    private static final String[] REP_FIRST = {
            "Ananya", "Vikram", "Meera", "Arjun", "Sneha", "Rohan", "Kavya", "Aditya", "Priya", "Karan",
            "Neha", "Rahul", "Divya", "Sanjay", "Pooja", "Amit", "Riya", "Varun", "Isha", "Nikhil"
    };
    private static final String[] REP_LAST = {
            "Verma", "Nair", "Iyer", "Kapoor", "Reddy", "Desai", "Menon", "Joshi", "Sharma", "Gupta",
            "Malhotra", "Chatterjee", "Rao", "Pillai", "Bhatt", "Choudhary", "Sinha", "Kulkarni", "Bose", "Agarwal"
    };

    /** Shuffles a fixed-seed cartesian product so the same N names always come out, none repeated. */
    private static List<String> combinatorialNames(String[] first, String[] second, long seed, int count) {
        List<String> combos = new ArrayList<>(first.length * second.length);
        for (String f : first) for (String s : second) combos.add(f + " " + s);
        Collections.shuffle(combos, new Random(seed));
        return combos.subList(0, Math.min(count, combos.size()));
    }

    private List<AppUser> ensureSalesReps() {
        // rep1/rep2 are also created by DataSeeder before this runs (findByUsername picks them up unchanged).
        String[][] defs = {{"rep1", "Ananya Verma"}, {"rep2", "Vikram Nair"}, {"rep3", "Meera Iyer"}, {"rep4", "Arjun Kapoor"}, {"rep5", "Sneha Reddy"},
                {"rep6", "Rohan Desai"}, {"rep7", "Kavya Menon"}, {"rep8", "Aditya Joshi"}};
        List<AppUser> reps = new ArrayList<>();
        for (String[] d : defs) {
            reps.add(appUserRepository.findByUsername(d[0]).orElseGet(() ->
                    appUserRepository.save(new AppUser(d[0], passwordEncoder.encode("rep123"), d[1], d[0] + "@dealflow360.com", Role.SALES_REP))));
        }
        return reps;
    }

    private List<Customer> ensureCustomers() {
        String[] names = {"Nimbus Logistics", "Orion Retail", "Vega Pharma", "Helios Energy", "Kestrel Media", "Juniper Foods", "Meridian Bank", "Sable Textiles",
                "Quantum Labs", "Harbor Freight Lines", "Zenith Hotels", "Aster Healthcare", "Cobalt Mining", "Lumen Studios", "Pinnacle Realty", "Summit Insurance",
                "Tidewater Marine", "Cascade Software", "Ember Restaurants", "Falcon Aviation", "Granite Construction", "Ivory Cosmetics", "Jade Jewellers", "Kite Education",
                "Lotus Wellness", "Mosaic Design", "Nectar Beverages", "Onyx Security", "Prism Analytics", "Quill Publishing", "Ridge Automotive", "Solace Care Homes",
                "Terra Agritech", "Umbra Lighting", "Verdant Landscaping", "Willow Furniture", "Xenon Semiconductors", "Yarrow Organics", "Zephyr Airlines", "Atlas Freight",
                "Beacon Telecom", "Cedar Legal", "Dune Apparel", "Echo Audio", "Fable Toys", "Glacier Water", "Horizon Travel", "Iris Optics", "Juno Fitness", "Kraken Gaming",
                "Lark Bakery", "Maple Dental", "Nova Robotics", "Opal Events", "Pioneer Steel", "Quartz Watches", "Raven Books", "Sierra Outdoor", "Topaz Interiors", "Ultra Motors"};
        List<Customer> customers = new ArrayList<>(customerRepository.findAll());
        int i = 0;
        for (String n : names) {
            i++;
            String username = String.format("cust%02d", i);
            if (customerRepository.findByPortalUsername(username).isPresent()) continue;
            CustomerTier tier = i % 5 == 0 ? CustomerTier.GOLD : i % 2 == 0 ? CustomerTier.SILVER : CustomerTier.BRONZE;
            String email = "buyer@" + n.toLowerCase().replace(" ", "") + ".com";
            customers.add(customerRepository.save(new Customer(n, email, tier, username, passwordEncoder.encode("cust123"))));
        }
        return customers;
    }

    /**
     * Manually adds {@code count} more customers with generated, unique company names (a
     * combinatorial word + industry mix, e.g. "Cascade Software") - never touches the base 60.
     * Repeated calls keep adding fresh names; nothing is ever generated unless asked for.
     */
    private int generateExtraCustomers(int count) {
        Set<String> existingNames = customerRepository.findAll().stream().map(Customer::getName).collect(Collectors.toSet());
        List<String> pool = combinatorialNames(COMPANY_FIRST, COMPANY_TYPE, 20240601L, COMPANY_FIRST.length * COMPANY_TYPE.length);
        int created = 0, i = (int) customerRepository.count();
        for (String n : pool) {
            if (created >= count) break;
            if (existingNames.contains(n)) continue;
            i++;
            String username = String.format("cust%04d", i);
            if (customerRepository.findByPortalUsername(username).isPresent()) continue;
            CustomerTier tier = i % 10 == 0 ? CustomerTier.GOLD : i % 3 == 0 ? CustomerTier.SILVER : CustomerTier.BRONZE;
            String email = "buyer@" + n.toLowerCase().replace(" ", "") + i + ".com";
            customerRepository.save(new Customer(n, email, tier, username, passwordEncoder.encode("cust123")));
            existingNames.add(n);
            created++;
        }
        return created;
    }

    /**
     * Manually adds {@code count} more sales reps with generated, unique names - never touches
     * rep1..rep8. Repeated calls keep adding fresh names; nothing is ever generated unless asked for.
     */
    private int generateExtraReps(int count) {
        List<AppUser> existing = appUserRepository.findByRole(Role.SALES_REP);
        Set<String> existingNames = existing.stream().map(AppUser::getFullName).collect(Collectors.toSet());
        List<String> pool = combinatorialNames(REP_FIRST, REP_LAST, 90210L, REP_FIRST.length * REP_LAST.length);
        int created = 0, i = existing.size();
        for (String name : pool) {
            if (created >= count) break;
            if (existingNames.contains(name)) continue;
            i++;
            String username = "rep" + i;
            if (appUserRepository.findByUsername(username).isPresent()) continue;
            appUserRepository.save(new AppUser(username, passwordEncoder.encode("rep123"), name, username + "@dealflow360.com", Role.SALES_REP));
            existingNames.add(name);
            created++;
        }
        return created;
    }

    private Product product(Map<String, Product> byName, String name, String category, BigDecimal price, double costRatio, String unit, String description) {
        Product existing = byName.get(name);
        if (existing != null) return existing;
        BigDecimal cost = price.multiply(BigDecimal.valueOf(costRatio)).setScale(2, RoundingMode.HALF_UP);
        Product p = productRepository.save(new Product(name, category, price.setScale(2, RoundingMode.HALF_UP), cost, unit, new BigDecimal("18.00"), description));
        byName.put(name, p);
        return p;
    }

    private void ensurePlan(String name, Product product, BillingCycle cycle, BigDecimal pricePerCycle) {
        boolean exists = subscriptionPlanRepository.findByProductId(product.getId()).stream().anyMatch(pl -> pl.getBillingCycle() == cycle);
        if (!exists) subscriptionPlanRepository.save(new SubscriptionPlan(name, product, cycle, pricePerCycle.setScale(2, RoundingMode.HALF_UP), true, cycle == BillingCycle.MONTHLY));
    }

    private int rule(Product base, Product suggested, RecommendationType type, int priority, String threshold, boolean promoted, String tag, String reason) {
        if (base.getId().equals(suggested.getId())) return 0;
        if (type == RecommendationType.PRODUCT_UPGRADE && suggested.getPrice().compareTo(base.getPrice()) <= 0) return 0;
        boolean exists = upsellRuleRepository.findByBaseProductId(base.getId()).stream()
                .anyMatch(r -> r.getSuggestedProduct().getId().equals(suggested.getId()) && r.getRecommendationType() == type);
        if (exists) return 0;
        upsellRuleRepository.save(new UpsellRule(base, suggested, type, Math.min(100, priority), new BigDecimal(threshold), promoted, tag, reason));
        return 1;
    }

    private static BigDecimal price(Random rnd, int min, int max) {
        return BigDecimal.valueOf(min + rnd.nextInt(Math.max(1, max - min))).setScale(2, RoundingMode.HALF_UP);
    }

    private static <T> List<T> pick(Random rnd, List<T> from, int n) {
        List<T> copy = new ArrayList<>(from);
        Collections.shuffle(copy, rnd);
        return copy.subList(0, Math.min(n, copy.size()));
    }

    private static <T> T pick(Random rnd, List<T> from) {
        return from.get(rnd.nextInt(from.size()));
    }
}
