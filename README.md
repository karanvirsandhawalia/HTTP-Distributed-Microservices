# HTTP-Distributed-Microservices with Load Balancing and Inter-Service Communication

A multi-service distributed system built in Java and Python featuring order, user, and product microservices with inter-service communication, load balancing, and throughput optimization.

## Architecture

### Services

| Service | Port | Description |
|---------|------|-------------|
| User Service | 8066 | Manages user accounts and profiles |
| Product Service | 8069 | Manages product catalog and inventory |
| Order Service | 8068 | Manages orders, proxies to User/Product services |
| ISCS | 8070 | Inter-Service Communication Server (sync coordination) |
| Load Balancer | 8080 | Python-based round-robin load balancer |

### Deployment Configuration

Services are distributed across multiple machines (lab environment):
- User Service: `142.1.46.21:8066`
- Product Service: `142.1.46.22:8069`
- Order Service 1: `142.1.46.26:8068`
- Order Service 2: `142.1.46.28:8068` (replica)
- ISCS: `142.1.46.27:8070`
- Load Balancer: `142.1.46.25:8080`
- Database: `142.1.114.76:5433` (PostgreSQL)

Configuration files:
- `config.json` — Lab machines (default)
- `config_iscs2.json` — Alternative ISCS configuration
- `config_pc25.json` — PC25 test environment

## Features

### Microservices

**Order Service**
- POST `/order` — Create new order (validates user and product)
- GET `/user/{id}` — Proxy to User Service
- GET `/product/{id}` — Proxy to Product Service
- Shared `HttpClient` with 200-thread pool for async requests
- Handles inter-service communication via ISCS

**User Service**
- User account management and lookup
- HTTP-based REST API
- Responds to Order Service queries

**Product Service**
- Product catalog management and lookup
- Inventory tracking
- Responds to Order Service queries

**ISCS (Inter-Service Communication Server)**
- Coordinates communication between services
- Maintains service discovery
- Message relay and request routing
- Dual instance support (ISCS 1 & 2)

### Load Balancing

**Python Load Balancer**
- Round-robin distribution across Order Service instances
- Transparent request forwarding (preserves method, headers, body)
- Silent logging for performance
- Thread pool for concurrent connections

### Performance Optimizations

- Shared `HttpClient` across requests (connection pooling)
- 200-thread executor pool for async handling
- Request/response body buffering
- Horizontal scaling via multiple service instances
- Configurable backend targets

## Project Structure

```
├── src/
│   ├── ISCS/
│   │   └── ISCS.java                    # Inter-service communication server
│   ├── LoadBalancer/
│   │   └── LoadBalancer.java            # Java load balancer wrapper
│   ├── OrderService/
│   │   └── OrderServer.java             # Order management & proxying
│   ├── ProductService/
│   │   └── ProductServer.java           # Product catalog service
│   ├── UserService/
│   │   └── UserServer.java              # User management service
│   ├── LoadBalancer.py                  # Python round-robin balancer
│   └── WorkloadParser.py                # Workload file parser for testing
│
├── compiled/                            # Compiled .class files (post-build)
│   ├── ISCS/
│   ├── OrderService/
│   ├── ProductService/
│   ├── UserService/
│   └── LoadBalancer/
│
├── tests/
│   ├── payloads/
│   │   ├── order_testcases.json         # Order test data
│   │   ├── product_testcases.json       # Product test data
│   │   └── user_testcases.json          # User test data
│   ├── responses/
│   │   ├── order_responses.json         # Expected order responses
│   │   ├── product_responses.json       # Expected product responses
│   │   └── user_responses.json          # Expected user responses
│   ├── test_script_order.py             # Order service tests
│   ├── test_script_product.py           # Product service tests
│   ├── test_script_user.py              # User service tests
│   ├── workload3u20c.txt                # 3 users, 20 concurrent requests
│   ├── workloadOrder.txt                # Order-specific workload
│   ├── productWorkload.txt              # Product-specific workload
│   └── WorkLoadTemplate.txt             # Workload format guide
│
├── config.json                          # Lab machine configuration (default)
├── config_iscs2.json                    # Alternative ISCS config
├── config_pc25.json                     # PC25 environment config
├── runme.sh                             # Build & run script
├── README.txt                           # Original instructions
├── HikariCP-5.1.0.jar                   # Connection pooling library
├── postgresql-42.7.3.jar                # PostgreSQL JDBC driver
└── Profiling and Architecture.pdf       # Performance analysis document
```

## Build & Run

### Compile All Services

```bash
chmod +x ./runme.sh
./runme.sh -c
```

Outputs compiled `.class` files to `compiled/<ServiceName>/`

### Start Services (each in separate terminal)

**Order Service 1:**
```bash
./runme.sh -o
```

**Order Service 2 (replica):**
```bash
./runme.sh -o2
```

**ISCS Instance 1:**
```bash
./runme.sh -i
```

**ISCS Instance 2:**
```bash
./runme.sh -i2
```

**Product Service:**
```bash
./runme.sh -p
```

**User Service:**
```bash
./runme.sh -u
```

**Load Balancer:**
```bash
./runme.sh -l
```

### Run Workload Tests

```bash
./runme.sh -w <workload_file>
```

Examples:
```bash
./runme.sh -w tests/workload3u20c.txt
./runme.sh -w tests/workloadOrder.txt
./runme.sh -w tests/productWorkload.txt
```

## Workload Format

See `tests/WorkLoadTemplate.txt` for syntax. Example:

```
GET /user/1
POST /order {"user_id": "1", "product_id": "2", "quantity": "5"}
GET /product/2
```

## API Endpoints

### Order Service (8068)

```
POST /order
  Body: {"user_id": "...", "product_id": "...", "quantity": "..."}
  Returns: Order confirmation or error

GET /user/{id}
  Proxied to User Service
  Returns: User details

GET /product/{id}
  Proxied to Product Service
  Returns: Product details
```

### User Service (8066)

```
GET /user/{id}
  Returns: User profile

POST /user
  Body: {"name": "...", "email": "..."}
  Returns: Created user
```

### Product Service (8069)

```
GET /product/{id}
  Returns: Product details

POST /product
  Body: {"name": "...", "price": "..."}
  Returns: Created product
```

## Key Implementation Details

### OrderServer Design

**Request Handling:**
- HttpServer listens for incoming HTTP requests
- Separate handlers for `/order`, `/user/{id}`, `/product/{id}`
- OrderHandler validates order, proxies to User/Product services

**Async Communication:**
- `CompletableFuture` for non-blocking service calls
- Shared `HttpClient` with connection pooling
- 200-thread executor pool for concurrency

**Routing:**
```
Incoming Request
    ↓
Load Balancer (round-robin to Order Service 1 or 2)
    ↓
OrderServer
    ├─ POST /order → Proxy User & Product validation → DB insert
    ├─ GET /user/{id} → Proxy to User Service
    └─ GET /product/{id} → Proxy to Product Service
```

### Load Balancer (Python)

**Round-robin Distribution:**
```python
BACKENDS = [
  {"ip": "142.1.46.26", "port": 8068},  # Order Service 1
  {"ip": "142.1.46.28", "port": 8068}   # Order Service 2
]

# Forward to BACKENDS[counter % len(BACKENDS)]
# Increment counter each request
```

## Testing

### Unit Tests

```bash
python tests/test_script_order.py
python tests/test_script_product.py
python tests/test_script_user.py
```

### Load Testing

```bash
# 3 users, 20 concurrent requests
./runme.sh -w tests/workload3u20c.txt

# Order-specific workload
./runme.sh -w tests/workloadOrder.txt
```

### Test Data

- `payloads/*.json` — Input test cases
- `responses/*.json` — Expected output responses
- Automated comparison for pass/fail validation

## Performance Optimization

### Throughput Improvements

1. **Connection Pooling** — Shared `HttpClient` reuses TCP connections
2. **Thread Pools** — 200-thread executor pool handles concurrent requests
3. **Async I/O** — `CompletableFuture` prevents blocking on network calls
4. **Load Balancing** — Distributes request load across multiple Order Service instances
5. **Request Caching** — Inter-service responses can be cached (optional)

### Profiling

See `Profiling and Architecture.pdf` for detailed throughput and architecture analysis.

## Dependencies

- **Java 11+** (HttpServer, HttpClient, CompletableFuture)
- **Python 3.6+** (http.server, urllib, json)
- **PostgreSQL 12+** — Database backend
- **HikariCP 5.1.0** — Connection pooling
- **PostgreSQL JDBC 42.7.3** — Database driver

## Database Setup

PostgreSQL configuration:
- Host: `142.1.114.76`
- Port: `5433`
- Credentials: See config.json

Create tables for users, products, and orders as needed.

## Requirements

- Java 11+
- Python 3.6+
- PostgreSQL 12+
- Network access across lab machines
- Bash shell (runme.sh)

## License

Educational purposes.
