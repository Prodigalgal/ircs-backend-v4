package com.prodigalgal.ircs.contracts.notification;

import java.io.Serializable;
import java.util.List;
import java.util.Map;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class NotificationCommand implements Serializable {
    private String commandId;
    private String correlationId;
    private NotificationChannel channel;
    private List<String> recipients;
    private String subject;
    private String content;
    private boolean html;
    private String templateCode;
    private Map<String, Object> variables;
    private Map<String, Object> channelOptions;
}
