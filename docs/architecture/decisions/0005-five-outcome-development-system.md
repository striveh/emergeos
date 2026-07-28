# ADR-0005: Develop through five evidence-backed outcomes

- Status: Accepted
- Date: 2026-07-28

## Context

EmergeOS is intended to become a real product while also developing AI Coding and Agent Engineering
capability, creating truthful career evidence and testing a scalable business. A technology-only roadmap can
produce a sophisticated prototype that nobody repeatedly uses, while unrestricted Codex generation can
produce a repository the owner cannot explain.

## Decision

- Every `V/R` task and meaningful `X` spike declares a user/system outcome, one main risk, one learning
  objective and observable completion. `S` work remains lightweight.
- Every Roadmap Stage/Release Gate must produce AI Coding, Agent Engineering, Product/Production, Career
  and Business receipts. Ordinary slices record only relevant evidence and may use `N/A`.
- Use risk-proportional TDD: deterministic Red/Green/Refactor for domain behavior, contract/integration/fault
  tests for infrastructure, repeated evals for model behavior and real experiments for user/business value.
- The main Agent owns decision synthesis, integration, verification execution and evidence summaries.
  The human owner accepts risk and makes milestone go/no-go decisions. Subagents default to bounded
  read-heavy exploration, test design and independent review; parallel writers require separate worktrees
  and non-overlapping ownership.
- Human mastery requires explain, transfer and debug evidence. Generated code or Codex self-assessment does
  not prove learning.
- Market discovery and Build-in-Public run beside engineering, not after the product is “finished”.
- Codex project guidance, custom agents, Skills, MCP and Automation remain separate surfaces; a workflow
  becomes a Skill or scheduled task only after repeated manual success.

## Consequences

- Small changes remain lightweight through `S/V/R/X` classification; not every commit must create all five
  receipts.
- Large work uses a living ExecPlan so progress and decisions survive chat boundaries.
- Roadmap stages include AI Coding, Agent Engineering, Product/Production, Career and Business gates.
- Some development time is explicitly allocated to teach-back, user interviews, demos and failure exercises.
- Framework novelty, code volume, Star count and model self-evaluation cannot substitute for outcome evidence.

## Rollback

Individual templates and metrics can be simplified when they add coordination cost without changing
decisions. The five outcomes and evidence boundary remain unless a later ADR replaces them.
