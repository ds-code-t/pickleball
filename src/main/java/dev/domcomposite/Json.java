package dev.domcomposite;

import com.fasterxml.jackson.databind.ObjectMapper;

/** Shared mapper. Configured once; safe to read from any thread. */
final class Json {
    static final ObjectMapper MAPPER = new ObjectMapper();

    private Json() {}
}
