package com.esolutions.massmailer.security.service;

import com.esolutions.massmailer.security.AdminDtos.AdminUserDto;
import com.esolutions.massmailer.security.model.AdminUser;
import com.esolutions.massmailer.security.repository.AdminSessionTokenRepository;
import com.esolutions.massmailer.security.repository.AdminUserRepository;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.UUID;

@Service
public class AdminUserService {

    private final AdminUserRepository adminUserRepository;
    private final AdminSessionTokenRepository tokenRepo;
    private final BCryptPasswordEncoder passwordEncoder;

    public AdminUserService(AdminUserRepository adminUserRepository,
                            AdminSessionTokenRepository tokenRepo,
                            BCryptPasswordEncoder passwordEncoder) {
        this.adminUserRepository = adminUserRepository;
        this.tokenRepo = tokenRepo;
        this.passwordEncoder = passwordEncoder;
    }

    @Transactional
    public AdminUserDto createUser(String username, String password, String displayName) {
        if (password == null || password.length() < 8) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Password must be at least 8 characters");
        }
        if (adminUserRepository.existsByUsername(username)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Username already exists");
        }
        AdminUser user = AdminUser.builder()
                .username(username)
                .passwordHash(passwordEncoder.encode(password))
                .displayName(displayName)
                .build();
        AdminUser saved = adminUserRepository.save(user);
        return toDto(saved);
    }

    public List<AdminUserDto> listUsers() {
        return adminUserRepository.findAll().stream()
                .map(this::toDto)
                .toList();
    }

    @Transactional
    public void deactivateUser(UUID id) {
        AdminUser user = adminUserRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Admin user not found"));
        if (adminUserRepository.countByActiveTrue() <= 1) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Cannot deactivate the last active administrator");
        }
        user.setActive(false);
        adminUserRepository.save(user);
        tokenRepo.deleteByAdminUser(user);
    }

    @Transactional
    public void reactivateUser(UUID id) {
        AdminUser user = adminUserRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Admin user not found"));
        user.setActive(true);
        adminUserRepository.save(user);
    }

    private AdminUserDto toDto(AdminUser user) {
        return new AdminUserDto(
                user.getId(),
                user.getUsername(),
                user.getDisplayName(),
                user.getRole(),
                user.isActive(),
                user.getCreatedAt()
        );
    }
}
