package com.depo.service;

import com.depo.dto.CreateUserRequest;
import com.depo.dto.UpdateProfileRequest;
import com.depo.dto.UpdateUserRequest;
import com.depo.dto.UserResponse;

import java.util.List;

public interface UserService {

    List<UserResponse> getAllUsers();

    UserResponse getUserById(Long id);

    UserResponse createUser(CreateUserRequest request);

    UserResponse updateUser(Long id, UpdateUserRequest request);

    UserResponse updateProfile(UpdateProfileRequest request);

    void deleteUser(Long id);
}
