package com.prodigalgal.ircs.task.runtime;

public final class TaskRuntimeFields {

    public static final String MASTER_TASK_ID = "masterTaskId";
    public static final String PAGE_TASK_ID = "pageTaskId";
    public static final String PAGE_NUMBER = "pageNumber";
    public static final String STATUS = "status";
    public static final String UPDATED_AT = "updatedAt";
    public static final String LAST_ERROR = "lastError";
    public static final String CONTROL_REASON = "controlReason";
    public static final String START_PAGE = "startPage";
    public static final String TOTAL_PAGES = "totalPages";
    public static final String TOTAL_ITEMS = "totalItems";

    public static final String PAGE_SCHEDULED = "pageScheduled";
    public static final String PAGE_DISCOVERED = "pageDiscovered";
    public static final String PAGE_COMPLETED = "pageCompleted";
    public static final String PAGE_SUCCEEDED = "pageSucceeded";
    public static final String PAGE_FAILED = "pageFailed";

    public static final String DETAIL_SCHEDULED = "detailScheduled";
    public static final String DETAIL_COMPLETED = "detailCompleted";
    public static final String DETAIL_SUCCEEDED = "detailSucceeded";
    public static final String DETAIL_FAILED = "detailFailed";

    private TaskRuntimeFields() {
    }
}
