package io.github.gudcks0305.jev.internal;

/** Provider wire contracts; separate from HTTP transport choice. */
public enum WireFormat {
    TYPESAFE, VERCEL, VERCEL_DECISION, OPENROUTER, CLOUDFLARE, OPENAI
}
