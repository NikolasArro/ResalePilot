# ResalePilot Architecture

## Architectural approach

ResalePilot starts as a modular monolith. The backend owns business rules, persistence, scheduling, photograph metadata, and browser automation. A separate Playwright worker is intentionally deferred until operational experience demonstrates a need for it.

## Initial components

```text
User
  -> REST API
      -> Product service
      -> Publication queue service
      -> Listing lifecycle service
      -> Yaga automation service
          -> Playwright
              -> Chromium / Yaga
      -> PostgreSQL
      -> Photograph storage adapter
```

## Technical foundation

- Java 21
- Spring Boot 4.1.0
- Maven
- PostgreSQL 17
- Flyway migrations
- Hibernate schema validation (`ddl-auto: validate`)
- Docker Compose for local infrastructure

Database structure is owned by Flyway. Hibernate may validate mappings but must not modify the schema automatically.

## Backend package structure

```text
ee.nikolas.resalepilot
├── automation
├── configuration
├── controller
├── dto
├── entity
├── exception
├── mapper
├── repository
├── service
└── validation
```

## Core domain entities

### Product

- id
- sku
- title
- description
- category
- brand
- size
- condition
- colour
- purchasePrice
- askingPrice
- minimumPrice
- status
- createdAt
- updatedAt

### ProductImage

- id
- productId
- storageProvider
- storageKey
- localRelativePath
- position
- mainImage

### Listing

- id
- productId
- marketplace
- externalListingId
- externalUrl
- status
- currentPrice
- publishedAt
- lastCheckedAt
- lastUpdatedAt

### PublicationTask

- id
- productId
- action
- status
- scheduledFor
- startedAt
- completedAt
- errorMessage

## Photograph storage boundary

Business logic must not depend directly on Google Drive. A `PhotoStorage` interface will hide the storage implementation.

Initial implementation:

- Google Drive for desktop synchronized directory.
- Database stores relative paths and image order.

Later implementation:

- Google Drive API.
- Database stores stable Drive file IDs.
- Files are downloaded temporarily before Playwright uploads them.

## Yaga automation boundary

All browser-specific behaviour belongs in the `automation` package. Controllers and product services must not contain Playwright selectors.

The automation service will:

1. Validate that the product is ready.
2. Resolve photographs in the correct order.
3. Open an authenticated browser context.
4. Navigate to the required Yaga form.
5. Fill supported fields.
6. Stop before a consequential final action.
7. Record success or a diagnostic failure.

## Safety rules

- No Yaga password is stored.
- Authentication state is local, secret, and excluded from Git.
- CAPTCHA and two-factor authentication require the user.
- Failed selectors stop the operation; the bot does not guess.
- Publish, delete, and republish require explicit confirmation.
- Browser automation initially runs in headed mode.

## Deferred decisions

- Angular UI structure.
- Google Drive API authentication.
- Cloud deployment.
- Separate automation worker.
- AI text generation.
