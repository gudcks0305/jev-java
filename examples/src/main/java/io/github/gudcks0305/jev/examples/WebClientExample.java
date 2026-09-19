package io.github.gudcks0305.jev.examples;

import io.github.gudcks0305.jev.*;
import io.github.gudcks0305.jev.typesafe.TypeSafeJevClient;
import io.github.gudcks0305.jev.vercel.VercelJevClient;
import io.github.gudcks0305.jev.webflux.ReactorJevClient;
import io.github.gudcks0305.jev.webflux.WebClientJevTransport;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.http.client.reactive.JdkClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClient;

/** Real WebClient transport; the CLI waits only at its terminal boundary, never inside the SDK. */
public final class WebClientExample {
    public static void main(String[] args) throws Exception {
        String provider = args.length == 0 ? "typesafe" : args[0];
        AtomicInteger exchanges = new AtomicInteger();
        WebClient webClient = WebClient.builder()
                .clientConnector(new JdkClientHttpConnector())
                .filter((request, next) -> { exchanges.incrementAndGet(); return next.exchange(request); })
                .build();
        try (var transport = new WebClientJevTransport(webClient, Duration.ofSeconds(30), 2);
             JevClient client = switch (provider) {
                 case "typesafe" -> TypeSafeJevClient.builder().transport(transport).build();
                 case "vercel" -> VercelJevClient.builder().transport(transport).build();
                 default -> throw new IllegalArgumentException("Provider must be typesafe or vercel");
             }) {
            var question = NoulQuestion.of("refund", "Does the customer request a refund?");
            var reactor = new ReactorJevClient(client);
            var result = reactor.evaluate("Please refund my duplicate payment.", question).toFuture().get();
            System.out.printf("provider=%s transport=webclient exchanges=%d refundProbability=%.2f%n",
                    provider, exchanges.get(), result.answer(question).probability());
        }
    }
}
