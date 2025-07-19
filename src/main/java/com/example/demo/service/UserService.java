package com.example.demo.service;

import com.example.demo.entity.User;
import com.example.demo.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Recover;
import org.springframework.retry.annotation.Retryable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import jakarta.persistence.EntityNotFoundException;
import jakarta.persistence.OptimisticLockException;

@Service
@Transactional
@RequiredArgsConstructor
@Slf4j
public class UserService {
    
    private final UserRepository userRepository;
    
    public User createUser(String username, String email) {
        // Check if username or email already exists
        if (userRepository.existsByUsername(username)) {
            throw new IllegalArgumentException("Username already exists: " + username);
        }
        if (userRepository.existsByEmail(email)) {
            throw new IllegalArgumentException("Email already exists: " + email);
        }
        
        User user = User.builder()
            .username(username)
            .email(email)
            .build();
        
        log.info("Creating new user with username: {}", username);
        return userRepository.save(user);
    }
    
    /**
     * Updates user email with automatic version checking.
     * No manual version check needed - Hibernate handles it automatically!
     */
    public User updateUser(Long id, String newEmail, Long version) {
        User user = userRepository.findById(id)
            .orElseThrow(() -> new EntityNotFoundException("User not found with id: " + id));
        
        // Set the version from the client request - this is crucial!
        // Hibernate will compare this version with the database version
        user.setVersion(version);
        user.setEmail(newEmail);
        
        log.info("Updating user {} email to {} with version {}", id, newEmail, version);
        
        // Hibernate automatically:
        // 1. Compares user.version with database version
        // 2. Throws OptimisticLockException if they don't match
        // 3. Increments version and updates if they match
        return userRepository.save(user);
    }
    
    /**
     * Update user with automatic retry on version conflicts
     */
    @Retryable(value = OptimisticLockException.class, maxAttempts = 3, backoff = @Backoff(delay = 100))
    public User updateUserWithRetry(Long id, String newEmail) {
        // Fetch fresh entity each time to get latest version
        User user = userRepository.findById(id)
            .orElseThrow(() -> new EntityNotFoundException("User not found with id: " + id));
        
        log.info("Attempting to update user {} email with retry mechanism (current version: {})", 
                 id, user.getVersion());
        
        user.setEmail(newEmail);
        return userRepository.save(user);
    }
    
    @Recover
    public User recoverFromOptimisticLock(OptimisticLockException ex, Long id, String newEmail) {
        log.error("Failed to update user {} after all retry attempts: {}", id, ex.getMessage());
        throw new RuntimeException("Unable to update user after multiple attempts. Please try again later.");
    }
    
    @Transactional(readOnly = true)
    public User findById(Long id) {
        return userRepository.findById(id)
            .orElseThrow(() -> new EntityNotFoundException("User not found with id: " + id));
    }
    
    @Transactional(readOnly = true)
    public User findByUsername(String username) {
        return userRepository.findByUsername(username)
            .orElseThrow(() -> new EntityNotFoundException("User not found with username: " + username));
    }
    
    @Transactional(readOnly = true)
    public Page<User> findAll(Pageable pageable) {
        return userRepository.findAll(pageable);
    }
    
    @Transactional(readOnly = true)
    public Page<User> searchUsers(String keyword, Pageable pageable) {
        return userRepository.findByKeyword(keyword, pageable);
    }
    
    public void deleteUser(Long id) {
        if (!userRepository.existsById(id)) {
            throw new EntityNotFoundException("User not found with id: " + id);
        }
        
        log.info("Deleting user with id: {}", id);
        userRepository.deleteById(id);
    }
}