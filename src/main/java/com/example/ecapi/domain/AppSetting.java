package com.example.ecapi.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * A simple key/value store-level setting (e.g. the tax pricing mode). Kept generic so
 * future runtime-tunable options can reuse it without new tables.
 */
@Entity
@Table(name = "app_settings")
public class AppSetting {

    @Id
    @Column(name = "setting_key", length = 64)
    private String key;

    @Column(name = "setting_value", nullable = false, length = 255)
    private String value;

    public AppSetting() {
    }

    public AppSetting(String key, String value) {
        this.key = key;
        this.value = value;
    }

    public String getKey() {
        return key;
    }

    public void setKey(String key) {
        this.key = key;
    }

    public String getValue() {
        return value;
    }

    public void setValue(String value) {
        this.value = value;
    }
}
