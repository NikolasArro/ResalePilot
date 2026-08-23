# ResalePilot API Draft

This contract is provisional and will be implemented after the technical foundation is running.

## Health

```http
GET /api/health
```

## Products

```http
POST   /api/products
GET    /api/products
GET    /api/products/{id}
PUT    /api/products/{id}
DELETE /api/products/{id}
```

## Product status

```http
PATCH /api/products/{id}/status
```

## Images

```http
POST   /api/products/{id}/images
GET    /api/products/{id}/images
PUT    /api/products/{id}/images/order
DELETE /api/products/{id}/images/{imageId}
```

## Publication queue

```http
POST /api/publication-tasks
GET  /api/publication-tasks/today
GET  /api/publication-tasks/{id}
```

## Assisted Yaga actions

```http
POST /api/yaga/products/{id}/prepare-listing
POST /api/yaga/listings/{listingId}/prepare-edit
```

These operations prepare a visible browser session. They do not perform a final publish or delete action without explicit confirmation.
