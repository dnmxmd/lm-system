package com.lms.analytics.model;

import java.math.BigDecimal;

/** Revenue in one currency. Amounts in different currencies are never added together. */
public record CurrencyTotal(String currency, BigDecimal amount) {
}
