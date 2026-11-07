package com.example.ledger.domain;

import jakarta.persistence.*;

@Entity
@Table(name = "accounts")
public class Account {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String owner;

    @Column(nullable = false)
    private Long balanceCents;

    protected Account() {
        // JPA requires a no-arg constructor
    }

    public Account(String owner, Long balanceCents) {
        if (balanceCents == null || balanceCents < 0) {
            throw new IllegalArgumentException("Balance cannot be negative or null");
        }
        this.owner = owner;
        this.balanceCents = balanceCents;
    }

    public Long getId() {
        return id;
    }

    public String getOwner() {
        return owner;
    }

    public Long getBalanceCents() {
        return balanceCents;
    }

    public void credit(Long amountCents) {
        if (amountCents == null || amountCents <= 0) {
            throw new IllegalArgumentException("Credit amount must be positive");
        }
        this.balanceCents += amountCents;
    }

    public void debit(Long amountCents) {
        if (amountCents == null || amountCents <= 0) {
            throw new IllegalArgumentException("Debit amount must be positive");
        }
        if (this.balanceCents < amountCents) {
            throw new IllegalStateException("Insufficient funds");
        }
        this.balanceCents -= amountCents;
    }
}
