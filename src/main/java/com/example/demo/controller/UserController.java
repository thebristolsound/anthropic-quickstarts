package com.example.demo.controller;

import com.example.demo.dto.CreateUserRequest;
import com.example.demo.dto.UpdateUserRequest;
import com.example.demo.dto.UserDto;
import com.example.demo.entity.User;
import com.example.demo.service.UserService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import jakarta.persistence.OptimisticLockException;
import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/users")
@RequiredArgsConstructor
@Slf4j
@Validated
public class UserController {
    
    private final UserService userService;
    
    @PostMapping
    public ResponseEntity<UserDto> createUser(@Valid @RequestBody CreateUserRequest request) {
        log.info("Creating user with username: {}", request.getUsername());
        
        User user = userService.createUser(request.getUsername(), request.getEmail());
        UserDto userDto = UserDto.fromEntity(user);
        
        log.info("Successfully created user with id: {} and version: {}", user.getId(), user.getVersion());
        return ResponseEntity.status(HttpStatus.CREATED).body(userDto);
    }
    
    @PutMapping("/{id}")
    public ResponseEntity<UserDto> updateUser(@PathVariable Long id, 
                                            @Valid @RequestBody UpdateUserRequest request) {
        log.info("Updating user {} with version {} - new email: {}", 
                 id, request.getVersion(), request.getEmail());
        
        try {
            // This is the key method - no manual version checking!
            User user = userService.updateUser(id, request.getEmail(), request.getVersion());
            UserDto userDto = UserDto.fromEntity(user);
            
            log.info("Successfully updated user {} to version {}", id, user.getVersion());
            return ResponseEntity.ok(userDto);
            
        } catch (OptimisticLockException e) {
            log.warn("Optimistic lock exception for user {}: {}", id, e.getMessage());
            // Return 409 Conflict - client should refresh and retry
            return ResponseEntity.status(HttpStatus.CONFLICT).build();
        }
    }
    
    @PutMapping("/{id}/retry")
    public ResponseEntity<UserDto> updateUserWithRetry(@PathVariable Long id, 
                                                      @RequestParam String email) {
        log.info("Updating user {} email with retry mechanism", id);
        
        try {
            User user = userService.updateUserWithRetry(id, email);
            UserDto userDto = UserDto.fromEntity(user);
            
            log.info("Successfully updated user {} with retry", id);
            return ResponseEntity.ok(userDto);
            
        } catch (RuntimeException e) {
            log.error("Failed to update user {} after retries: {}", id, e.getMessage());
            return ResponseEntity.status(HttpStatus.CONFLICT).build();
        }
    }
    
    @GetMapping("/{id}")
    public ResponseEntity<UserDto> getUser(@PathVariable Long id) {
        log.info("Fetching user with id: {}", id);
        
        User user = userService.findById(id);
        UserDto userDto = UserDto.fromEntity(user);
        
        return ResponseEntity.ok(userDto);
    }
    
    @GetMapping("/username/{username}")
    public ResponseEntity<UserDto> getUserByUsername(@PathVariable String username) {
        log.info("Fetching user with username: {}", username);
        
        User user = userService.findByUsername(username);
        UserDto userDto = UserDto.fromEntity(user);
        
        return ResponseEntity.ok(userDto);
    }
    
    @GetMapping
    public ResponseEntity<Page<UserDto>> getAllUsers(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(defaultValue = "id") String sortBy,
            @RequestParam(defaultValue = "asc") String sortDir) {
        
        Sort.Direction direction = sortDir.equalsIgnoreCase("desc") ? 
            Sort.Direction.DESC : Sort.Direction.ASC;
        Pageable pageable = PageRequest.of(page, size, Sort.by(direction, sortBy));
        
        Page<User> users = userService.findAll(pageable);
        Page<UserDto> userDtos = users.map(UserDto::fromEntity);
        
        return ResponseEntity.ok(userDtos);
    }
    
    @GetMapping("/search")
    public ResponseEntity<Page<UserDto>> searchUsers(
            @RequestParam String keyword,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        
        Pageable pageable = PageRequest.of(page, size);
        Page<User> users = userService.searchUsers(keyword, pageable);
        Page<UserDto> userDtos = users.map(UserDto::fromEntity);
        
        return ResponseEntity.ok(userDtos);
    }
    
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteUser(@PathVariable Long id) {
        log.info("Deleting user with id: {}", id);
        
        userService.deleteUser(id);
        
        log.info("Successfully deleted user with id: {}", id);
        return ResponseEntity.noContent().build();
    }
}