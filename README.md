# Microservices-Based Order Processing Platform

A microservices-based order processing platform capable of handling 5,000+ orders per minute with real-time dashboard updates. Built with Java 17, Spring Boot 3.2, Apache Kafka, PostgreSQL, and WebSocket (STOMP).
 
---

## 1. Architecture Diagram

```
                          ⑤ WebSocket (STOMP) — Real-time notifications
                 ┌──────────────────────────────────────────────────────┐
                 ▼                                                      │
┌──────────────────┐   ① REST    ┌──────────────────┐   ③ Publish   ┌──────┐   ④ Consume   ┌──────────────────────┐
│   Frontend UI    │──(JWT)────▶│  Order Service    │──(Outbox)───▶│ Kafka │─────────────▶│ Notification Service │
│   :3000          │            │  :8081            │              │ :9092 │              │ :8082                │
│                  │            │                   │              │       │              │                      │
│ HTML + JS        │            │ Spring Boot 3.2   │              │ 3 pt  │              │ Spring Boot 3.2      │
│ SockJS + STOMP   │            │ Resilience4j      │              └───────┘              │ @KafkaListener       │
└──────────────────┘            │ JWT + Rate Limit  │                                     │ SimpMessagingTemplate│
                                └────────┬──────────┘                                     └──────────────────────┘
                                         │
                                    ② Persist
                                         │
                                ┌────────▼──────────┐
                                │    PostgreSQL      │
                                │    :5432           │
                                │                    │
                                │ orders             │
                                │ outbox_events      │
                                └────────────────────┘

                        ┌──────────────────────────────────┐
                        │  📊 Prometheus + Micrometer       │
                        │  Structured JSON Logs + MDC       │
                        └──────────────────────────────────┘
```

### Services

| Service | Port | Technology | Responsibility |
|---------|------|-----------|----------------|
| **Order Service** | 8081 | Spring Boot 3.2, Java 17, Spring Data JPA, Spring Security | REST API (`POST /orders`, `GET /orders/{id}`), JWT authentication, order persistence, outbox event creation |
| **Notification Service** | 8082 | Spring Boot 3.2, Java 17, WebSocket (STOMP) | Kafka event consumption, real-time WebSocket push to connected clients |

### Messaging

| Component | Technology | Configuration |
|-----------|-----------|---------------|
| **Message Broker** | Apache Kafka 3.9 | Topic: `order-events`, 3 partitions, `orderId` as message key |
| **Producer** | Spring Kafka + Resilience4j | `acks=all`, `retries=3`, `enable.idempotence=true`, circuit breaker protected |
| **Consumer** | Spring Kafka | `group-id=notification-group`, `auto-offset-reset=earliest`, manual acknowledgment |
| **Pattern** | Outbox Pattern | Order + event in same DB transaction → background scheduler publishes to Kafka |

### Database

| Component | Technology | Tables |
|-----------|-----------|--------|
| **PostgreSQL 15+** | Spring Data JPA, HikariCP (20 max connections) | `orders` (order data + idempotency key), `outbox_events` (pending Kafka events) |

### Frontend

| Component | Technology | Features |
|-----------|-----------|----------|
| **UI** (:3000) | HTML + JavaScript, SockJS + STOMP | Login form, order creation, live notification feed, order list, service health dashboard |

### Request Flow

1. **Frontend** sends `POST /orders` with JWT Bearer token to Order Service
2. **Order Service** persists the order + outbox event in a **single database transaction**
3. **Background scheduler** polls `outbox_events` every 1 second and publishes pending events to Kafka
4. **Notification Service** consumes events from the `order-events` Kafka topic
5. **Notification Service** pushes real-time updates to all connected WebSocket clients on `/topic/orders`

---

## 2. Design Decisions

### Messaging Technology: Apache Kafka

**Why Kafka over alternatives:**

| Alternative | Why Kafka was chosen instead |
|-------------|------------------------------|
| RabbitMQ | Kafka provides higher throughput for our 5,000+ orders/min target. Kafka's partitioning model allows parallel consumption. Kafka retains messages on disk, enabling replay after consumer crashes. |
| Direct HTTP calls | Tight coupling between services. If the notification service is down, order creation would fail or need complex retry logic. |
| Redis Pub/Sub | No message persistence. If the consumer is down, messages are lost. No consumer group semantics for scaling. |

**Kafka configuration rationale:**
- **3 partitions** — allows up to 3 notification service instances consuming in parallel
- **`orderId` as message key** — guarantees all events for the same order go to the same partition, preserving ordering per order
- **`acks=all`** — durability guarantee, all replicas acknowledge before the send is considered successful
- **`enable.idempotence=true`** — prevents duplicate messages at the broker level if the producer retries

### Communication Patterns: Outbox Pattern + Event-Driven

**Why Outbox Pattern instead of direct Kafka publish:**

The core problem is the **dual-write problem** — writing to two systems (database + Kafka) where one can fail:

```
Direct publish approach (PROBLEMATIC):
  1. Save order to DB        ✓ succeeds
  2. Publish to Kafka         ✗ fails (Kafka down)
  Result: Order exists but no notification event → data inconsistency

Outbox Pattern approach (WHAT WE USE):
  1. Save order to DB         }  SAME TRANSACTION
  2. Save outbox event to DB  }  Both succeed or both rollback
  3. Background scheduler publishes outbox events to Kafka independently
  Result: Always consistent. Event delivery is eventual but guaranteed.
```

**Trade-off accepted:** Up to 1 second delay between order creation and Kafka event (the outbox polling interval). This is acceptable because the client gets an immediate `201 Created` response, and near-real-time notification is sufficient.

**Why Event-Driven over synchronous:**
- Services are **decoupled** — Order Service doesn't know about Notification Service
- Each service can **scale independently**
- If Notification Service is down, orders still succeed
- New consumers can be added without changing the Order Service

### Database Choice: PostgreSQL

**Why PostgreSQL:**

| Requirement | How PostgreSQL meets it |
|-------------|----------------------|
| ACID transactions | Outbox Pattern requires the order and event to be in the same transaction. PostgreSQL's full ACID compliance guarantees this. |
| Unique constraints | `idempotency_key UNIQUE` constraint prevents duplicate orders even under race conditions. |
| JSON support | `payload::jsonb` allows querying event payloads for debugging. |
| Performance | Handles 10,000+ TPS on modern hardware. With HikariCP (20 connections) and ~5ms per insert, theoretical capacity is ~4,000 inserts/second — well above our 83/second target. |
| Ecosystem | Mature, well-supported, excellent Spring Data JPA integration. |

**Schema design:**
- `orders` table — order data with UUID primary key and unique idempotency key
- `outbox_events` table — pending Kafka events with status tracking (PENDING → SENT / FAILED)

### Scaling Strategy

**Target: 5,000+ orders per minute (~83 orders/second)**

| Strategy | Implementation | Impact |
|----------|---------------|--------|
| **Stateless services** | No server-side sessions (JWT auth). No local state. | Horizontally scale by running more instances behind a load balancer. |
| **Async event publishing** | Order API writes to DB only (fast). Kafka publishing is async via outbox scheduler. | API latency is only DB write time (~5-10ms). Kafka throughput doesn't bottleneck the API. |
| **Connection pooling** | HikariCP with `maximum-pool-size=20`, `minimum-idle=5` | 20 concurrent DB connections × ~5ms per insert = ~4,000 inserts/second capacity. |
| **Kafka partitioning** | 3 partitions on `order-events` topic | Up to 3 notification service instances consuming in parallel. Increase partitions to scale further. |
| **Kafka producer idempotence** | `enable.idempotence=true` | Exactly-once semantics at broker level, no duplicate messages even on retries. |

### Security: JWT Authentication

**Why JWT over session cookies:**
- **Stateless** — no session store needed, fits horizontal scaling
- **Cross-origin** — works across ports 3000 (UI), 8081 (API), 8082 (WebSocket) without cookie domain issues
- **Both REST and WebSocket** — same token authenticates both channels

**Security layers implemented:**
- JWT authentication on all `/orders/**` endpoints
- Rate limiting on `/auth/login` (5 attempts/minute per IP)
- CORS restricted to `http://localhost:3000`
- Actuator metrics (`/actuator/prometheus`) require JWT; health endpoints remain public
- Input validation (`@NotBlank`, `@Min`) with structured error responses
- Custom `401 Unauthorized` responses (not Spring's default `403`)

---

## 3. Failure Handling

### 3.1 Messaging System (Kafka) Unavailable

**What happens if Kafka is down when an order is created?**

The order is **still created successfully**. The Outbox Pattern decouples order creation from Kafka availability:

```
1. Client sends POST /orders
2. Order + outbox event saved in SAME DB transaction
   → Client receives 201 Created immediately
3. Background scheduler (every 1s) tries to publish outbox event to Kafka
4. Kafka is down → KafkaProducerService.send() fails
5. Circuit breaker tracks the failure:
   - Failure rate < 50%  → event stays PENDING, retry on next poll (1s later)
   - Failure rate ≥ 50%  → circuit OPENS for 10 seconds (fast-fail, no Kafka calls)
   - After 10s           → HALF_OPEN, allow 3 test calls
   - Tests succeed       → circuit CLOSES, normal operation resumes
6. Event stays in outbox_events as PENDING
7. When Kafka recovers → scheduler picks up all pending events and publishes them
```

**No data is lost.** Orders are persisted. Events are eventually delivered.

**Implementation details:**
- `OrderService.createOrder()` — `@Transactional` ensures order + outbox event atomicity
- `OutboxPublisher` — `@Scheduled(fixedDelay=1000)` polls every second
- `KafkaProducerService` — `@CircuitBreaker(name="kafkaProducer", fallbackMethod="sendFallback")`
- Circuit breaker config: sliding window of 10 calls, 50% failure threshold, 10s open state, 3 calls in half-open

**Monitoring:** Query `outbox_events WHERE status = 'PENDING' AND created_at < NOW() - INTERVAL '5 seconds'` to detect Kafka delivery lag.

### 3.2 Service Crashes

**What happens if the Order Service crashes?**

| Crash timing | What happens | Recovery |
|-------------|-------------|----------|
| Before DB commit | Transaction rolls back. No partial state. Client gets an error and can retry. | Retry with same `idempotencyKey` — safe. |
| After DB commit, before Kafka publish | Order is saved. Outbox event is PENDING in the database. | On restart, `@Scheduled` publisher resumes polling and publishes all pending events. No data loss. |

**What happens if the Notification Service crashes?**

```
1. Notification Service goes down
2. Kafka retains all messages (default retention: 7 days)
3. No offsets are committed (enable.auto.commit=false, ack-mode=manual)
4. Notification Service restarts
5. Consumer rejoins the group
6. Resumes from last committed offset (auto-offset-reset=earliest)
7. All missed messages are processed and pushed to WebSocket
```

**Key configuration that enables crash recovery:**
- `enable.auto.commit: false` — offsets only committed after successful processing
- `ack-mode: manual` — explicit `acknowledgment.acknowledge()` call after WebSocket push
- `auto-offset-reset: earliest` — start from beginning if no committed offset exists

**Both services are stateless.** Restart the JAR and processing resumes from where it left off.

### 3.3 Duplicate Orders Submitted

**What happens if the same order is submitted twice?**

Three layers of duplicate protection:

```
Layer 1 — Application Check (fast path):
  OrderService.createOrder() calls:
    orderRepository.findByIdempotencyKey(key)
  → If found: return existing order immediately (no DB write, no Kafka event)
  → Handles 99% of duplicate requests

Layer 2 — Database Unique Constraint (race condition safety):
  @Column(name = "idempotency_key", unique = true)
  → If two concurrent requests pass Layer 1 simultaneously,
    the second INSERT fails with ConstraintViolationException
  → Prevents duplicates even under high concurrency

Layer 3 — Kafka Producer Idempotence (broker level):
  enable.idempotence: true
  → If the producer retries a send that actually succeeded
    (e.g., acknowledgment was lost), Kafka deduplicates at the broker
  → Prevents duplicate messages in the topic
```

**Client experience:** Sending the same request with the same `idempotencyKey` always returns the same order. No duplicate created, no duplicate Kafka event.

### 3.4 Additional Failure Scenarios

| Scenario | Behavior | Recovery |
|----------|----------|----------|
| **Database unavailable** | Order creation fails with `500 Internal Server Error` | Client retries with same `idempotencyKey` — safe due to idempotency |
| **Malformed Kafka message** | Consumer logs error, acknowledges message to avoid blocking partition | Message skipped. In production, route to Dead Letter Queue (DLQ) |
| **Kafka producer repeated failures** | Circuit breaker opens (10s cooldown), all sends fast-fail | Events accumulate in outbox; published when circuit closes and Kafka recovers |
| **WebSocket client disconnects** | No impact on Kafka consumption. Messages still consumed and acknowledged. | Client reconnects via SockJS auto-reconnect (3s retry) |

---

## Technology Stack

| Component | Technology | Version |
|-----------|-----------|---------|
| Order Service | Spring Boot + Java | 3.2.5 / 17 |
| Notification Service | Spring Boot + Java | 3.2.5 / 17 |
| Message Broker | Apache Kafka | 3.9 |
| Database | PostgreSQL | 15+ |
| Resilience | Resilience4j | 2.2.0 |
| Security | Spring Security + jjwt | 6.x / 0.12.6 |
| Observability | Micrometer + Prometheus | — |
| Frontend | HTML + JavaScript (SockJS + STOMP) | — |
---

## API Reference

### Authentication

| Method | Path | Auth | Description |
|--------|------|------|-------------|
| `POST` | `/auth/login` | Public | Returns JWT token |

### Orders

| Method | Path | Auth | Description |
|--------|------|------|-------------|
| `POST` | `/orders` | JWT | Create a new order |
| `GET` | `/orders/{id}` | JWT | Get order by UUID |

### Health & Metrics

| Method | Path | Auth | Description |
|--------|------|------|-------------|
| `GET` | `/actuator/health` | Public | Service health check |
| `GET` | `/actuator/info` | Public | Service info |
| `GET` | `/actuator/prometheus` | JWT (Order) / Public (Notification) | Prometheus metrics |

### WebSocket

| Endpoint | Protocol | Description |
|----------|----------|-------------|
| `/ws/orders` | SockJS + STOMP | WebSocket connection endpoint |
| `/topic/orders` | STOMP subscription | Real-time order notifications |

---

## Observability

- **Structured JSON logging** with correlation IDs (`MDC`) in both services
- **Prometheus metrics** via Micrometer: JVM, HTTP, Kafka producer/consumer, HikariCP, Resilience4j circuit breaker
- **Health endpoints** with database, Kafka, and circuit breaker status

---

## Environment Variables

All configuration is externalized. Override via environment variables for different environments:

| Variable | Default | Description |
|----------|---------|-------------|
| `SERVER_PORT` | 8081 / 8082 | Service port |
| `DB_URL` | `jdbc:postgresql://localhost:5432/orderdb` | Database connection URL |
| `DB_USERNAME` | `orderuser` | Database username |
| `DB_PASSWORD` | `orderpass` | Database password |
| `KAFKA_BOOTSTRAP_SERVERS` | `localhost:9092` | Kafka broker address |
| `JWT_SECRET` | (base64 string) | JWT signing secret |
| `JWT_EXPIRATION_MS` | `3600000` | Token expiry (1 hour) |
| `AUTH_USERNAME` | `admin` | Demo login username |
| `AUTH_PASSWORD` | `admin123` | Demo login password |
| `CORS_ALLOWED_ORIGINS` | `http://localhost:3000` | Comma-separated allowed origins |
| `LOG_LEVEL` | `INFO` | Application log level |

---

## Project Structure

```
root/
├── order-service/
│   ├── src/main/java/com/orderplatform/order/
│   │   ├── OrderServiceApplication.java
│   │   ├── config/          CorsConfig
│   │   ├── controller/      OrderController, AuthController, GlobalExceptionHandler
│   │   │   └── dto/         CreateOrderRequest, OrderResponse
│   │   ├── entity/          Order (with indexes), OrderStatus
│   │   ├── kafka/           KafkaConfig, KafkaProducerService, OrderEventPayload
│   │   ├── outbox/          OutboxEvent (with indexes), OutboxEventStatus, OutboxEventRepository, OutboxPublisher
│   │   ├── repository/      OrderRepository
│   │   ├── security/        SecurityConfig, JwtUtil, JwtAuthenticationFilter,
│   │   │                    JwtAuthenticationEntryPoint, RateLimiter
│   │   └── service/         OrderService, OrderNotFoundException
│   └── src/main/resources/  application.yml (externalized config)
│
├── notification-service/
│   ├── src/main/java/com/orderplatform/notification/
│   │   ├── NotificationServiceApplication.java
│   │   ├── config/          WebSocketConfig, CorsConfig
│   │   ├── consumer/        OrderEventConsumer, OrderEventPayload, OrderNotification
│   │   └── websocket/       OrderWebSocketController
│   └── src/main/resources/  application.yml (externalized config)
│
├── ui/                      index.html (frontend with login + WebSocket)
├── db/                      queries.sql (schema, monitoring, analytics)
├── docs/                    Design-Document.md (full design + Q&A)
├── docker-backup/           Original Docker configs for restore
├── .gitignore
├── architecture-diagram.html
├── Order-Processing-Platform.postman_collection.json
└── README.md
```