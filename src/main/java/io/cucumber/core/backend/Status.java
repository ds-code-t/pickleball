package io.cucumber.core.backend;

import org.apiguardian.api.API;

@API(status = API.Status.STABLE)
public enum Status {
    PASSED,
    SKIPPED,
    PENDING,
    SOFT_FAILED,
    UNDEFINED,
    AMBIGUOUS,
    FAILED,
    UNUSED,
    RUNNING,
    COMPLETED
}
