package com.recon.core.match;

import java.math.BigDecimal;

public record MatchLink(String internalRef, String externalRef, MatchRule rule, BigDecimal score) {
}
