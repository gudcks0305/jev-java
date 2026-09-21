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

## OpenRouter (since 0.1.1)

- `POST https://openrouter.ai/api/alpha/decisions`
- `Authorization: Bearer <OPENROUTER_API_KEY>`
- Default model: `typesafe/jev-1.13`; body includes `model`, `state`, `questions`.
- Native `noul`, `choice`, and `score` answers; snake_case `input_tokens` / `output_tokens`.
- Choice/Score probabilities and confidence are optional. Score legend, model,
  usage, response id and provider are optional. Metadata including `usage.cost`
  is preserved in the raw response without fabricating missing values.
- Criteria descriptions are string/object/array values; choice also permits
  null. Score levels cannot be null. Noul criteria need both sides; two null
  descriptions are encoded as omitted criteria, as in the official provider.

Sources: [official Decisions API](https://openrouter.ai/docs/api/api-reference/alphadecisions/submit-a-decisions-questions-and-answers-request),
[official schema at 1b22b05](https://github.com/OpenRouterTeam/ai-sdk-provider/blob/1b22b05352cb0f9243a6c3fdd326038dd3705544/src/evaluation/schemas.ts),
[official mapping](https://github.com/OpenRouterTeam/ai-sdk-provider/blob/1b22b05352cb0f9243a6c3fdd326038dd3705544/src/evaluation/index.ts).
This is an alpha API. OpenRouter live inference has not been tested in this
development account; protocol tests use a local HTTP server and fixtures.

## Cloudflare (since 0.2.0)

- `POST https://api.cloudflare.com/client/v4/accounts/{account_id}/ai/run`
- Bearer `CLOUDFLARE_API_TOKEN`, account from `CLOUDFLARE_ACCOUNT_ID` or builder/Spring configuration.
- Default model `typesafe/jev`; request `{model, input: {state, questions}}`.
- Direct TypeSafe-shaped answers and Cloudflare `{success: true, result: ...}` envelopes are decoded; the original body remains in `rawResponse()`.
- A declared `success: false` is an error, never a successful evaluation.
- This is third-party model access through Cloudflare, not a claim of native Jev weight hosting.

Sources: [Cloudflare Jev catalog](https://developers.cloudflare.com/ai/models/typesafe/jev/),
[REST API and token permissions](https://developers.cloudflare.com/ai-gateway/usage/rest-api/).
Account > Workers AI > Read permission is required even for the third-party Jev model.
Only an AI Gateway management permission is insufficient. Live Cloudflare inference has not been verified.

## URL configuration

`baseUrl` is an origin with an optional path prefix. The adapter appends its full endpoint suffix (`v1/systemone`, `v4/ai/evaluation-model`, or `api/alpha/decisions`). Do not include that suffix twice. Since 0.1.1, `endpoint(URI)` / Spring `jev.endpoint` accepts the complete URL and preserves its path/query without appending anything. The two options are mutually exclusive. The selected client still determines authentication and wire format. User-info and fragments are rejected; `baseUrl` also rejects queries. Use HTTPS for real API keys; HTTP exists for local servers/proxies.
