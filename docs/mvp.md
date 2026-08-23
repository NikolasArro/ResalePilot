# ResalePilot MVP

## Problem

Managing many resale listings requires repeatedly entering the same product data, selecting attributes, uploading photographs, tracking publication dates, and deciding when a listing needs attention.

## MVP goal

Build a local application that maintains a structured product catalogue, creates a daily publication queue, and prepares Yaga listing forms through Playwright. The user reviews the prepared form and performs the final publication confirmation.

## Primary workflow

1. The user creates a product in ResalePilot.
2. The user adds product details and ordered photographs.
3. The product moves from `DRAFT` to `READY`.
4. A ready product can be placed in the publication queue.
5. ResalePilot opens Yaga in a visible browser and fills the listing form.
6. The user reviews and publishes the listing.
7. ResalePilot records the Yaga URL, publication date, price, and status.
8. ResalePilot later proposes actions for ageing listings.

## MVP features

- Product CRUD.
- Product status lifecycle.
- Purchase price, sale price, and minimum price.
- Ordered product photographs with one main photograph.
- Publication queue.
- Yaga URL and publication metadata.
- Assisted creation of a Yaga listing.
- Assisted editing of a listing price or description.
- Visible browser and manual final confirmation.
- Failure logging and screenshots for failed browser steps.

## Product statuses

- `DRAFT`
- `READY`
- `SCHEDULED`
- `WAITING_FOR_APPROVAL`
- `PUBLISHED`
- `PRICE_REDUCTION_NEEDED`
- `SOLD`
- `ARCHIVED`

## Explicitly outside the MVP

- Fully automatic publishing.
- Automatic deletion and republication.
- CAPTCHA or two-factor authentication bypass.
- Facebook Marketplace or Osta.ee integration.
- AI-generated descriptions or photographs.
- Redis, Kafka, Keycloak, and multi-user access.
- Cloud deployment.
- View and favourite statistics when Yaga does not expose them reliably.

## Success criteria

The MVP is successful when the user can add a real product, prepare its photographs, press one action in ResalePilot, review a correctly completed Yaga form, publish it manually, and see the resulting listing recorded in the catalogue.
