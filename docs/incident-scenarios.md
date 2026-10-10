# Traceback Incident Scenarios

## 1. Overview and Evaluation Philosophy

This document defines reproducible failure scenarios for evaluating the Traceback investigation platform. All scenarios are modeled on a realistic, hypothetical Spring Boot microservice: **`payment-service`**.

### 1.1 Target Application Profile (`payment-service`)
- **Framework**: Spring Boot 3.3.x on Java 21
- **Persistence**: Spring Data JPA / Hibernate over HikariCP connection pool to PostgreSQL 16
- **External Dependencies**: Stripe REST API for credit card processing (`https://api.stripe.com/v1/charges`)
- **Deployment Topology**: Docker container deployed in Kubernetes/Docker Compose with rolling updates

### 1.2 Ground-Truth Isolation Guarantee

> [!IMPORTANT]
> **Zero Ground-Truth Leakage Guarantee**:
> In all evaluation runs, ground-truth root causes, expected conclusions, and relationship labels are strictly maintained in test documentation and evaluation verification harnesses (`eval/fixtures/`). They are **never** injected into or exposed to the Traceback ingestion APIs, event database, or correlation algorithms. The investigation engine receives only raw, normalized events (commits, CI builds, deployments, config updates, and runtime error logs).

---

## 2. Scenario A — Code Regression

### 2.1 Incident Overview
- **Incident ID**: `INC-2026-001`
- **Title**: Payment Calculation Math Regression (Fee Discount Logic Inversion)
- **Service Affected**: `payment-service` (Environment: `production`)
- **Severity**: High (P1) — Checkout validation failures impacting revenue

### 2.2 Expected Failure Symptoms
- Checkout API endpoint (`POST /api/v1/payments/checkout`) returns HTTP `500 Internal Server Error`.
- Customer payments with promotional discount codes fail validation with negative net transaction amounts.
- Application logs report recurring stack traces:
  ```text
  com.traceback.payment.exception.PaymentCalculationException: Invalid net payment calculation: fee deduction exceeds gross total [-12.50 USD]
      at com.traceback.payment.service.PaymentFeeCalculator.calculateTransactionFee(PaymentFeeCalculator.java:58)
      at com.traceback.payment.service.PaymentProcessingService.processPayment(PaymentProcessingService.java:112)
      at com.traceback.payment.controller.PaymentController.checkout(PaymentController.java:45)
  ```
- Error burst: 120 errors recorded within a 5-minute window following deployment.

### 2.3 Ground-Truth Root Cause
Commit `c1a89b3` refactored fee deduction in `PaymentFeeCalculator.java` to support seasonal promo codes. The developer inadvertently inverted an operand on line 58 (`gross - (discountFee * -1)` instead of `gross - discountFee`), generating negative fee deductions that violate business invariants.

### 2.4 Chronological Event Timeline

| Event ID | Event Type | Timestamp (UTC) | Source | Entity ID | Summary / Details |
| :--- | :--- | :--- | :--- | :--- | :--- |
| `EVT-101` | `COMMIT` | 2026-10-10T14:10:00Z | GitHub | `c1a89b3` | Commit: "Refactor promotional discount math in fee calculator" by Alice (`PaymentFeeCalculator.java` modified, lines 50-65) |
| `EVT-102` | `CI_BUILD` | 2026-10-10T14:12:00Z | GitHub Actions | `build-4011` | Build #4011 succeeded for commit `c1a89b3`. Unit tests passed (flawed test asserting truthy fee). |
| `EVT-103` | `COMMIT` | 2026-10-10T14:13:30Z | GitHub | `e55b112` | Commit: "Update README with promo code docs" by Bob (docs only, no code files). |
| `EVT-104` | `DEPLOYMENT`| 2026-10-10T14:15:00Z | K8s Deployer | `dep-102` | Rolling deployment to production finished. Deployed commit `c1a89b3`. |
| `EVT-105` | `ERROR_BURST`| 2026-10-10T14:16:30Z | Spring Boot | `err-burst-901`| First occurrence of `PaymentCalculationException` on checkout endpoint. |
| `EVT-106` | `ERROR_BURST`| 2026-10-10T14:18:00Z | Spring Boot | `err-burst-902`| 120 exceptions/min threshold breached; incident auto-created. |

### 2.5 Expected Evidence Relationships

| Source Event | Target Event | Relationship Type | Confidence Weight | Reason / Explanation |
| :--- | :--- | :--- | :--- | :--- |
| `EVT-104` (`dep-102`) | `EVT-101` (`c1a89b3`) | `EXPLICIT_DEPLOY_COMMIT` | 1.00 | Deployment manifest explicitly references commit SHA `c1a89b3`. |
| `EVT-102` (`build-4011`) | `EVT-101` (`c1a89b3`) | `EXPLICIT_CI_COMMIT` | 1.00 | CI build ran against commit SHA `c1a89b3`. |
| `EVT-101` (`c1a89b3`) | `EVT-105` (`err-burst-901`) | `INFERRED_CODE_FRAME` | 0.95 | Stack frame top `PaymentFeeCalculator.java:58` matches modified file & line in commit diff. |
| `EVT-104` (`dep-102`) | `EVT-105` (`err-burst-901`) | `TEMPORAL_PRECEDENCE` | 0.40 | Deployment completed 90 seconds prior to first observed failure. |
| `EVT-103` (`e55b112`) | `EVT-105` (`err-burst-901`) | `TEMPORAL_PRECEDENCE` | 0.20 | Pure temporal proximity (preceded error by 3 min), but 0 matching code files. |

### 2.6 Evidence Supporting Root Cause
- **Direct Code Intersection**: Git diff of `c1a89b3` touches `PaymentFeeCalculator.java` lines 50–65. The crash stack frame explicitly faults at `PaymentFeeCalculator.java:58`.
- **Temporal Alignment**: Failure rate went from 0% to 100% on discounted carts 90 seconds after `dep-102` finished rolling out.
- **Traceable Deployment Link**: Deployment `dep-102` cryptographically links to commit `c1a89b3`.

### 2.7 Potential Misleading Evidence
- Commit `e55b112` ("Update README with promo code docs") was committed *after* `c1a89b3` and closer in time to the failure.
- A naive "most recent commit" algorithm would rank `e55b112` ahead of `c1a89b3`, presenting a false cause.

### 2.8 Conclusion Traceback Should Produce
- **Primary Hypothesis (Rank #1, Confidence: ~95%)**: Code regression in `PaymentFeeCalculator.java` introduced in commit `c1a89b3`, deployed in `dep-102`.
- **Secondary Discarded Hypothesis (Confidence: <10%)**: Commit `e55b112` demoted due to non-functional file modification (`README.md` only) and zero stack trace intersection.

### 2.9 Developer Verification Checklist
1. Review git diff for `c1a89b3` in `PaymentFeeCalculator.java`: verify arithmetic operator on line 58.
2. Run unit test with negative and positive discount fixtures: `mvn test -Dtest=PaymentFeeCalculatorTest`.
3. Roll back deployment `dep-102` or deploy hotfix reverting line 58.

---

## 3. Scenario B — Configuration Failure

### 3.1 Incident Overview
- **Incident ID**: `INC-2026-002`
- **Title**: Database Connectivity Loss via Misconfigured Port in Secret Injection
- **Service Affected**: `payment-service` (Environment: `production`)
- **Severity**: Critical (P0) — Total service outage; 100% of database operations fail

### 2.2 Expected Failure Symptoms
- All transactional APIs fail immediately with HTTP `500 Internal Server Error`.
- Health check endpoint (`/actuator/health`) transitions from `UP` to `DOWN`.
- Recurring HikariCP and Hibernate exceptions in logs:
  ```text
  org.springframework.transaction.CannotCreateTransactionException: Could not open JPA EntityManager for transaction
      at org.springframework.orm.jpa.JpaTransactionManager.doBegin(JpaTransactionManager.java:448)
  Caused by: org.hibernate.exception.JDBCConnectionException: Unable to acquire JDBC Connection
      at org.hibernate.exception.internal.SQLStateConverter.convert(SQLStateConverter.java:99)
  Caused by: java.sql.SQLTransientConnectionException: HikariPool-1 - Connection is not available, request timed out after 30000ms.
      at com.zaxxer.hikari.pool.HikariPool.createTimeoutException(HikariPool.java:696)
  Caused by: org.postgresql.util.PSQLException: Connection to db-primary.internal:5433 refused. Check that hostname and port are correct.
  ```

### 3.3 Ground-Truth Root Cause
A DevOps engineer updated the Kubernetes production ConfigMap/Secret (`cfg-deploy-204`), updating `SPRING_DATASOURCE_URL` to point to a new database read replica, but entered port `5433` instead of the primary port `5432`. A rolling pod restart triggered immediate connection failures.

### 3.4 Chronological Event Timeline

| Event ID | Event Type | Timestamp (UTC) | Source | Entity ID | Summary / Details |
| :--- | :--- | :--- | :--- | :--- | :--- |
| `EVT-201` | `COMMIT` | 2026-10-10T15:00:00Z | GitHub | `a991d4e` | Commit: "Refactor checkout button CSS & add aria labels" by Carol (Frontend/UI only). |
| `EVT-202` | `CI_BUILD` | 2026-10-10T15:02:00Z | GitHub Actions | `build-4015` | Build passed for `a991d4e`. |
| `EVT-203` | `CONFIG_CHANGE` | 2026-10-10T15:05:00Z | K8s ConfigMap | `cfg-deploy-204` | Updated environment config: `SPRING_DATASOURCE_URL` changed from port `5432` to `5433`. |
| `EVT-204` | `RESTART` | 2026-10-10T15:06:00Z | K8s Deployment | `restart-801` | Rolling restart of `payment-service` pods initiated to apply config. |
| `EVT-205` | `ERROR_BURST` | 2026-10-10T15:06:35Z | Spring Boot | `err-burst-905` | HikariPool connection timeout logged upon startup; all queries fail. |
| `EVT-206` | `HEALTH_DOWN` | 2026-10-10T15:07:05Z | Actuator / K8s | `health-fail-10` | Actuator liveness/readiness probes fail with DB DOWN. |

### 3.5 Expected Evidence Relationships

| Source Event | Target Event | Relationship Type | Confidence Weight | Reason / Explanation |
| :--- | :--- | :--- | :--- | :--- |
| `EVT-204` (`restart-801`) | `EVT-203` (`cfg-deploy-204`) | `EXPLICIT_CONFIG_APPLY` | 1.00 | Pod rollout explicitly triggered by ConfigMap revision change. |
| `EVT-203` (`cfg-deploy-204`) | `EVT-205` (`err-burst-905`) | `INFERRED_CONFIG_KEY` | 0.92 | Config key `SPRING_DATASOURCE_URL` matches JDBC connection error and port string `5433`. |
| `EVT-204` (`restart-801`) | `EVT-205` (`err-burst-905`) | `TEMPORAL_PRECEDENCE` | 0.45 | First connection failure occurred 35s after pod initialization. |
| `EVT-201` (`a991d4e`) | `EVT-205` (`err-burst-905`) | `TEMPORAL_PRECEDENCE` | 0.15 | Code commit occurred 6m prior, but has 0 stack trace frame overlap and touched only CSS. |

### 3.6 Evidence Supporting Root Cause
- **Direct Configuration Parameter Match**: Config delta modified `SPRING_DATASOURCE_URL` port to `5433`. Error specifically cites `Connection to db-primary.internal:5433 refused`.
- **Infrastructure Sequence**: Config change $\to$ Pod restart $\to$ Immediate Hikari pool timeout upon context initialization.
- **Absence of Domain Code Stack Frames**: Exception is entirely infrastructure/framework (`HikariPool`, `PSQLException`), not business logic.

### 3.7 Potential Misleading Evidence
- Commit `a991d4e` was merged 5 minutes prior to the config change. A git-only tool would blame Carol's CSS commit for the database crash.

### 3.8 Conclusion Traceback Should Produce
- **Primary Hypothesis (Rank #1, Confidence: ~94%)**: Configuration failure in `SPRING_DATASOURCE_URL` applied in `cfg-deploy-204` with pod restart `restart-801`.
- **Secondary Discarded Hypothesis (Confidence: <5%)**: Commit `a991d4e` rejected with clear notice: "Zero code frames in call stack; commit touched only CSS styling files; dismissed as temporal coincidence".

### 3.9 Developer Verification Checklist
1. Inspect active ConfigMap/Environment variables: `kubectl get configmap payment-service-config -o yaml`.
2. Verify port in `SPRING_DATASOURCE_URL` matches PostgreSQL listening port (`5432` vs `5433`).
3. Test container network reachability: execute `nc -zv db-primary.internal 5432` and `5433` from a debug pod.
4. Patch ConfigMap to port `5432` and trigger restart.

---

## 4. Scenario C — Misleading Recent Commit

### 4.1 Incident Overview
- **Incident ID**: `INC-2026-003`
- **Title**: Upstream Stripe Payment Gateway Latency Spike & Read Timeouts
- **Service Affected**: `payment-service` (Environment: `production`)
- **Severity**: High (P1) — Third-party payment capture failures

### 4.2 Expected Failure Symptoms
- Checkout calls fail after exactly 10 seconds with HTTP `504 Gateway Timeout`.
- Recurring socket read timeout exceptions in application logs:
  ```text
  com.traceback.payment.exception.PaymentGatewayTimeoutException: Read timed out connecting to api.stripe.com/v1/charges
      at com.traceback.payment.client.StripePaymentGatewayClient.charge(StripePaymentGatewayClient.java:78)
      at com.traceback.payment.service.PaymentProcessingService.executeGatewayCharge(PaymentProcessingService.java:145)
      at com.traceback.payment.controller.PaymentController.checkout(PaymentController.java:49)
  Caused by: java.net.SocketTimeoutException: Read timed out
      at java.base/sun.nio.ch.NioSocketImpl.timedRead(NioSocketImpl.java:278)
  ```

### 4.3 Ground-Truth Root Cause
External degradation of Stripe's API gateway (`api.stripe.com`) causing network read timeouts. **A recent internal commit `f881c2b` ("Refactor UserProfileAvatar layout and icons") was deployed 2 minutes before the outage, but is completely innocent.**

### 4.4 Chronological Event Timeline

| Event ID | Event Type | Timestamp (UTC) | Source | Entity ID | Summary / Details |
| :--- | :--- | :--- | :--- | :--- | :--- |
| `EVT-301` | `COMMIT` | 2026-10-10T16:00:00Z | GitHub | `f881c2b` | Commit: "Refactor UserProfileAvatar layout and icons" by Dave (`UserProfileAvatar.tsx`, `avatar.css`). |
| `EVT-302` | `CI_BUILD` | 2026-10-10T16:01:30Z | GitHub Actions | `build-4022` | CI build succeeded for `f881c2b`. |
| `EVT-303` | `DEPLOYMENT`| 2026-10-10T16:03:00Z | K8s Deployer | `dep-105` | Deployment finished rolling out commit `f881c2b` to production. |
| `EVT-304` | `EXTERNAL_ALERT`| 2026-10-10T16:04:30Z | Statuspage/Webhook | `stripe-deg-01` | Stripe status page updates: "Elevated API latency and 504 errors on /v1/charges". |
| `EVT-305` | `ERROR_BURST`| 2026-10-10T16:05:00Z | Spring Boot | `err-burst-910` | First `PaymentGatewayTimeoutException` logged (10,000ms read timeout). |
| `EVT-306` | `ERROR_BURST`| 2026-10-10T16:06:00Z | Spring Boot | `err-burst-911` | Over 80 gateway timeout errors logged across checkout nodes. |

### 4.5 Expected Evidence Relationships

| Source Event | Target Event | Relationship Type | Confidence Weight | Reason / Explanation |
| :--- | :--- | :--- | :--- | :--- |
| `EVT-303` (`dep-105`) | `EVT-301` (`f881c2b`) | `EXPLICIT_DEPLOY_COMMIT` | 1.00 | Deployment manifest explicitly deploys commit `f881c2b`. |
| `EVT-304` (`stripe-deg-01`) | `EVT-305` (`err-burst-910`) | `INFERRED_EXTERNAL_DEPENDENCY` | 0.90 | External status alert hostname `api.stripe.com` matches target URL in socket timeout exception. |
| `EVT-303` (`dep-105`) | `EVT-305` (`err-burst-910`) | `TEMPORAL_PRECEDENCE` | 0.35 | Deployment finished 2 minutes before error, but has zero code links. |
| `EVT-301` (`f881c2b`) | `EVT-305` (`err-burst-910`) | `NEGATIVE_CODE_OVERLAP` | -0.60 | Commit diff modified only frontend UI files (`UserProfileAvatar.tsx`, `avatar.css`); zero backend frames in stack. |

### 4.6 Evidence Supporting Root Cause
- **External Dependency Signature**: The stack trace originates in `SocketTimeoutException` inside `StripePaymentGatewayClient` when invoking `api.stripe.com`.
- **Negative Code Evidence**: Commit `f881c2b` touched zero Java files, zero configuration keys, and zero payment pathways.
- **Corroborating External Telemetry**: Ingestion of Stripe status alert (`stripe-deg-01`) confirms widespread external degradation.

### 4.7 Potential Misleading Evidence
- **The Classic Baseline Trap**: Commit `f881c2b` was deployed at 16:03:00Z, and errors started at 16:05:00Z. A naive "Most Recent Commit" baseline will falsely flag Dave's commit as the culprit with 100% certainty.

### 4.8 Conclusion Traceback Should Produce
- **Primary Hypothesis (Rank #1, Confidence: ~88%)**: External third-party dependency degradation (`api.stripe.com` read timeout).
- **Secondary Demoted Hypothesis (Rank #2, Confidence: ~12%)**: Deployment `dep-105` (commit `f881c2b`) flagged with explicit warning:
  > *"Temporal proximity detected (deployed 2m before failure), but zero code overlap found with stack trace. Files modified were frontend UI only. Demoted due to strong negative evidence."*

### 4.9 Developer Verification Checklist
1. Check Stripe system status dashboard: `https://status.stripe.com`.
2. Inspect application outbound egress latency metrics to `api.stripe.com`.
3. Verify that commit `f881c2b` touched only `UserProfileAvatar.tsx` and `avatar.css`.
4. Enable temporary fallback payment provider or retry circuit breaker in `payment-service`.

---

## 5. Evaluation Fixture Format (JSON Specification)

To ensure repeatable evaluation in automated tests, scenarios are serialized into JSON fixture files within `eval/fixtures/`. Below is the canonical schema:

```json
{
  "scenarioId": "SCN-A-CODE-REGRESSION",
  "incidentId": "INC-2026-001",
  "serviceName": "payment-service",
  "environment": "production",
  "windowStart": "2026-10-10T14:00:00Z",
  "windowEnd": "2026-10-10T14:30:00Z",
  "inputEvents": [
    {
      "id": "EVT-101",
      "eventType": "COMMIT",
      "timestamp": "2026-10-10T14:10:00Z",
      "source": "github",
      "entityId": "c1a89b3",
      "metadata": {
        "author": "alice@example.com",
        "message": "Refactor promotional discount math in fee calculator",
        "filesModified": ["src/main/java/com/traceback/payment/service/PaymentFeeCalculator.java"],
        "diffLines": [
          {"file": "src/main/java/com/traceback/payment/service/PaymentFeeCalculator.java", "lineStart": 50, "lineEnd": 65}
        ]
      }
    },
    {
      "id": "EVT-104",
      "eventType": "DEPLOYMENT",
      "timestamp": "2026-10-10T14:15:00Z",
      "source": "k8s",
      "entityId": "dep-102",
      "metadata": {
        "commitSha": "c1a89b3",
        "environment": "production"
      }
    },
    {
      "id": "EVT-105",
      "eventType": "ERROR_BURST",
      "timestamp": "2026-10-10T14:16:30Z",
      "source": "spring-boot",
      "entityId": "err-burst-901",
      "metadata": {
        "exceptionClass": "com.traceback.payment.exception.PaymentCalculationException",
        "message": "Invalid net payment calculation: fee deduction exceeds gross total",
        "topStackFrame": {
          "className": "com.traceback.payment.service.PaymentFeeCalculator",
          "methodName": "calculateTransactionFee",
          "fileName": "PaymentFeeCalculator.java",
          "lineNumber": 58
        }
      }
    }
  ],
  "groundTruth": {
    "rootCauseEntityId": "c1a89b3",
    "rootCauseType": "CODE_REGRESSION",
    "expectedRank": 1,
    "minimumConfidence": 0.85,
    "expectedEvidenceTypes": ["EXPLICIT_DEPLOY_COMMIT", "INFERRED_CODE_FRAME", "TEMPORAL_PRECEDENCE"],
    "misleadingEntities": ["e55b112"]
  }
}
```
