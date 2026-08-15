// Reuse the hardened same-origin DOM/fetch harness so the new Acceptance exercises the
// exact served page and scripts without maintaining a second security-sensitive browser shim.
await import("./exact-local-approval-ui-harness.mjs");
