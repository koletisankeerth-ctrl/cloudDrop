package com.clouddrop.controller;

import com.clouddrop.auth.AuthContext;
import com.clouddrop.auth.IssuedToken;
import com.clouddrop.auth.JwtService;
import com.clouddrop.dto.LoginRequest;
import com.clouddrop.dto.LoginResponse;
import com.clouddrop.dto.RegisterRequest;
import com.clouddrop.dto.UserResponse;
import com.clouddrop.entity.User;
import com.clouddrop.service.UserService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final UserService userService;
    private final JwtService jwtService;

    public AuthController(UserService userService, JwtService jwtService) {
        this.userService = userService;
        this.jwtService = jwtService;
    }

    // @Valid triggers the Bean Validation annotations on the DTO; failures become a 400 via GlobalExceptionHandler.
    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    public UserResponse register(@Valid @RequestBody RegisterRequest request) {
        return UserResponse.from(userService.register(request.username(), request.email(), request.password()));
    }

    @PostMapping("/login")
    public LoginResponse login(@Valid @RequestBody LoginRequest request) {
        User user = userService.authenticate(request.email(), request.password());
        IssuedToken issued = jwtService.generate(user);
        return new LoginResponse(issued.token(), "Bearer", issued.expiresAt(), UserResponse.from(user));
    }

    // The user id comes from AuthContext (set by JwtAuthFilter from the validated token), never from the request.
    @GetMapping("/me")
    public UserResponse me() {
        return UserResponse.from(userService.getById(AuthContext.requireUser().id()));
    }
}
