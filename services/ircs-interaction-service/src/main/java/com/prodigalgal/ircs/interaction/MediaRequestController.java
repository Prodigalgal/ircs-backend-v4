package com.prodigalgal.ircs.interaction;

import com.prodigalgal.ircs.common.web.PageEnvelope;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/portal/media-requests")
public class MediaRequestController {

    private final MediaRequestCommandService commandService;
    private final MemberTokenService memberTokenService;

    @PostMapping
    public MediaRequestResponse submit(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestBody MediaRequestSubmitRequest request) {
        UUID memberId = memberTokenService.requireMemberId(authorization);
        return commandService.submit(memberId, request);
    }

    @GetMapping
    public PageEnvelope<MediaRequestResponse> myRequests(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size) {
        UUID memberId = memberTokenService.requireMemberId(authorization);
        PageResponse<MediaRequestResponse> requests =
                commandService.myRequests(memberId, PageBounds.of(page, size, 10, 70));
        return PageEnvelope.of(requests.content(), requests.number(), requests.size(), requests.totalElements());
    }
}
