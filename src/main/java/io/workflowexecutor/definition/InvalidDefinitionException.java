package io.workflowexecutor.definition;

import java.util.List;

/** The definition is well-formed JSON but breaks a rule that needs the whole definition to check. */
public class InvalidDefinitionException extends RuntimeException {

    private final List<DefinitionViolation> violations;

    public InvalidDefinitionException(List<DefinitionViolation> violations) {
        super("Workflow definition has " + violations.size() + " violation(s)");
        this.violations = List.copyOf(violations);
    }

    public List<DefinitionViolation> violations() {
        return violations;
    }
}
