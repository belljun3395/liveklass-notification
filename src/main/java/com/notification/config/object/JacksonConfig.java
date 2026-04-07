package com.notification.config.object;

import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class JacksonConfig {

    @Bean
    public Jackson2ObjectMapperBuilderCustomizer jacksonCustomizer() {
        return builder -> {
            builder.modules(new JavaTimeModule());
            builder.featuresToDisable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
            builder.postConfigurer(
                    mapper ->
                            mapper.getFactory()
                                    .setStreamReadConstraints(
                                            StreamReadConstraints.builder()
                                                    .maxNestingDepth(100)
                                                    .maxStringLength(5_000_000)
                                                    .build()));
        };
    }
}
