package com.prodigalgal.ircs.credential;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/credentials")
public class CredentialController {

    private final CredentialService credentialService;

    @GetMapping
    public List<CredentialSummary> listCredentials(
            @RequestParam(required = false) String provider,
            @RequestParam(required = false) Boolean enabled,
            @RequestParam(defaultValue = "100") @Min(1) @Max(500) int limit) {
        return credentialService.list(provider, enabled, limit);
    }

    @GetMapping("/{id}")
    public ResponseEntity<CredentialSummary> getCredential(@PathVariable UUID id) {
        return credentialService.findById(id)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @GetMapping("/templates")
    public List<CredentialTemplateField> credentialTemplates(@RequestParam String provider) {
        return credentialService.templates(provider);
    }

    @PostMapping
    public ResponseEntity<CredentialSummary> createCredential(@Valid @RequestBody CredentialWriteRequest request) {
        CredentialSummary result = credentialService.create(request);
        return ResponseEntity
                .created(URI.create("/api/v1/credentials/" + result.id()))
                .body(result);
    }

    @PutMapping("/{id}")
    public ResponseEntity<CredentialSummary> updateCredential(
            @PathVariable UUID id,
            @Valid @RequestBody CredentialWriteRequest request) {
        return credentialService.update(id, request)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteCredential(@PathVariable UUID id) {
        credentialService.delete(id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/refresh")
    public ResponseEntity<Void> refreshCredentials() {
        credentialService.refreshPool();
        return ResponseEntity.ok().build();
    }
}
