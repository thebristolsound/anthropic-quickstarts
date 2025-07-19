package com.example.demo.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.data.domain.AuditorAware;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.retry.annotation.EnableRetry;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;

import java.text.SimpleDateFormat;
import java.util.Optional;
import java.util.TimeZone;

@Configuration
@EnableJpaRepositories(basePackages = "com.example.demo.repository")
@EnableJpaAuditing(auditorAwareRef = "auditorProvider")
@EnableRetry
@RequiredArgsConstructor
@Slf4j
public class JpaConfig {
    
    @Bean
    public AuditorAware<String> auditorProvider() {
        return () -> {
            // In a real application, you would get this from SecurityContext
            // return Optional.ofNullable(SecurityContextHolder.getContext())
            //     .map(SecurityContext::getAuthentication)
            //     .map(Authentication::getName);
            
            // For demo purposes, return system user
            return Optional.of("system");
        };
    }
    
    @Bean
    @Primary
    public ObjectMapper objectMapper() {
        return Jackson2ObjectMapperBuilder.json()
            .dateFormat(new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss"))
            .timeZone(TimeZone.getTimeZone("UTC"))
            .build();
    }
}