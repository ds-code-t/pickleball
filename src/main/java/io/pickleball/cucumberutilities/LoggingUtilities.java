package io.pickleball.cucumberutilities;

import io.cucumber.core.backend.Status;

import java.util.List;

public class LoggingUtilities {


    public static Status getHighestStatus(List<Status> statuses) {
        if(statuses.isEmpty())
            return Status.PASSED;
        return statuses.stream()
                .max(Enum::compareTo) // Compare by ordinal implicitly
                .orElseThrow(() -> new IllegalArgumentException("Status list is empty!"));
    }

}
