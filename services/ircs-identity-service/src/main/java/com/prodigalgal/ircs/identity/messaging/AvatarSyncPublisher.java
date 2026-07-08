package com.prodigalgal.ircs.identity.messaging;

import com.prodigalgal.ircs.common.storage.StorageWorkPublisher;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class AvatarSyncPublisher {

    private final StorageWorkPublisher storageWorkPublisher;

    public void publish(UUID memberId) {
        storageWorkPublisher.enqueueAvatarSync(memberId, "member-avatar-updated");
    }
}
