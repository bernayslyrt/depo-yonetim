package com.depo.dto;

import com.depo.enums.Role;
import jakarta.validation.constraints.NotBlank;
import lombok.*;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class UpdateUserRequest {

    @NotBlank(message = "Kullanıcı adı boş olamaz.")
    private String username;

    private String password;

    @NotBlank(message = "Ad soyad boş olamaz.")
    private String fullName;

    private Role role;
}
