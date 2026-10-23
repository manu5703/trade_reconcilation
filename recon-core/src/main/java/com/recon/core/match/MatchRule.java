package com.recon.core.match;

/** The pass that produced a match; stored as {@code match_link.rule} so every decision can be explained. */
public enum MatchRule {
    EXACT,
    BLOCK,
    FUZZY
}
