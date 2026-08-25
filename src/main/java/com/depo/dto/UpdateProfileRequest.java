package com.depo.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.*;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class UpdateProfileRequest {

    @NotBlank(message = "Ad soyad boş olamaz.")
    private String fullName;

    /** Boş bırakılırsa şifre değiştirilmez. */
    private String password;
}
