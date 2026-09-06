# Verification harnesses

Plain-Java checks that drive the REAL service classes (`QuotationService`, `ApprovalService`,
`FulfillmentService`, `BillingService`, ...) against in-memory repositories - no Spring context, no
database. They are kept outside `src/` so they never affect the Maven build; they document, in runnable
form, every rule the fixes in the main README rely on.

Run after `mvn compile` (needs the compiled classes plus the Spring/Jakarta/Jackson jars from your local
Maven repository on the classpath), e.g.:

```
CP=target/classes:$(mvn -q dependency:build-classpath -Dmdep.outputFile=/dev/stdout)
javac -d /tmp/verify-out -cp "$CP" verification/LifecycleVerify.java
java -cp "/tmp/verify-out:$CP" LifecycleVerify
```

| File | What it proves | Result |
|---|---|---|
| `DemoDataVerify.java` | Runs the real `DemoDataService` on in-memory repositories: ~450 base products, 300 quotations, 60 customers, 8 reps, 240+ rules, every status represented (~half confirmed), invoices/payments/deliveries present, dates spread over six months; a second manual call with `quotations=100, extraProducts=50, extraCustomers=30, extraReps=5` adds exactly that much on top - nothing forced, nothing duplicated, base catalog exactly preserved | 23/23 |
| `RecommendationVerify.java` | Live Product Recommendation Engine end to end: rule validation (self-reference, downgrade, duplicates, priority range), filtering (inactive product, thin margin, already in cart), upgrade maths from the real line (qty, discount, tier price, same-cycle plan), explainable ranking, dismiss/restore, ADD vs UPGRADE quotation integration (line replaced in place, quantity + discount preserved), audit event, risk-score/approval recalculation, and the subscription-line fix | 40/40 |
| `LifecycleVerify.java` | Full lifecycle end to end: tier pricing, discount validation, submit / approve / confirm, warehouse split + single backorder, one-time invoice + recurring schedule, payments and invoice status, the rejection dead end and both ways back, counter/confirm guards, stock release on reopen | 44/44 |
| `ApprovalBugVerify.java` | The approval chain cannot be bypassed via negotiation status or a post-approval discount increase | 10/10 |
| `SelfServiceFlowVerify.java` | A customer's self-service request stays editable by the rep and cannot be confirmed before a rep submits it | 9/9 |
| `LegacyRowNullSafetyVerify.java` | Rows from before `approved_risk_score` existed (NULL) behave as a zero score | 4/4 |
