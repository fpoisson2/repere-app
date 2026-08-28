# Design system

Repère's web design system lives in `frontend/src/design-system.css`. It preserves the product's
paper, pine, mint, and amber visual identity while keeping components consistent.

## Rules

- Use semantic variables such as `--surface`, `--text`, `--primary`, and `--border`; do not add
  isolated colour values to components.
- Reuse the shared button, card, field, badge, banner, spacing, radius, and shadow styles before
  creating a new component variant.
- Keep focus states visible and maintain sufficient contrast.
- Test narrow mobile layouts as well as desktop layouts.
- Match user-facing structure and wording in the Android client whenever the feature exists there.

Add a token at `:root` only when it describes a reusable role. A one-off visual exception should
remain scoped to its component.
