package com.example.backend.dto.notification;

import com.example.backend.model.entity.notification.Notification;
import com.example.backend.model.entity.notification.NotificationMessage;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Builder
@AllArgsConstructor
@Data
@NoArgsConstructor
public class NotificationResponseDTO {
    private String id;
    private String title;
    private String content;
    private String category;
    private Long targetId;
    private LocalDateTime createdAt;
    private boolean isRead;

    public static NotificationResponseDTO toResponseDTO(NotificationMessage notificationMessage) {
        return NotificationResponseDTO.builder()
                .id(notificationMessage.getId() != null ? notificationMessage.getId() : null)
                .title(notificationMessage.getTitle())
                .content(notificationMessage.getContent())
                .category(notificationMessage.getCategory())
                .targetId(notificationMessage.getTargetId())
                .createdAt(notificationMessage.getCreatedAt())
                .isRead(notificationMessage.isRead())
                .build();
    }

    public static List<NotificationResponseDTO> toResponseDTOList(List<NotificationMessage> notificationMessages) {
        return notificationMessages.stream()
                .map(NotificationResponseDTO::toResponseDTO)
                .toList();
    }
}
