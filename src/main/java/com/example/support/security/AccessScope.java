package com.example.support.security;

import java.util.Set;

/** Which accounts a caller may see. A null set means all of them. */
public record AccessScope(Set<String> accounts) {
    public static final AccessScope ALL = new AccessScope(null);

    public boolean canSee(String accountId) {
        return accounts == null || accounts.contains(accountId);
    }
}
