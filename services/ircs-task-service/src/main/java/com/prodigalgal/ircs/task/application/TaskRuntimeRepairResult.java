package com.prodigalgal.ircs.task.application;

public record TaskRuntimeRepairResult(int scanned, int repaired, int finalized, int skipped, int failed) {
}
