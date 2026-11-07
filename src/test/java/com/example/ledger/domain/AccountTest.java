package com.example.ledger.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AccountTest {

    @Test
    void cannotCreateAccountWithNegativeBalance() {
        assertThatThrownBy(() -> new Account("Alice", -100L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("cannot be negative");
    }

    @Test
    void creditIncreasesBalance() {
        Account account = new Account("Alice", 1000L);
        account.credit(500L);
        assertThat(account.getBalanceCents()).isEqualTo(1500L);
    }

    @Test
    void debitDecreasesBalance() {
        Account account = new Account("Alice", 1000L);
        account.debit(400L);
        assertThat(account.getBalanceCents()).isEqualTo(600L);
    }

    @Test
    void debitThrowsWhenInsufficientFunds() {
        Account account = new Account("Alice", 300L);
        assertThatThrownBy(() -> account.debit(500L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Insufficient funds");
    }
}
