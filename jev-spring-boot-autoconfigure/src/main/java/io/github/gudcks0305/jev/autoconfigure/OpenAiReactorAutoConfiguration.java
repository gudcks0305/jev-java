package io.github.gudcks0305.jev.autoconfigure;

import io.github.gudcks0305.jev.openai.OpenAiJevClient;
import io.github.gudcks0305.jev.webflux.ReactorOpenAiClient;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import reactor.core.publisher.Mono;

/** Native Reactor facade is available only for an actual OpenAI client bean. */
@AutoConfiguration(after = JevAutoConfiguration.class)
@ConditionalOnClass({OpenAiJevClient.class, ReactorOpenAiClient.class, Mono.class})
@ConditionalOnBean(OpenAiJevClient.class)
@ConditionalOnProperty(prefix = "jev", name = "enabled", havingValue = "true", matchIfMissing = true)
public class OpenAiReactorAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean(ReactorOpenAiClient.class)
    ReactorOpenAiClient reactorOpenAiClient(OpenAiJevClient client) {
        return new ReactorOpenAiClient(client);
    }
}
