package com.clouddrop.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

// @Entity maps this class to a table; @Table names it explicitly because schema.sql owns the schema.
// JPA requires a no-arg constructor, which Lombok generates (protected is enough for Hibernate).
@Entity
@Table(name = "users")
@Getter
@Setter
@NoArgsConstructor
public class User {

    // IDENTITY = the database AUTO_INCREMENT column generates the id.
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 50)
    private String username;

    @Column(nullable = false, unique = true)
    private String email;

    @Column(name = "password_hash", nullable = false, length = 100)
    private String passwordHash;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
}
