package com.example.support.domain;

import java.time.LocalDate;

public record Account(
        String id, String name, SupportTier tier, LocalDate contractEnd, LocalDate renewalDate, Long arrUsd) {}
