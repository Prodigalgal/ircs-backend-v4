package com.prodigalgal.ircs.common.work;

public interface WorkSubmissionGate {

    default boolean canSubmitRuntime(String taskType) {
        return true;
    }
}
