package com.example.requestmanagement.infrastructure.config;

import com.example.requestmanagement.domain.statemachine.DefaultRequestStateMachine;
import com.example.requestmanagement.domain.statemachine.RequestStateMachine;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

@Configuration
public class DomainConfig {

    @Bean
    public RequestStateMachine requestStateMachine() {
        return new DefaultRequestStateMachine();
    }

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
