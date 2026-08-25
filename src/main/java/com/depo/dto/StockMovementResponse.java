package com.depo.dto;

import com.depo.enums.MovementType;
import lombok.*;

import java.time.LocalDateTime;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class StockMovementResponse {

    private Long id;
    private Long productId;
    private String productName;
    private String productCode;
    private MovementType movementType;
    private Integer quantity;
    private String recipientName;
    private String description;
    private Long createdById;
    private String createdByFullName;
    private Boolean isCancelled;
    private LocalDateTime createdAt;
}
