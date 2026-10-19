package com.example.requestmanagement.infrastructure.persistence.adapter;

import com.example.requestmanagement.application.port.PublicationIdGenerator;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

@Component
public class SequencePublicationIdGenerator implements PublicationIdGenerator {

    private final JdbcClient jdbcClient;

    public SequencePublicationIdGenerator(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    @Override
    public long nextId() {
        return jdbcClient.sql("SELECT NEXT VALUE FOR publication_id_seq")
                .query(Long.class)
                .single();
    }
}
