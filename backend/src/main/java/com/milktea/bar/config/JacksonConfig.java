package com.milktea.bar.config;

import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.fasterxml.jackson.databind.SerializationFeature;

/**
 * Jackson 口径：BigDecimal 一律以普通数字（toPlainString）序列化，
 * 保证金额返回为 12.00 / 24.00，而不是 1E+1 之类的科学计数法。
 * 用编程方式设置，避免依赖不同版本里 relaxed property 的命名差异。
 */
@Configuration
public class JacksonConfig {

    @Bean
    public Jackson2ObjectMapperBuilderCustomizer bigDecimalPlainCustomizer() {
        return builder -> builder.featuresToEnable(SerializationFeature.WRITE_BIGDECIMAL_AS_PLAIN);
    }
}
