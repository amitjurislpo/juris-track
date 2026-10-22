package com.jurislpo.juristrack.security;

import com.jurislpo.juristrack.common.ApiResult;
import com.jurislpo.juristrack.entity.Role;
import com.jurislpo.juristrack.exception.ApiException;
import com.jurislpo.juristrack.repository.UserRepository;
import java.util.Map;
import java.util.UUID;
import org.springframework.boot.test.context.TestComponent;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Test-only endpoints that stand in for the module endpoints of later phases, to prove the request plumbing through the API: the
 * tenant of the request (row-level security), a role check, and a write. Imported by {@link EmbeddedIntegrationTest} only.
 */
@TestComponent
@RestController
@RequestMapping("/api/test-probes")
class ProbeController {

    private final Auth auth;
    private final UserRepository userRepository;

    ProbeController(Auth auth, UserRepository userRepository) {
        this.auth = auth;
        this.userRepository = userRepository;
    }

    /** A user by id, as the caller's business sees it: 404 for a user of another business. */
    @GetMapping("/users/{userId}")
    ApiResult<Map<String, Object>> findUser(@PathVariable UUID userId) {
        auth.requireUser();
        return userRepository.findById(userId)
                .map(user -> ApiResult.success(Map.<String, Object>of("id", user.getId(), "email", user.getEmail())))
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Not found."));
    }

    /** Only the business owner. */
    @GetMapping("/owner-area")
    ApiResult<Void> openOwnerArea() {
        auth.requireRole(Role.ADMIN);
        return ApiResult.message("Welcome, owner.");
    }

    /** Any signed-in user; a write, so the Origin check applies. */
    @PostMapping("/writes")
    ApiResult<Void> write() {
        auth.requireUser();
        return ApiResult.message("Written.");
    }
}
