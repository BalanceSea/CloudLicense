package cn.cloudlicense.controller;

import cn.cloudlicense.service.UserService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/users")
public class UserAuthController {
    private final UserService users;

    public UserAuthController(UserService users) {
        this.users = users;
    }

    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    public UserService.AuthResult register(@Valid @RequestBody Credentials request) {
        return users.register(request.username(), request.password());
    }

    @PostMapping("/login")
    public UserService.AuthResult login(@Valid @RequestBody Credentials request) {
        return users.login(request.username(), request.password());
    }

    public record Credentials(
            @NotBlank @Pattern(regexp = "[A-Za-z0-9_]{3,32}") String username,
            @NotBlank @Size(min = 8, max = 64) String password
    ) {
    }
}
