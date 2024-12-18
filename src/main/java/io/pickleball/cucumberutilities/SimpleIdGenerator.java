package io.pickleball.cucumberutilities;

import io.cucumber.messages.IdGenerator;

public class SimpleIdGenerator implements IdGenerator {
    private long counter = 0;

    @Override
    public String newId() {
        return "id-" + (++counter);
    }
}
