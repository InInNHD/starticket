package com.starticket.account;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.Set;

record RegisterRequest(
        @NotBlank
        @Pattern(regexp = "[a-zA-Z0-9_]{4,32}", message = "用户名只能包含字母、数字和下划线，长度为4至32位")
        String username,
        @NotBlank @Email @Size(max = 254) String email,
        @NotBlank @Size(min = 8, max = 72) String password
) {
}

record LoginRequest(
        @NotBlank @Size(max = 254) String login,
        @NotBlank @Size(max = 72) String password
) {
}

record UserView(Long id, String username, String email, Set<Role> roles) {
    static UserView from(User user) {
        return new UserView(user.getId(), user.getUsername(), user.getEmail(), user.getRoles());
    }
}

record AuthResponse(String accessToken, String tokenType, long expiresIn, UserView user) {
}
