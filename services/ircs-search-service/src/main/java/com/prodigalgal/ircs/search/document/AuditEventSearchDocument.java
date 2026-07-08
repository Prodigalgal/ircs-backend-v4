package com.prodigalgal.ircs.search.document;

import java.time.Instant;
import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.elasticsearch.annotations.Document;
import org.springframework.data.elasticsearch.annotations.Field;
import org.springframework.data.elasticsearch.annotations.FieldType;
import org.springframework.data.elasticsearch.annotations.Setting;

@Data
@Document(indexName = AuditEventSearchDocument.INDEX_NAME)
@Setting(settingPath = "elasticsearch/es-settings.json")
public class AuditEventSearchDocument {

    public static final String INDEX_NAME = "ircs-audit-events-v1";

    @Id
    @Field(type = FieldType.Keyword)
    private String id;

    @Field(type = FieldType.Keyword)
    private String sourceTable;

    @Field(type = FieldType.Keyword)
    private String sourceId;

    @Field(type = FieldType.Keyword)
    private String recordType;

    @Field(type = FieldType.Keyword)
    private String auditClass;

    @Field(type = FieldType.Keyword)
    private String eventType;

    @Field(type = FieldType.Keyword)
    private String status;

    @Field(type = FieldType.Keyword)
    private String username;

    @Field(type = FieldType.Keyword)
    private String method;

    @Field(type = FieldType.Keyword)
    private String path;

    @Field(type = FieldType.Text)
    private String queryString;

    @Field(type = FieldType.Integer)
    private Integer statusCode;

    @Field(type = FieldType.Boolean)
    private Boolean success;

    @Field(type = FieldType.Long)
    private Long durationMs;

    @Field(type = FieldType.Keyword)
    private String clientIp;

    @Field(type = FieldType.Text)
    private String userAgent;

    @Field(type = FieldType.Keyword)
    private String traceId;

    @Field(type = FieldType.Keyword)
    private String jobSource;

    @Field(type = FieldType.Keyword)
    private String jobType;

    @Field(type = FieldType.Keyword)
    private String jobName;

    @Field(type = FieldType.Keyword)
    private String correlationId;

    @Field(type = FieldType.Keyword)
    private String recipient;

    @Field(type = FieldType.Text)
    private String subject;

    @Field(type = FieldType.Keyword)
    private String templateCode;

    @Field(type = FieldType.Keyword)
    private String deliveryMode;

    @Field(type = FieldType.Keyword)
    private String credentialId;

    @Field(type = FieldType.Keyword)
    private String errorClass;

    @Field(type = FieldType.Keyword)
    private String failureCode;

    @Field(type = FieldType.Text)
    private String message;

    @Field(type = FieldType.Date)
    private Instant createdAt;

    @Field(type = FieldType.Date)
    private Instant updatedAt;

    @Field(type = FieldType.Date)
    private Instant indexedAt;
}
