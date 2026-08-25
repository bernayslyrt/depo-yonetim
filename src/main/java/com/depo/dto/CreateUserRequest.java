package com.depo.dto;

import com.depo.enums.Role;
import jakarta.validation.constraints.NotBlank;
import lombok.*;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CreateUserRequest {

    @NotBlank(message = "Kullanıcı adı boş olamaz.")
    private String username;

    @NotBlank(message = "Şifre boş olamaz.")
    private String password;

    @NotBlank(message = "Ad soyad boş olamaz.")
    private String fullName;

    private Role role;
}
