package com.lms.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.lms.course.dto.CourseResponse;
import com.lms.course.service.CourseCatalogReader;
import org.springframework.boot.autoconfigure.cache.RedisCacheManagerBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.serializer.Jackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext;

import java.time.Duration;

@Configuration
public class CacheConfig {

    private static final Duration TTL = Duration.ofMinutes(10);

    /**
     * JSON rather than Java serialization, so an entry written before a deployment is still
     * readable after one that changes the class.
     */
    @Bean
    RedisCacheConfiguration cacheConfiguration() {
        return base();
    }

    /**
     * The course cache is typed to what it actually holds.
     *
     * <p>A serializer bound to {@code Object} writes JSON with no type information and reads it
     * back as a {@code LinkedHashMap}, so the first call populates the cache and the second one
     * fails with a {@code ClassCastException}. Naming the type is what makes a cache hit usable.
     */
    @Bean
    RedisCacheManagerBuilderCustomizer typedCaches() {
        return builder -> builder.withCacheConfiguration(CourseCatalogReader.CACHE,
                base().serializeValuesWith(RedisSerializationContext.SerializationPair
                        .fromSerializer(new Jackson2JsonRedisSerializer<>(objectMapper(), CourseResponse.class))));
    }

    private static RedisCacheConfiguration base() {
        return RedisCacheConfiguration.defaultCacheConfig()
                .entryTtl(TTL)
                .disableCachingNullValues()
                .serializeValuesWith(RedisSerializationContext.SerializationPair
                        .fromSerializer(new Jackson2JsonRedisSerializer<>(objectMapper(), Object.class)));
    }

    private static ObjectMapper objectMapper() {
        return JsonMapper.builder().addModule(new JavaTimeModule()).build();
    }
}
