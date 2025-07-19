package com.example.demo.service;

import com.example.demo.entity.User;
import com.example.demo.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import jakarta.persistence.EntityManager;
import jakarta.persistence.OptimisticLockException;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
public class UserServiceTest {
    
    @Autowired
    private UserService userService;
    
    @Autowired
    private UserRepository userRepository;
    
    @Autowired
    private EntityManager entityManager;
    
    @Test
    public void testOptimisticLockingWithAutomaticVersionCheck() {
        // Create a user
        User user = userService.createUser("testuser", "test@example.com");
        Long userId = user.getId();
        Long initialVersion = user.getVersion();
        
        assertNotNull(userId);
        assertEquals(0L, initialVersion); // Initial version should be 0
        
        // Update user normally - should work
        User updatedUser = userService.updateUser(userId, "newemail@example.com", initialVersion);
        assertEquals("newemail@example.com", updatedUser.getEmail());
        assertEquals(1L, updatedUser.getVersion()); // Version should be incremented
    }
    
    @Test
    public void testOptimisticLockingConflict() {
        // Create a user
        User user = userService.createUser("testuser2", "test2@example.com");
        Long userId = user.getId();
        Long initialVersion = user.getVersion();
        
        // Simulate concurrent modification by updating the user directly
        User directUser = userRepository.findById(userId).get();
        directUser.setEmail("changed@example.com");
        userRepository.save(directUser);
        entityManager.flush(); // Ensure the change is persisted
        
        // Now try to update with the old version - should fail
        assertThrows(OptimisticLockException.class, () -> {
            userService.updateUser(userId, "anotheremail@example.com", initialVersion);
        });
    }
    
    @Test
    public void testRetryMechanism() {
        // Create a user
        User user = userService.createUser("testuser3", "test3@example.com");
        Long userId = user.getId();
        
        // This should work as it fetches fresh version each time
        User updatedUser = userService.updateUserWithRetry(userId, "retry@example.com");
        assertEquals("retry@example.com", updatedUser.getEmail());
        assertNotNull(updatedUser.getVersion());
    }
    
    @Test
    public void testVersionIncrementsAutomatically() {
        // Create a user
        User user = userService.createUser("testuser4", "test4@example.com");
        Long userId = user.getId();
        Long version0 = user.getVersion();
        
        // Update 1
        User updated1 = userService.updateUser(userId, "email1@example.com", version0);
        Long version1 = updated1.getVersion();
        assertEquals(version0 + 1, version1);
        
        // Update 2
        User updated2 = userService.updateUser(userId, "email2@example.com", version1);
        Long version2 = updated2.getVersion();
        assertEquals(version1 + 1, version2);
        
        // Update 3
        User updated3 = userService.updateUser(userId, "email3@example.com", version2);
        Long version3 = updated3.getVersion();
        assertEquals(version2 + 1, version3);
    }
    
    @Test 
    public void testWrongVersionThrowsException() {
        // Create a user
        User user = userService.createUser("testuser5", "test5@example.com");
        Long userId = user.getId();
        
        // Try to update with wrong version
        assertThrows(OptimisticLockException.class, () -> {
            userService.updateUser(userId, "wrong@example.com", 999L);
        });
    }
}