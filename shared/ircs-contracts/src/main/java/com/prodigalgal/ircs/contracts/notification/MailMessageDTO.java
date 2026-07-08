package com.prodigalgal.ircs.contracts.notification;

import java.io.Serializable;
import java.util.Map;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MailMessageDTO implements Serializable {
    private String to;
    private String subject;
    private String content;
    private boolean html;
    private String templateCode;
    private Map<String, Object> variables;
}

