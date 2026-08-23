# ResalePilot

ResalePilot is a product catalogue and listing-assistance application for small-scale resale. It stores product data and photographs, prepares a daily publishing queue, and uses Playwright to fill Yaga listing forms while keeping the final publish action under user control.

## Project status

Stage 2: Spring Boot and PostgreSQL technical foundation created.

## Planned technology stack

- Java 21
- Spring Boot
- Maven
- Spring Web
- Spring Data JPA / Hibernate
- Bean Validation
- PostgreSQL
- Flyway
- Lombok
- Docker Compose
- Playwright for Java
- Angular in a later stage

## Planned repository structure

```text
ResalePilot/
├── backend/
├── docs/
│   ├── api.md
│   ├── architecture.md
│   └── mvp.md
├── compose.yaml
├── .env.example
├── .gitignore
└── README.md
```

## Development stages

1. Define MVP and architecture.
2. Create the Spring Boot foundation and PostgreSQL environment.
3. Implement the product catalogue and local CRUD API.
4. Add product photographs and Google Drive support.
5. Add the publication queue and listing lifecycle.
6. Integrate Playwright in visible-browser mode.
7. Implement assisted Yaga listing creation and editing.
8. Add tests, error recovery, statistics, and documentation.

## MVP rule

ResalePilot assists the user but does not publish, delete, or republish Yaga listings without explicit user confirmation.

## Local development

### Requirements

- JDK 21
- Maven 3.6.3 or newer
- Docker with Docker Compose

### Start PostgreSQL

Copy `.env.example` to `.env`, then run from the project root:

```bash
docker compose up -d
```

### Start the backend

```bash
cd backend
mvn spring-boot:run
```

### Verify the application

```http
GET http://localhost:8080/api/health
```

Expected response:

```json
{
  "application": "ResalePilot",
  "status": "UP",
  "timestamp": "2026-08-23T12:00:00Z"
}
```

Flyway creates the initial `app_metadata` table during startup. Hibernate is configured with `ddl-auto: validate`; database changes must be implemented through versioned Flyway migrations.

The local PostgreSQL container is exposed on host port `5433` to avoid conflicts with a PostgreSQL server installed directly on the host machine.
