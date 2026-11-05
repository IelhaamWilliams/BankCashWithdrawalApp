# Bank Cash Withdrawal App

A Spring Boot REST API for bank account withdrawals. Withdrawal balances are updated transactionally, and successful withdrawals are recorded in a database outbox for asynchronous publishing to AWS SNS.

## Requirements

- Java 21
- AWS credentials configured through the AWS SDK default credentials provider chain if publishing to SNS
- An SNS topic if outbox dispatch is enabled

## Run locally

The default configuration uses an in-memory H2 database. The schema is initialized automatically, but no accounts are inserted for you.

```bash
./gradlew bootRun
```

By default, the outbox dispatcher is enabled. Configure an SNS topic and AWS credentials before running, or disable dispatch when developing without AWS:

```bash
OUTBOX_ENABLED=false ./gradlew bootRun
```

When dispatch is disabled, withdrawal events remain in the outbox and are not sent.

To use SNS, set the topic ARN and optionally the AWS region:

```bash
export AWS_SNS_TOPIC_ARN=arn:aws:sns:af-south-1:123456789012:withdrawals
export AWS_REGION=af-south-1
./gradlew bootRun
```

Configure AWS credentials using a supported AWS SDK mechanism, such as environment variables, a shared credentials profile, or an attached role.

## API

### Withdraw funds

`POST /bank/withdraw` accepts the existing query parameters `accountId` and `amount`:

```bash
curl -X POST \
  'http://localhost:8080/bank/withdraw?accountId=101&amount=25.50'
```

Successful response (`200 OK`):

```json
{
  "eventId": "a5a17fb3-23cc-4437-99d1-7db11bfd3945",
  "accountId": 101,
  "amount": 25.50,
  "status": "SUCCESSFUL",
  "occurredAt": "2026-11-03T15:00:00Z"
}
```

Amounts must be positive, have no fractional cents, and fit the account balance column (`DECIMAL(19, 2)`). The account must already exist in the configured database.

Errors use a JSON body with `code` and `message`:

| HTTP status | Error code | Meaning |
| --- | --- | --- |
| `400 Bad Request` | `INVALID_REQUEST` | A required parameter is missing or malformed |
| `400 Bad Request` | `INVALID_WITHDRAWAL` | The account ID or amount is invalid |
| `404 Not Found` | `ACCOUNT_NOT_FOUND` | No account has the supplied ID |
| `409 Conflict` | `CONCURRENT_WITHDRAWAL` | The account changed repeatedly during optimistic-lock retries; retry the request |
| `422 Unprocessable Entity` | `INSUFFICIENT_FUNDS` | The account does not have enough funds |

## Database configuration

The default datasource is an in-memory H2 database:

```text
jdbc:h2:mem:bankcashwithdrawal;DB_CLOSE_DELAY=-1
```

Override the datasource with `DATABASE_URL`, `DATABASE_USERNAME`, and `DATABASE_PASSWORD`. For example, an H2 file-backed database can use:

```bash
export DATABASE_URL='jdbc:h2:file:./data/bankcashwithdrawal'
```

`src/main/resources/schema.sql` creates the `accounts` and `withdrawal_outbox` tables. Insert accounts directly into the configured database before testing withdrawals, for example:

```sql
INSERT INTO accounts (id, balance) VALUES (101, 250.00);
```

`SQL_INIT_MODE` controls Spring SQL initialization and defaults to `always`.

## Account auditing

The `Accounts` entity is audited with Hibernate Envers. Account changes made through the JPA entity are recorded in `accounts_AUD`, with revision metadata stored in `REVINFO`. The audit tables are included in `schema.sql` because Hibernate schema generation is disabled.

`AccountsAuditService.getAuditHistory(accountId)` returns the recorded revisions for an account, newest first, including the revision number and timestamp, revision type, balance, and version. The service is available for application use; no audit-history HTTP endpoint is currently exposed.

## Outbox and SNS delivery

The account debit and outbox insert run in the same transaction. A scheduled dispatcher claims pending outbox rows and publishes them to SNS. Failed publishes are returned to the pending state with exponential retry delays, capped at five minutes. Published messages include the stable outbox event ID as an SNS message attribute.

Accounts have a `version` column. The account repository reads a balance/version snapshot and debits with a compare-and-set update that requires the version to remain unchanged, increments it on success, and checks that the balance covers the withdrawal. On a version conflict, the service reloads the account and retries; if the bounded retries are exhausted, the API returns `409 Conflict`. This prevents concurrent requests from spending the same funds. Outbox claims use conditional updates and per-claim lock tokens so concurrent dispatchers cannot own the same active claim, and an expired worker cannot update a claim after another worker has reclaimed it.

Delivery is **at least once**: a process failure after SNS accepts a message but before the outbox row is marked published can cause the message to be sent again. Consumers should deduplicate using the event ID.

Dispatcher settings can be overridden with:

| Environment variable | Default | Description |
| --- | --- | --- |
| `OUTBOX_ENABLED` | `true` | Enable scheduled SNS dispatch |
| `OUTBOX_POLL_INTERVAL_MS` | `1000` | Delay between dispatch runs |
| `OUTBOX_BATCH_SIZE` | `10` | Maximum rows claimed per run |
| `OUTBOX_LEASE_SECONDS` | `60` | Claim lease before an unfinished event can be retried |
| `AWS_REGION` | `af-south-1` | AWS region for the SNS client |
| `AWS_SNS_TOPIC_ARN` | empty | SNS topic ARN; required for publishing |

## Project structure

```text
org.example.bankcashwithdrawalapp
├── config       AWS SNS client and scheduling configuration
├── event        Withdrawal event model
├── exception    Domain exceptions
├── messaging    SNS publisher interface and implementation
├── repository   Account and outbox persistence
├── rest         HTTP controller, response DTO, and API error handling
└── service      Withdrawal, audit-history query, and outbox dispatcher
```

## Tests

Run the test suite with:

```bash
./gradlew test
```

Unit tests use Mockito to isolate the withdrawal service, outbox dispatcher, and REST controller. Integration tests use H2 and cover persistence-backed withdrawals, validation, missing accounts, insufficient funds, account audit history, concurrent withdrawals, competing outbox claims, and dispatch/retry behavior.
