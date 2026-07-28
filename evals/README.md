# Evaluations

Evaluation contributions are first-class product work.

```text
task-packs/synthetic/   Safe, reproducible tasks with no real user data
regression/             Failures promoted into permanent checks
reports/                Versioned aggregate results and limitations
```

Every Task Pack should state:

- seed and frozen evidence;
- required constraints and forbidden actions;
- expected Artifact class;
- deterministic acceptance checks;
- human review questions;
- risk and fault plan;
- model, Harness, state and tool versions required for replay.

Do not commit real conversations, voice, Self Model records, complete production traces or platform receipts. A realistic synthetic persona is preferable to weak anonymization.

## Gate types

- **Deterministic safety/correctness** checks—Schema, authority, tool allowlist, evidence linkage,
  duplicated side effect, invalid receipt—may block every PR.
- **Stochastic quality** checks—usefulness, style, planning quality—run with fixed versions and
  repeated trials. Judge distributions and failure categories, not one answer.
- **Live-provider smoke** may be scheduled or pre-release because of cost and availability. It must
  save a HarnessRunBundle and never replace offline deterministic coverage.

Before changing a model, Prompt, Skill or Harness, record the baseline task set, repetitions, grader,
budget and decision threshold. A new version is not better because it is newer, and a Critic model is
not ground truth.
