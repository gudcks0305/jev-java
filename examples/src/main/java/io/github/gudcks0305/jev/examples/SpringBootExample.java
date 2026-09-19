package io.github.gudcks0305.jev.examples;

import io.github.gudcks0305.jev.NoulQuestion;
import io.github.gudcks0305.jev.webflux.ReactorJevClient;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;

/** Exercises starter discovery, properties, environment-key fallback, and the reactive facade. */
@SpringBootConfiguration
@EnableAutoConfiguration
public class SpringBootExample {
    public static void main(String[] args) throws Exception {
        try (var context = new SpringApplicationBuilder(SpringBootExample.class)
                .web(WebApplicationType.NONE).properties("spring.main.banner-mode=off").run(args)) {
            var question = NoulQuestion.of("billing", "Does this describe a billing issue?");
            var client = context.getBean(ReactorJevClient.class);
            var result = client.evaluate("The customer was charged twice.", question).toFuture().get();
            System.out.printf("spring provider=%s transport=%s billingProbability=%.2f%n",
                    context.getEnvironment().getProperty("jev.provider", "typesafe"),
                    context.getEnvironment().getProperty("jev.transport", "jdk"), result.answer(question).probability());
        }
    }
}
