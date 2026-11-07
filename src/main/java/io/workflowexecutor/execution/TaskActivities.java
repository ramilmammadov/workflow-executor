package io.workflowexecutor.execution;

import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityMethod;

@ActivityInterface
public interface TaskActivities {

    /** Activity type name. Fixed explicitly because it is recorded in the history of every run. */
    String RUN_TASK = "RunTask";

    @ActivityMethod(name = RUN_TASK)
    TaskOutcome runTask(TaskInvocation invocation);
}
