package com.example.requestmanagement.infrastructure.config;

import com.example.requestmanagement.application.port.ActorProvider;
import org.springframework.stereotype.Component;

@Component
public class SystemActorProvider implements ActorProvider {

    public static final String SYSTEM_ACTOR = "SYSTEM";

    @Override
    public String currentActor() {
        return SYSTEM_ACTOR;
    }
}
