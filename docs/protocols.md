# Provider contracts

Checked on 2026-09-20. This SDK is unofficial.

## TypeSafe direct

- `POST https://api.typesafe.ai/v1/systemone`
- `Authorization: Bearer <TYPESAFE_API_KEY>`
- Body: `model`, `state`, named `questions`.
- Primitives: `choice`, `noul`, `score`.
- Responses: named `answers`, resolved `model`, snake_case token usage.

Sources: [HTTP API](https://docs.typesafe.ai/api), [models](https://docs.typesafe.ai/models), [structured criteria](https://docs.typesafe.ai/primitives/advanced).

## Vercel AI Gateway

- `POST https://ai-gateway.vercel.sh/v4/ai/evaluation-model`
- `Authorization: Bearer <AI_GATEWAY_API_KEY>`
- `ai-gateway-protocol-version: 0.0.1`
- `ai-gateway-auth-method: api-key`
- `ai-evaluation-model-specification-version: 4`
- `ai-model-id: typesafe-ai/jev`
- Body: `state`, named `questions`; the model travels in a header.

This is the Vercel AI SDK evaluation protocol. It is **not** Chat Completions, Responses, or a documented stable Java REST API. Protocol changes can require an adapter update. A Vercel key may be valid while the account cannot make requests (for example, payment verification).

Reference implementation pinned to Vercel AI commit `20dd00abba618d5a516e0fee40ccd3e18a2bd1fb`:

- [Gateway transport and schema](https://github.com/vercel/ai/blob/20dd00abba618d5a516e0fee40ccd3e18a2bd1fb/packages/gateway/src/gateway-evaluation-model.ts)
- [TypeSafe evaluation mapping](https://github.com/vercel/ai/blob/20dd00abba618d5a516e0fee40ccd3e18a2bd1fb/packages/typesafe-ai/src/typesafe-ai-evaluation-model.ts)
- [Official model listing](https://vercel.com/ai-gateway/models/jev)

| Meaning | Direct | Gateway | Java |
| --- | --- | --- | --- |
| Yes probability | `type:noul`, `noul` | `type:boolean`, `probability` | `NoulAnswer.probability()` |
| Choice | label + probabilities | label + optional probabilities | `ChoiceAnswer<T>` |
| Score | weighted level index + legend | weighted level index; legend omitted | `ScoreAnswer`, request levels supply missing legend |
| Confidence | answer `confidence` | `providerMetadata.typesafe.confidence[id]` | `OptionalDouble` |
| Usage | `input_tokens`, `output_tokens` | `inputTokens`, `outputTokens` | `Usage`, optional counts |

No score normalization, confidence inference, or probability renormalization occurs. Two-decimal display rounding can make probability sums differ slightly from one. Missing/extra answers, mismatched types, unknown labels, invalid level indices, nonnumeric or out-of-range probabilities are protocol failures. Unknown metadata is preserved in the raw response.

`baseUrl` is an origin with an optional path prefix. The adapter appends its full endpoint suffix (`v1/systemone` or `v4/ai/evaluation-model`). Do not include that suffix twice. Use HTTPS for real API keys; HTTP exists for local servers/proxies.
