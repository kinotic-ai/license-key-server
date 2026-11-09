package org.kinotic.licenses.support;

import org.junit.jupiter.api.extension.ConditionEvaluationResult;
import org.junit.jupiter.api.extension.ExecutionCondition;
import org.junit.jupiter.api.extension.ExtensionContext;

/**
 * Disables a server test class, before Spring prepares its context, when the suite has no database: neither
 * {@code LICENSES_TEST_DATABASE_URL} nor Docker for Testcontainers.
 */
public class DatabaseAvailableCondition implements ExecutionCondition {

    @Override
    public ConditionEvaluationResult evaluateExecutionCondition(ExtensionContext context) {
        return TestDatabase.available()
                ? ConditionEvaluationResult.enabled("A database is available")
                : ConditionEvaluationResult.disabled("No Postgres: set LICENSES_TEST_DATABASE_URL or run Docker for Testcontainers");
    }
}
