# Request Management Application

Backend service that manages the lifecycle of requests. A request is created, then moves through a fixed set of states defined by a state diagram. The service exposes a REST API, persists data in an embedded H2 database and keeps an audit history of every state transition. There is no graphical user interface.

## Technology stack

| Component | Technology | Reason |
|---|---|---|
| Language | Java 21 | Current LTS, records, pattern matching |
| Framework | Spring Boot 3.5 | Standard production framework, minimal configuration |
| REST | Spring Web MVC | Synchronous request/response API |
| Persistence | Spring Data JPA / Hibernate | Entity mapping, optimistic locking via `@Version`, paging |
| Database | H2 (file mode) | Embedded and needs no external server |
| Migrations | Flyway | Versioned, reviewable schema; Hibernate only validates |
| Validation | Jakarta Bean Validation | Declarative validation of API input |
| Testing | JUnit 5, Mockito, Spring Boot Test, MockMvc, AssertJ | Provided by `spring-boot-starter-test` |
| Build | Maven (Maven Wrapper included) | No local Maven installation required |

No other runtime dependencies are used.

## Architecture

The application uses a lightweight hexagonal architecture with four packages under `com.example.requestmanagement`:

```
domain/          Request model, statuses, events, state machine, guards, domain exceptions
application/     RequestService (use cases, transaction boundaries) and ports (repository interfaces)
infrastructure/  JPA entities, Spring Data repositories, port adapters, publication sequence, configuration
api/             REST controller, DTOs, global exception handler
```

- **Domain** is plain Java. It has no dependency on Spring, JPA, Hibernate or HTTP. It decides whether a transition is valid, enforces the business guards and protects invariants such as a non-blank name and body.
- **Application** coordinates use cases. `RequestService` loads aggregates through ports, calls domain behaviour, persists the result and writes audit entries in one transaction.
- **Infrastructure** implements the ports: `RequestPersistenceAdapter`, `AuditPersistenceAdapter`, `SequencePublicationIdGenerator` and `SystemActorProvider`.
- **API** maps HTTP to application calls and domain objects to DTOs. It contains no business rules.

Dependencies point inward: `api → application → domain` and `infrastructure → application/domain`. This keeps the business rules easy to unit test and independent of the framework. The architecture stays deliberately small: there is one application service and only the interfaces that have a real second implementation or a test seam.

## State machine

States: `CREATED`, `VERIFIED`, `ACCEPTED`, `PUBLISHED`, `REJECTED`, `DELETED`.
Events: `VERIFY`, `ACCEPT`, `PUBLISH`, `REJECT`, `DELETE`.

| Current state | Event | New state |
|---|---|---|
| CREATED | VERIFY | VERIFIED |
| CREATED | DELETE | DELETED |
| VERIFIED | ACCEPT | ACCEPTED |
| VERIFIED | REJECT | REJECTED |
| ACCEPTED | REJECT | REJECTED |
| ACCEPTED | PUBLISH | PUBLISHED |

All other combinations are rejected with `InvalidTransitionException` (HTTP 409). `PUBLISHED`, `REJECTED` and `DELETED` are terminal states and accept no events.

`DefaultRequestStateMachine` is an immutable `Map<TransitionKey, RequestStatus>`. Business guards live in `TransitionGuards` and are kept separate from transition validation:

- `REJECT` and `DELETE` require a non-blank reason.
- `PUBLISH` requires a valid generated publication identifier.

Events are processed in this order: load the request, validate the state transition, check the guards, generate the publication id (only for `PUBLISH`), change the state, persist the request, persist the audit entry and commit.

A custom state machine was chosen over Spring StateMachine. Six states and five events fit in one table that can be read at a glance and tested exhaustively, and this avoids pulling a framework and its configuration model into the domain.

## REST API

Base path: `/api/requests`

| Method | Path | Description | Success |
|---|---|---|---|
| POST | `/api/requests` | Create a request | 201 + `Location` |
| GET | `/api/requests/{id}` | Get a request (deleted ones return 404) | 200 |
| GET | `/api/requests?page=&size=&name=&status=` | Browse requests | 200 |
| PATCH | `/api/requests/{id}` | Edit the body (`CREATED` and `VERIFIED` only) | 200 |
| POST | `/api/requests/{id}/events` | Apply a state transition event | 200 |
| GET | `/api/requests/{id}/audit` | Transition history in chronological order | 200 |

### Create

```http
POST /api/requests
{"name": "Loan application", "body": "Customer requests a personal loan"}
```

```json
{
  "id": 1,
  "name": "Loan application",
  "body": "Customer requests a personal loan",
  "status": "CREATED",
  "publicationId": null,
  "createdAt": "2026-10-19T10:00:00Z",
  "updatedAt": "2026-10-19T10:00:00Z"
}
```

### Edit body

```http
PATCH /api/requests/1
{"body": "Updated loan application content"}
```

Only `body` can be changed. The name is immutable, as the assignment only allows editing the body.

### Apply event

```http
POST /api/requests/1/events
{"event": "VERIFY"}

POST /api/requests/1/events
{"event": "REJECT", "reason": "Application does not meet requirements"}
```

### Browse

```http
GET /api/requests?page=0&size=10&name=loan&status=VERIFIED
```

```json
{
  "content": [ ... ],
  "page": 0,
  "size": 10,
  "totalElements": 1,
  "totalPages": 1
}
```

### Audit

```json
[
  {
    "id": 1,
    "requestId": 1,
    "previousStatus": "CREATED",
    "newStatus": "VERIFIED",
    "event": "VERIFY",
    "reason": null,
    "performedBy": "SYSTEM",
    "timestamp": "2026-10-19T10:01:00Z"
  }
]
```

### Errors

All errors share one format:

```json
{
  "status": 409,
  "error": "CONFLICT",
  "message": "Cannot apply PUBLISH event in CREATED state",
  "timestamp": "2026-10-19T10:00:00Z"
}
```

| Situation | Status |
|---|---|
| Validation error, malformed JSON, invalid pagination, unknown status filter | 400 |
| Missing rejection/deletion reason | 400 |
| Unsupported or missing event | 400 |
| Request not found | 404 |
| Invalid transition, edit in a locked state | 409 |
| Concurrent modification (optimistic locking) | 409 |
| Unexpected error (logged, details hidden from the client) | 500 |

## Database

- H2 runs embedded in file mode: `jdbc:h2:file:./data/requests`. Data survives restarts and lives in `./data/`, which is git-ignored. Tests use an in-memory database.
- The schema is created by Flyway (`src/main/resources/db/migration/V1__create_requests_schema.sql`). Hibernate runs with `ddl-auto=validate` and never changes the schema.
- `requests` has a primary key, NOT NULL constraints, a `version` column for optimistic locking, a unique constraint on `publication_id`, a check constraint on the status values and a check that `publication_id` is set exactly when the status is `PUBLISHED`. Indexes support the default ordering and status filtering.
- `request_audit` has a foreign key to `requests` and an index on `(request_id, occurred_at, id)`.
- `publication_id_seq` is a database sequence for publication identifiers.

### Publication identifier strategy

On `PUBLISH` the identifier is taken from the `publication_id_seq` sequence (`NEXT VALUE FOR`, starting at 1000). The database guarantees that sequence values are unique even under concurrent transactions, and the unique constraint on `publication_id` is a second safeguard. Values are not reused after a rollback, so the numbering may have gaps; the assignment does not require gapless numbering. The sequence is read only after the transition has been validated, so invalid publish attempts do not use up values.

## Business decisions

- **Soft deletion.** `DELETE` moves a request to `DELETED`; the row is never physically removed.
- **Deleted requests in reads.** Default browsing excludes `DELETED`. `GET /api/requests?status=DELETED` returns them. `GET /api/requests/{id}` returns 404 for a deleted request. The audit endpoint still works for deleted requests.
- **Rejection and deletion reasons.** A non-blank reason is mandatory. It is stored in the audit entry of the terminal transition, so the `requests` table has no separate reason column. Other events accept an optional reason, which is stored as well.
- **Validation order.** The state transition is validated before the guards. For example, `REJECT` on a `CREATED` request returns 409 even without a reason.
- **Editing.** The body can be edited only in `CREATED` and `VERIFIED`. Edits are not state transitions and are not written to the audit log. Editing a request in any other state, including `DELETED`, returns 409.
- **Name filtering.** Case-insensitive substring match. `%` and `_` are treated as literal characters.
- **Status filtering.** Exact enum value; an unknown value returns 400.
- **Pagination.** Zero-based, default size 10, maximum size 100. Out-of-range values return 400. Ordering is deterministic: `createdAt DESC, id DESC`.
- **Audit actor.** There is no authentication in the assignment, so every audit entry is recorded with `performedBy = "SYSTEM"`. The value comes from the `ActorProvider` port (`SystemActorProvider`). To record real users, replace that one bean with one that reads the authenticated principal.
- **Optimistic locking.** `requests.version` is managed by JPA `@Version`. The adapter rejects a stale domain snapshot, and the `UPDATE ... WHERE version = ?` issued by Hibernate detects concurrent commits. Conflicts return 409, and the losing transaction rolls back together with its audit entry.
- **Atomicity.** `RequestService` is `@Transactional`. The state change, publication id and audit entry are committed together or not at all.
- **Terminal states.** `PUBLISHED`, `REJECTED` and `DELETED` accept no events and no edits.
- **Timestamps.** Stored in UTC with microsecond precision and serialized as ISO-8601.

## Running the application

Requirements: JDK 21 or newer. Maven is downloaded by the wrapper.

```bash
./mvnw clean verify
./mvnw spring-boot:run
```

Windows:

```powershell
.\mvnw.cmd clean verify
.\mvnw.cmd spring-boot:run
```

The API is available at `http://localhost:8080/api/requests`.

## Example workflow

```bash
# Create
curl -i -X POST http://localhost:8080/api/requests \
  -H "Content-Type: application/json" \
  -d '{"name": "Loan application", "body": "Customer requests a personal loan"}'

# Verify
curl -X POST http://localhost:8080/api/requests/1/events \
  -H "Content-Type: application/json" -d '{"event": "VERIFY"}'

# Accept
curl -X POST http://localhost:8080/api/requests/1/events \
  -H "Content-Type: application/json" -d '{"event": "ACCEPT"}'

# Publish
curl -X POST http://localhost:8080/api/requests/1/events \
  -H "Content-Type: application/json" -d '{"event": "PUBLISH"}'

# Retrieve the published request
curl http://localhost:8080/api/requests/1

# Transition history
curl http://localhost:8080/api/requests/1/audit
```

Rejection and deletion:

```bash
curl -X POST http://localhost:8080/api/requests/2/events \
  -H "Content-Type: application/json" \
  -d '{"event": "REJECT", "reason": "Missing required documents"}'

curl -X POST http://localhost:8080/api/requests/3/events \
  -H "Content-Type: application/json" \
  -d '{"event": "DELETE", "reason": "Created by mistake"}'

curl "http://localhost:8080/api/requests?status=DELETED"
```

## Testing

```bash
./mvnw test
```

| Test class | Scope |
|---|---|
| `DefaultRequestStateMachineTest` | Full 6×5 state/event matrix: all 6 permitted and 24 forbidden transitions, terminal states |
| `RequestTest` | Domain invariants, editing rules, guards, publication id assignment, no state change on failure |
| `RequestServiceTest` | Use cases with mocked ports (Mockito): audit creation, nothing persisted on failure, not-found handling |
| `RequestSearchCriteriaTest` | Pagination bounds and name normalization |
| `PersistenceAdaptersTest` | `@DataJpaTest` against Flyway schema: mapping, version increments, stale updates, filtering, ordering, paging, sequence uniqueness, DB constraints, audit ordering |
| `RequestControllerTest` | `@WebMvcTest`: status codes, validation, JSON shape, error format, pagination parameters, no leaking of internal errors |
| `RequestWorkflowIntegrationTest` | Full Spring context with H2: publish, reject, delete and invalid workflows; editing per state; soft deletion; filtering and paging; audit history |
| `RequestConcurrencyIntegrationTest` | Two transactions on the same snapshot (only one wins, the loser gets an optimistic lock conflict); repeated races; concurrent publications with unique identifiers; rollback of the state change when the audit write fails |

## Assumptions

- `GET /api/requests/{id}` hides deleted requests (404). Deleted requests stay reachable through `?status=DELETED` and the audit endpoint.
- PATCH ignores unknown fields such as `name`; only the body is changed.
- Body edits are not audited because the audit log tracks state transitions.
- A reason sent with an event that does not require one is stored in the audit entry.
- Publication identifiers start at 1000 and may have gaps.
- `performedBy` is always `SYSTEM` until authentication is introduced.
