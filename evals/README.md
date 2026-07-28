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

