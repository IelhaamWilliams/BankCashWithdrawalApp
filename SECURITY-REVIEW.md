# Security Review

**Review date:** 2026-11-05

## Finding

| # | Severity | File | Lines | Finding | Confidence |
|---|----------|------|-------|---------|------------|
| 1 | HIGH | `src/main/java/org/example/bankcashwithdrawalapp/rest/BankAccountController.java` (22–29) | Withdrawal accepts a caller-supplied `accountId` and invokes the withdrawal service without authentication or account-ownership authorization. Anyone able to reach the endpoint can attempt withdrawals against arbitrary accounts. | 9/10 |

**Recommendation:** Require an authenticated caller and authorize each withdrawal against the account owner. Enforce the authorization at the API/service boundary and add tests proving callers cannot withdraw from accounts they do not own.

## Scope and limitations

This review examined the withdrawal API and its debit flow for concrete security issues. No other substantiated security vulnerabilities were identified in the reviewed scope. This review does not replace a deployment, infrastructure, or dependency audit.
