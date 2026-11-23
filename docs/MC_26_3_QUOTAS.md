# W-059: withdrawal quotas discontinued

> **Decision 2026-11-23:** WeSort Manager targets **one agent on 6b6t**,
> a single shared guild inventory, and only Owner/Member accounts.
> Personal withdrawal quotas are **not** part of this product.

The previously implemented, disabled-by-default quota policy, member
usage ledger and `/api/v1/quotas*` endpoints were disconnected from the
live order placement flow. They have been removed, including their
corresponding frontend hook and tests. Issue
[#60](https://github.com/Crispy-Monke-eSports/WeSort/issues/60)
was closed as `not planned`.

Removing quotas does **not** remove stock safety or audit obligations:

- Items and shulker roots must still be reserved atomically against fresh,
  observed physical inventory. No double allocation or unverified stock.
- Every member order retains authenticated actor, intended recipient,
  item lines, quantity, purpose and actual verified receipt.
- Managers cannot infer successful delivery from an agent report alone.
  Unknown physical outcomes stay `NEEDS_RECONCILIATION`.
- These constraints are tracked in
  [#140](https://github.com/Crispy-Monke-eSports/WeSort/issues/140),
  [#27](https://github.com/Crispy-Monke-eSports/WeSort/issues/27) and
  [#47](https://github.com/Crispy-Monke-eSports/WeSort/issues/47).

The former quota implementation and its tests remain available in Git
history; they are not an active specification.
