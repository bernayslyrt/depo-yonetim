package com.depo.dto;

import com.depo.enums.MovementType;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.*;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class StockMovementRequest {

    @NotNull(message = "Ürün ID boş olamaz.")
    private Long productId;

    @NotNull(message = "Hareket tipi boş olamaz.")
    private MovementType movementType;

    @NotNull(message = "Miktar boş olamaz.")
    @Min(value = 1, message = "Miktar en az 1 olmalıdır.")
    private Integer quantity;

    private String recipientName;

    private String description;

    // ID of the User who performed this stock movement. Trusted directly for MVP.
    private Long userId;
}
