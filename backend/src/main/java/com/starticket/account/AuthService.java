package com.starticket.account;

import com.starticket.common.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Locale;

@Service
class AuthService {

    private final UserRepository users;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;

    AuthService(UserRepository users, PasswordEncoder passwordEncoder, JwtService jwtService) {
        this.users = users;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
    }

    @Transactional
    AuthResponse register(RegisterRequest request) {
        String username = normalize(request.username());
        String email = normalize(request.email());
        if (users.existsByUsername(username)) {
            throw new ApiException(HttpStatus.CONFLICT, "用户名已存在");
        }
        if (users.existsByEmail(email)) {
            throw new ApiException(HttpStatus.CONFLICT, "邮箱已存在");
        }
        User user = users.save(User.create(username, email, passwordEncoder.encode(request.password())));
        return response(user);
    }

    @Transactional(readOnly = true)
    AuthResponse login(LoginRequest request) {
        String login = normalize(request.login());
        User user = users.findByUsernameOrEmail(login, login)
                .filter(User::isEnabled)
                .filter(found -> passwordEncoder.matches(request.password(), found.getPasswordHash()))
                .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "用户名或密码错误"));
        return response(user);
    }

    @Transactional(readOnly = true)
    UserView currentUser(String username) {
        return users.findByUsernameOrEmail(username, username)
                .map(UserView::from)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "用户不存在"));
    }

    private AuthResponse response(User user) {
        JwtService.IssuedToken token = jwtService.issue(user);
        return new AuthResponse(token.value(), "Bearer", token.expiresIn(), UserView.from(user));
    }

    private static String normalize(String value) {
        return value.trim().toLowerCase(Locale.ROOT);
    }
}
