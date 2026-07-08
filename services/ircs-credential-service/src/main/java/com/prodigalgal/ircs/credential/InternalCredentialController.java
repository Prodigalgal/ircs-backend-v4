package com.prodigalgal.ircs.credential;

import com.prodigalgal.ircs.common.security.InternalServiceAccessPolicy;
import com.prodigalgal.ircs.common.security.InternalServiceAuthHeaders;
import com.prodigalgal.ircs.contracts.credential.ProviderCredentialLease;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@Validated
@RestController
@RequiredArgsConstructor
@RequestMapping("/internal/credentials")
public class InternalCredentialController {

    private final CredentialService credentialService;
    private final CredentialInternalAccessProperties internalAccessProperties;

    @GetMapping("/providers/{provider}/leases")
    public List<ProviderCredentialLease> leaseProviderCredentials(
            @PathVariable String provider,
            @RequestParam(required = false) String requiredPayloadKey,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int limit,
            @RequestHeader(value = InternalServiceAuthHeaders.SERVICE_ID, required = false) String serviceId,
            @RequestHeader(value = InternalServiceAuthHeaders.SERVICE_TOKEN, required = false) String serviceToken,
            @RequestHeader(value = InternalServiceAuthHeaders.SERVICE_SCOPES, required = false) String serviceScopes) {
        assertInternalAccess(serviceId, serviceToken, serviceScopes);
        return credentialService.leaseProviderCredentials(provider, requiredPayloadKey, limit);
    }

    private void assertInternalAccess(String serviceId, String serviceToken, String serviceScopes) {
        InternalServiceAccessPolicy.require(
                "Credential",
                internalAccessProperties.getToken(),
                internalAccessProperties.getRequiredScope(),
                serviceId,
                serviceToken,
                serviceScopes);
    }
}
