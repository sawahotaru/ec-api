package com.example.ecapi.service;

import com.example.ecapi.domain.Role;
import com.example.ecapi.domain.User;
import com.example.ecapi.dto.AuthDtos.AuthResponse;
import com.example.ecapi.dto.AuthDtos.LoginRequest;
import com.example.ecapi.dto.AuthDtos.RegisterRequest;
import com.example.ecapi.dto.AuthDtos.UserResponse;
import com.example.ecapi.exception.ConflictException;
import com.example.ecapi.exception.TooManyAttemptsException;
import com.example.ecapi.repository.UserRepository;
import com.example.ecapi.security.JwtService;
import com.example.ecapi.security.LoginThrottle;
import java.time.Duration;
import java.time.Instant;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuthService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final LoginThrottle loginThrottle;

    public AuthService(UserRepository userRepository, PasswordEncoder passwordEncoder,
                       JwtService jwtService, LoginThrottle loginThrottle) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
        this.loginThrottle = loginThrottle;
    }

    @Transactional
    public AuthResponse register(RegisterRequest request) {
        if (userRepository.existsByEmail(request.email())) {
            throw new ConflictException("Email already registered: " + request.email());
        }
        User user = new User();
        user.setEmail(request.email());
        user.setPassword(passwordEncoder.encode(request.password()));
        user.setName(request.name());
        user.setRole(Role.USER);
        userRepository.save(user);
        return buildResponse(user);
    }

    /**
     * ログイン。
     *
     * <p>試行制限を<strong>コントローラではなくここ</strong>に置いてあるのは、認証を行う経路が
     * 増えたときに素通りする道ができないようにするため。
     *
     * <p>ロック中は「存在しないユーザー」と同じ扱いにはせず、**残り時間を伝える**。
     * 黙って弾くと、正しいパスワードを持つ本人が「パスワードが違う」と誤解して
     * 何度も試し、ロックを延長し続けることになる。
     */
    @Transactional(readOnly = true)
    public AuthResponse login(LoginRequest request) {
        Instant until = loginThrottle.lockedUntil(request.email());
        if (until != null) {
            long minutes = Math.max(1, Duration.between(Instant.now(), until).toMinutes() + 1);
            throw new TooManyAttemptsException(
                    "ログインの試行回数が上限に達しました。約" + minutes + "分後に再度お試しください。");
        }

        User user = userRepository.findByEmail(request.email()).orElse(null);
        if (user == null || !passwordEncoder.matches(request.password(), user.getPassword())) {
            // 存在しないアドレスへの試行も数える。数えないと「当たりのアドレス探し」が
            // 無制限にできてしまい、しかもそちらのほうが総当たりより先に来る。
            loginThrottle.registerFailure(request.email());
            throw new BadCredentialsException("Invalid email or password");
        }
        loginThrottle.reset(request.email());
        return buildResponse(user);
    }

    private AuthResponse buildResponse(User user) {
        String token = jwtService.generateToken(user.getEmail(), user.getRole().name());
        return new AuthResponse(token, "Bearer", jwtService.getExpirationMs(), UserResponse.from(user));
    }
}
