package com.example.support.domain;

import java.time.Instant;

public record Comment(String id, String caseId, String author, String body, Instant createdAt) {}
