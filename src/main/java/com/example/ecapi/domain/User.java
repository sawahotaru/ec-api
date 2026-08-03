package com.example.ecapi.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import org.hibernate.annotations.ColumnDefault;

@Entity
@Table(name = "users")
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String email;

    @Column(nullable = false)
    private String password;

    @Column(nullable = false)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Role role = Role.USER;

    /**
     * TOTP の共有鍵（Base32）。null なら二段階認証は未設定。
     *
     * <p>⚠ <strong>鍵が入っていることと、有効になっていることは別</strong>。登録の途中
     * （鍵は発行したが、まだ1回もコードを通していない）で有効扱いにすると、
     * 認証アプリの登録に失敗した人がそのまま締め出される。有効化は
     * {@link #mfaEnabled} が担い、コードを1回通して初めて true になる。
     */
    @Column(length = 64)
    private String mfaSecret;

    @Column(nullable = false)
    @ColumnDefault("false")
    private boolean mfaEnabled = false;

    /**
     * リカバリコードのハッシュ（改行区切り）。認証アプリを失くしたときの最後の入口。
     *
     * <p>平文では持たない。パスワードと同じく、漏れた時点でそのまま鍵になるため。
     * 使い捨てで、1つ使うたびに行が消える。
     */
    @Column(length = 2000)
    private String mfaRecoveryCodes;

    @Column(nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getMfaSecret() {
        return mfaSecret;
    }

    public void setMfaSecret(String mfaSecret) {
        this.mfaSecret = mfaSecret;
    }

    public boolean isMfaEnabled() {
        return mfaEnabled;
    }

    public void setMfaEnabled(boolean mfaEnabled) {
        this.mfaEnabled = mfaEnabled;
    }

    public String getMfaRecoveryCodes() {
        return mfaRecoveryCodes;
    }

    public void setMfaRecoveryCodes(String mfaRecoveryCodes) {
        this.mfaRecoveryCodes = mfaRecoveryCodes;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public Role getRole() {
        return role;
    }

    public void setRole(Role role) {
        this.role = role;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
}
