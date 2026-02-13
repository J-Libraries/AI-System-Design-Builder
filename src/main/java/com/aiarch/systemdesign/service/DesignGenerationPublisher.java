package com.aiarch.systemdesign.service;

import com.aiarch.systemdesign.dto.GenerationEvent;
import com.aiarch.systemdesign.dto.GenerationStatus;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class DesignGenerationPublisher {

    private static final Logger log = LoggerFactory.getLogger(DesignGenerationPublisher.class);

    private final SimpMessagingTemplate messagingTemplate;

    public void publishStageStarted(String designId, String stageName, int progress) {
        publish(GenerationEvent.builder()
                .designId(designId)
                .stageName(stageName)
                .status(GenerationStatus.STARTED)
                .progressPercentage(progress)
                .build());
    }

    public void publishStageCompleted(String designId, String stageName, int progress, String payload) {
        publish(GenerationEvent.builder()
                .designId(designId)
                .stageName(stageName)
                .status(GenerationStatus.COMPLETED)
                .progressPercentage(progress)
                .payload(payload)
                .build());
    }

    public void publishStageFailed(String designId, String stageName, String errorMessage) {
        publish(GenerationEvent.builder()
                .designId(designId)
                .stageName(stageName)
                .status(GenerationStatus.FAILED)
                .payload(errorMessage)
                .build());
    }

    private void publish(GenerationEvent event) {
        String destination = "/topic/design/" + event.getDesignId();
        messagingTemplate.convertAndSend(destination, event);
        log.debug(
                "Published generation event designId={}, stage={}, status={}",
                event.getDesignId(),
                event.getStageName(),
                event.getStatus()
        );
    }
}
