# OpenAI Decisions fixtures

Checked 2026-10-07 against the official [HTTP reference](https://developers.openai.com/api/reference/resources/decisions/methods/create)
and [Decisions guide](https://developers.openai.com/api/docs/guides/decisions).

- `predicate.json` reproduces the reference's example response, including
  its `damaged` question name, probability and zero output tokens.
- Other responses constructed in Java tests are synthetic contract fixtures,
  including refusals and deliberately malformed payloads.

These are offline examples, not recorded live inference or accuracy evidence.
