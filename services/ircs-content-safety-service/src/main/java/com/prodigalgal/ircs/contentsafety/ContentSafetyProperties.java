package com.prodigalgal.ircs.contentsafety;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.content-safety")
public class ContentSafetyProperties {

    private final InternalAccess internalAccess = new InternalAccess();
    private final Adult adult = new Adult();

    public InternalAccess internalAccess() {
        return internalAccess;
    }

    public InternalAccess getInternalAccess() {
        return internalAccess;
    }

    public Adult adult() {
        return adult;
    }

    public Adult getAdult() {
        return adult;
    }

    public static class InternalAccess {
        private boolean requireToken;
        private String token = "";
        private String requiredScope = "content-safety:assess";

        public boolean requireToken() {
            return requireToken;
        }

        public boolean isRequireToken() {
            return requireToken;
        }

        public void setRequireToken(boolean requireToken) {
            this.requireToken = requireToken;
        }

        public String token() {
            return token;
        }

        public String getToken() {
            return token;
        }

        public void setToken(String token) {
            this.token = token;
        }

        public String requiredScope() {
            return requiredScope;
        }

        public String getRequiredScope() {
            return requiredScope;
        }

        public void setRequiredScope(String requiredScope) {
            this.requiredScope = requiredScope;
        }
    }

    public static class Adult {
        private int maxTextLength = 512;
        private final Model model = new Model();

        public int maxTextLength() {
            return maxTextLength;
        }

        public int getMaxTextLength() {
            return maxTextLength;
        }

        public void setMaxTextLength(int maxTextLength) {
            this.maxTextLength = maxTextLength;
        }

        public Model model() {
            return model;
        }

        public Model getModel() {
            return model;
        }
    }

    public static class Model {
        private boolean enabled;
        private String name = "uget/sexual_content_dection";
        private String version = "remote";
        private String endpoint = "";
        private String serviceId = "ircs-content-safety-service";
        private String serviceToken = "";
        private String scopes = "adult-classifier:classify";
        private Duration requestTimeout = Duration.ofSeconds(3);
        private double adultThreshold = 0.85d;
        private double suspectThreshold = 0.5d;

        public boolean enabled() {
            return enabled;
        }

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String name() {
            return name;
        }

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public String version() {
            return version;
        }

        public String getVersion() {
            return version;
        }

        public void setVersion(String version) {
            this.version = version;
        }

        public String endpoint() {
            return endpoint;
        }

        public String getEndpoint() {
            return endpoint;
        }

        public void setEndpoint(String endpoint) {
            this.endpoint = endpoint;
        }

        public String serviceId() {
            return serviceId;
        }

        public String getServiceId() {
            return serviceId;
        }

        public void setServiceId(String serviceId) {
            this.serviceId = serviceId;
        }

        public String serviceToken() {
            return serviceToken;
        }

        public String getServiceToken() {
            return serviceToken;
        }

        public void setServiceToken(String serviceToken) {
            this.serviceToken = serviceToken;
        }

        public String scopes() {
            return scopes;
        }

        public String getScopes() {
            return scopes;
        }

        public void setScopes(String scopes) {
            this.scopes = scopes;
        }

        public Duration requestTimeout() {
            return requestTimeout;
        }

        public Duration getRequestTimeout() {
            return requestTimeout;
        }

        public void setRequestTimeout(Duration requestTimeout) {
            this.requestTimeout = requestTimeout;
        }

        public double adultThreshold() {
            return adultThreshold;
        }

        public double getAdultThreshold() {
            return adultThreshold;
        }

        public void setAdultThreshold(double adultThreshold) {
            this.adultThreshold = adultThreshold;
        }

        public double suspectThreshold() {
            return suspectThreshold;
        }

        public double getSuspectThreshold() {
            return suspectThreshold;
        }

        public void setSuspectThreshold(double suspectThreshold) {
            this.suspectThreshold = suspectThreshold;
        }
    }
}
