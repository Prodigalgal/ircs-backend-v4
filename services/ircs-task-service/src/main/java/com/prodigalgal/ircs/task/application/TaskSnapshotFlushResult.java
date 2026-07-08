package com.prodigalgal.ircs.task.application;

public record TaskSnapshotFlushResult(int discovered, int flushed, int failed) {
}
