package com.example.opencodemobile;

final class ModelConfig {
    static final int DEFAULT_CONTEXT_LIMIT = 128_000;
    static final int DEFAULT_OUTPUT_LIMIT = 8_192;
    final String id;
    final String name;
    final String baseUrl;
    final String apiKey;
    final String model;
    final int contextLimit;
    final int outputLimit;

    ModelConfig(String id, String name, String baseUrl, String apiKey, String model,
                int contextLimit, int outputLimit) {
        this.id = id;
        this.name = name;
        this.baseUrl = baseUrl;
        this.apiKey = apiKey;
        this.model = model;
        this.contextLimit = contextLimit;
        this.outputLimit = outputLimit;
    }

    boolean isReady() {
        return !baseUrl.trim().isEmpty() && !apiKey.trim().isEmpty()
                && !model.trim().isEmpty() && !id.trim().isEmpty();
    }

    String displayName() {
        return name.trim().isEmpty() ? model : name;
    }
}
