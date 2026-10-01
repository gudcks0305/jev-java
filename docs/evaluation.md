# Labeled evaluation example

This example is available in source and is not part of the published 0.2.0
artifacts. It evaluates the binary question "Does the message request a refund?"
over labeled rows. It reports how changing a probability threshold affects the
fraction of automatic decisions and their accuracy.

## Offline demonstration

```sh
./mvnw install
./mvnw -q -pl examples exec:java \
  -Dexec.mainClass=io.github.gudcks0305.jev.examples.LabeledEvaluationExample
```

The default run uses a bundled synthetic dataset with hand-written probabilities.
It makes no API calls and requires no credentials. Its scores demonstrate the
metric calculations; they are not a measurement of Jev's accuracy or calibration.

## Your labeled data

Use one JSON object per line in a UTF-8 JSONL file:

```json
{"state":"Please refund the duplicate charge.","label":true,"offline_probability":0.92}
{"state":"Please update my billing address.","label":false,"offline_probability":0.08}
```

`state` is nonempty text; `label` is the gold boolean answer to the refund
question. `offline_probability` is a finite number in `[0,1]` required for
offline runs. It is optional for live runs, which obtain a fresh probability
from the provider. Invalid rows fail validation rather than disappearing from
metric denominators. To change the task, change the question and supply labels
for that question.

```sh
./mvnw -q -pl examples exec:java \
  -Dexec.mainClass=io.github.gudcks0305.jev.examples.LabeledEvaluationExample \
  -Dexec.args="--file /path/to/refunds.jsonl"
```

## Explicit live evaluation

```sh
# Configure TYPESAFE_API_KEY in your environment first.
./mvnw -q -pl examples exec:java \
  -Dexec.mainClass=io.github.gudcks0305.jev.examples.LabeledEvaluationExample \
  -Dexec.args="--file /path/to/refunds.jsonl --live typesafe"
```

`--live` makes one potentially billable request per row and sends each row's
`state` to the selected provider. Gold labels and offline probabilities remain
local. Supported provider names are `typesafe`, `openrouter`, `vercel`, and
`cloudflare`, using their existing environment keys and defaults. Cloudflare
also requires its account ID. Omitting `--file` uses the synthetic states even
in live mode.

Predictions are collected once, then reused across the threshold table; changing
the threshold does not make additional requests. The summary contains counts
and bounded error kinds, not row text, labels, API keys, or exception messages.

## Metric definitions

At each threshold `t` (`0.5`, `0.7`, and `0.9`), a positive decision is accepted
when `p >= t`; a negative decision is accepted when `1 - p >= t`. Other
successful predictions abstain. At `t = 0.5`, an exact `p = 0.5` tie is positive.
Noul is a yes probability; no separate confidence field is substituted for it.

| Metric | Denominator or meaning |
| --- | --- |
| `total` | All labeled rows |
| `successful` | Rows with a valid returned probability |
| `errors` | Rows where inference failed; still included in total |
| `abstentions` | Successful predictions that meet neither threshold |
| `accepted` | Successful predictions acted on at this threshold |
| `correct` | Accepted predictions matching the gold label |
| `coverage` | `accepted / total`, including errors in the denominator |
| `accepted_accuracy` | `correct / accepted`; `N/A` if no prediction is accepted |

For each threshold, `total = successful + errors` and
`successful = accepted + abstentions`. Accuracy on accepted rows can rise while
coverage falls. Compare both and inspect failure counts; excluding difficult
rows does not establish overall model quality. Use representative labels and
separate threshold tuning from held-out evaluation before relying on a policy.

This is an evaluation example, not automatic provider failover, an LLM fallback
policy, or an SDK batch inference API.
