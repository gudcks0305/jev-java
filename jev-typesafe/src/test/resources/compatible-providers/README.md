# Compatibility fixtures

Sources checked 2026-10-01. All files are documentation examples, not live
captures. Tests serve them from an ephemeral loopback HTTP server; credentials
are synthetic and no external service is contacted.

| File | Source and adaptation |
| --- | --- |
| `solar-decide-response.json` | [Upstage System One API](https://console.upstage.ai/api/systemone), published complete response; whitespace changed only |
| `solar-decide-overflow.json` | Same page, published HTTP 422 error for 27 Choice candidates |
| `liquid-d1-answers.json` | [Liquid Decision Models](https://docs.liquid.ai/lfm/models/decision-models), “Combining All Three Primitives” answer fields placed inside a synthetic `answers` envelope; model and usage deliberately absent |
| `liquid-d1-noul-response.json` | Same page, complete quickstart Noul response; whitespace changed only |

Requests in tests use matching labels and short synthetic instructions/state.
They are not copies of provider prompts. Test-only mutations remove optional
metadata or required distributions to establish codec boundaries; they do not
claim providers emit those variants. Vercel route tests use a wholly synthetic
complete TypeSafe response. Published probability/score/confidence values are
asserted unchanged, including rounded distributions whose sum differs from one.
