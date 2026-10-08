# Provider contracts

Originally checked on 2026-09-20; Netlify guidance checked on 2026-09-26;
Venice and AI/ML API guidance checked on 2026-09-28; additional compatible
services and Vercel public API guidance checked on 2026-10-01.
This SDK is unofficial.

## OpenAI Decisions (since 0.3.0, checked 2026-10-07)

`jev-openai` implements OpenAI's beta `POST https://api.openai.com/v1/decisions`
with Bearer `OPENAI_API_KEY` and default model `gpt-6-luna`, independent of Jev.
The native `decide` API supports string or user-message input with text/inline
images, string/boolean choices, score labels/descriptions, optional question names,
request-level safety identifiers, and ordered results with individual refusals.
Usage includes all documented token counters; unknown metadata stays in raw JSON.

The inherited Jev `evaluate` API maps basic Noul to predicate, string/enum Choice
and string-level Score to their existing Java types. It accepts text only and
fails the whole evaluation with `REFUSAL` on a declined question. Existing record
mapping remains available except structured `@JevLabels` questions.

Changing a TypeSafe/OpenRouter endpoint cannot change its wire format. See the
[OpenAI guide](openai-decisions.md) for native/compatibility API examples, Spring
setup, and official contracts; [validation](validation.md) separates offline
fixtures from actual provider calls.

## TypeSafe direct

- `POST https://api.typesafe.ai/v1/systemone`
- `Authorization: Bearer <TYPESAFE_API_KEY>`
- Body: `model`, `state`, named `questions`.
- Primitives: `choice`, `noul`, `score`.
- Responses: named `answers`, resolved `model`, snake_case token usage.

Sources: [HTTP API](https://docs.typesafe.ai/api), [models](https://docs.typesafe.ai/models), [structured criteria](https://docs.typesafe.ai/primitives/advanced).

Netlify AI Gateway documents Jev through this TypeSafe API rather than a
separate Java wire format. It supplies `TYPESAFE_API_KEY` and
`TYPESAFE_BASE_URL` in supported compute contexts; the Java client requires an
explicit `baseUrl` / `jev.base-url` to use that route. See the
[Netlify configuration guide](netlify-ai-gateway.md) for runtime limits.

Venice and AI/ML API also document TypeSafe-shaped Jev Decisions requests.
Their complete endpoints and model IDs differ from TypeSafe direct access;
use the existing TypeSafe client with an explicit key, model, and `endpoint`.
See the separate [Venice](venice.md) and [AI/ML API](aimlapi.md) guides for
configuration, provider-specific contract limits, and validation evidence.

### Additional compatible services (checked 2026-10-01)

These routes use `TypeSafeJevClient` / Spring `jev.provider=typesafe` with an
explicit provider key, model, and full `endpoint`. No new provider selector is
introduced. Documentation compatibility and offline response parsing do not
establish successful live inference; see [validation](validation.md).

| Service | Model ID used in guide | Full endpoint / contract guide |
| --- | --- | --- |
| DigitalOcean | `typesafe-jev-1.13.0` | [DigitalOcean](digitalocean.md): `https://inference.do-ai.run/v1/systemone` |
| OpenCode Zen | `jev-1.13` | [OpenCode Zen](opencode-zen.md): `https://opencode.ai/zen/v1/systemone` |
| LLM Gateway | `typesafe/jev-1.13.0` | [LLM Gateway](llm-gateway.md): `https://api.llmgateway.io/v1/systemone` |
| Eden AI | `typesafe/jev-latest` | [Eden AI](edenai.md): `https://api.edenai.run/v3/alpha/decisions` |
| Upstage | `solar-decide` | [Solar Decide](solar-decide.md): `https://api.upstage.ai/v1/systemone` |
| Liquid AI | `d1:free` | [Liquid d1](liquid-d1.md): `https://api.liquid.ai/decisions/v1/systemone` |

Solar Decide and d1 are independent decision models, not Jev hosting routes.
Shared field names do not establish equivalent calibration or quality. The
TypeSafe decoder requires complete Choice/Score distributions; it retains
native numeric values and does not synthesize missing confidence or token
counts. Provider-specific limits remain the application's responsibility.

## Vercel AI Gateway

Vercel now documents two public HTTP formats:

- TypeSafe-compatible `POST /typesafe/v1/systemone`: native `noul` and
  snake_case usage. Configure the existing TypeSafe client with a Gateway key
  and model `typesafe-ai/jev`; see the [Vercel guide](vercel-ai-gateway.md).
- Evaluation `POST /v1/evaluate`: `boolean` / `probability` and the model in
  the request body. This SDK does not implement that HTTP route directly.

Sources: [TypeSafe-compatible API](https://vercel.com/docs/ai-gateway/sdks-and-apis/typesafe),
[public evaluation API](https://vercel.com/docs/ai-gateway/modalities/evaluation).

The existing `VercelJevClient` / Spring `jev.provider=vercel` still uses the
following AI SDK evaluation-model v4 protocol:

- `POST https://ai-gateway.vercel.sh/v4/ai/evaluation-model`
- `Authorization: Bearer <AI_GATEWAY_API_KEY>`
- `ai-gateway-protocol-version: 0.0.1`
- `ai-gateway-auth-method: api-key`
- `ai-evaluation-model-specification-version: 4`
- `ai-model-id: typesafe-ai/jev`
- Body: `state`, named `questions`; the model travels in a header.

This adapter targets the Vercel AI SDK evaluation protocol, not the public
`/v1/evaluate` route, Chat Completions, or Responses. Changing only its endpoint
does not change its headers or body format. Protocol changes can require an
adapter update. A Vercel key may be valid while the account cannot make
requests (for example, payment verification).

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

`baseUrl` is an origin with an optional path prefix. The adapter appends its full endpoint suffix (for example `v1/systemone`, `v4/ai/evaluation-model`, `api/alpha/decisions`, or OpenAI's `v1/decisions`). Do not include that suffix twice. Since 0.1.1, `endpoint(URI)` / Spring `jev.endpoint` accepts the complete URL and preserves its path/query without appending anything. The two options are mutually exclusive. The selected client still determines authentication and wire format. User-info and fragments are rejected; `baseUrl` also rejects queries. Use HTTPS for real API keys; HTTP exists for local servers/proxies.
