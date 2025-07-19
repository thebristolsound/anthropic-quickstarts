# JPA Versioning in Spring Boot Applications

## Overview

JPA (Java Persistence API) versioning in Spring Boot applications refers to optimistic locking mechanisms that help prevent concurrent modification conflicts. This is crucial for maintaining data integrity in multi-user environments where multiple transactions might attempt to modify the same entity simultaneously.

## Types of Versioning

### 1. Optimistic Locking with @Version

The most common approach uses the `@Version` annotation to implement optimistic locking.

**How it works:**
- Each entity has a version field (typically `Long` or `Integer`)
- Every time an entity is updated, the version is automatically incremented
- If two transactions try to update the same entity, the second one will fail with `OptimisticLockException`

### 2. Timestamp-based Versioning

Uses a timestamp field to track when the entity was last modified.

### 3. Custom Versioning Strategies

Custom implementations using specific business logic or combination of fields.

## Implementation Examples

### Basic Entity with Version Field

```java
@Entity
@Table(name = "users")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EntityListeners(AuditingEntityListener.class)
public class User {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    
    @Column(nullable = false, unique = true)
    private String username;
    
    @Column(nullable = false)
    private String email;
    
    @Version
    private Long version;
    
    @CreatedDate
    @Column(updatable = false)
    private LocalDateTime createdAt;
    
    @LastModifiedDate
    private LocalDateTime updatedAt;
    
    // Custom constructor for common use case
    public User(String username, String email) {
        this.username = username;
        this.email = email;
    }
}
```

### Repository Interface

```java
@Repository
public interface UserRepository extends JpaRepository<User, Long> {
    Optional<User> findByUsername(String username);
    
    @Query("SELECT u FROM User u WHERE u.email = :email")
    Optional<User> findByEmail(@Param("email") String email);
    
    @Modifying
    @Query("UPDATE User u SET u.email = :email WHERE u.id = :id AND u.version = :version")
    int updateEmailWithVersion(@Param("id") Long id, 
                              @Param("email") String email, 
                              @Param("version") Long version);
}
```

### Service Layer with Proper Version Handling

```java
@Service
@Transactional
@RequiredArgsConstructor
@Slf4j
public class UserService {
    
    private final UserRepository userRepository;
    
    public User createUser(String username, String email) {
        User user = User.builder()
            .username(username)
            .email(email)
            .build();
        
        log.info("Creating new user with username: {}", username);
        return userRepository.save(user);
    }
    
    public User updateUser(Long id, String newEmail, Long version) {
        User user = userRepository.findById(id)
            .orElseThrow(() -> new EntityNotFoundException("User not found with id: " + id));
        
        // Check if the version matches (optimistic locking)
        if (!user.getVersion().equals(version)) {
            log.warn("Version mismatch for user {}: expected {}, got {}", 
                id, version, user.getVersion());
            throw new OptimisticLockException("User has been modified by another transaction");
        }
        
        log.info("Updating user {} email from {} to {}", id, user.getEmail(), newEmail);
        user.setEmail(newEmail);
        return userRepository.save(user);
    }
    
    @Retryable(value = OptimisticLockException.class, maxAttempts = 3, backoff = @Backoff(delay = 100))
    public User updateUserWithRetry(Long id, String newEmail) {
        User user = userRepository.findById(id)
            .orElseThrow(() -> new EntityNotFoundException("User not found with id: " + id));
        
        log.info("Attempting to update user {} email with retry mechanism", id);
        user.setEmail(newEmail);
        return userRepository.save(user);
    }
    
    @Recover
    public User recoverFromOptimisticLock(OptimisticLockException ex, Long id, String newEmail) {
        log.error("Failed to update user {} after all retry attempts: {}", id, ex.getMessage());
        throw new BusinessException("Unable to update user after multiple attempts. Please try again later.");
    }
    
    @Transactional(readOnly = true)
    public User findById(Long id) {
        return userRepository.findById(id)
            .orElseThrow(() -> new EntityNotFoundException("User not found with id: " + id));
    }
}
```

### REST Controller with Version Handling

```java
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
        return ResponseEntity.status(HttpStatus.CREATED)
            .body(UserDto.fromEntity(user));
    }
    
    @PutMapping("/{id}")
    public ResponseEntity<UserDto> updateUser(@PathVariable Long id, 
                                            @Valid @RequestBody UpdateUserRequest request) {
        try {
            log.info("Updating user {} with version {}", id, request.getVersion());
            User user = userService.updateUser(id, request.getEmail(), request.getVersion());
            return ResponseEntity.ok(UserDto.fromEntity(user));
        } catch (OptimisticLockException e) {
            log.warn("Optimistic lock exception for user {}: {}", id, e.getMessage());
            return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(null); // or return error details
        }
    }
    
    @GetMapping("/{id}")
    public ResponseEntity<UserDto> getUser(@PathVariable Long id) {
        log.info("Fetching user with id: {}", id);
        User user = userService.findById(id);
        return ResponseEntity.ok(UserDto.fromEntity(user));
    }
    
    @GetMapping
    public ResponseEntity<Page<UserDto>> getAllUsers(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(defaultValue = "id") String sortBy) {
        
        Pageable pageable = PageRequest.of(page, size, Sort.by(sortBy));
        Page<User> users = userService.findAll(pageable);
        Page<UserDto> userDtos = users.map(UserDto::fromEntity);
        
        return ResponseEntity.ok(userDtos);
    }
}
```

### DTOs for API

```java
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class UserDto {
    private Long id;
    private String username;
    private String email;
    private Long version;
    
    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
    private LocalDateTime createdAt;
    
    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
    private LocalDateTime updatedAt;
    
    public static UserDto fromEntity(User user) {
        return UserDto.builder()
            .id(user.getId())
            .username(user.getUsername())
            .email(user.getEmail())
            .version(user.getVersion())
            .createdAt(user.getCreatedAt())
            .updatedAt(user.getUpdatedAt())
            .build();
    }
}

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CreateUserRequest {
    
    @NotBlank(message = "Username is required")
    @Size(min = 3, max = 50, message = "Username must be between 3 and 50 characters")
    @Pattern(regexp = "^[a-zA-Z0-9_]+$", message = "Username can only contain letters, numbers, and underscores")
    private String username;
    
    @NotBlank(message = "Email is required")
    @Email(message = "Email should be valid")
    @Size(max = 100, message = "Email must not exceed 100 characters")
    private String email;
}

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UpdateUserRequest {
    
    @NotBlank(message = "Email is required")
    @Email(message = "Email should be valid")
    @Size(max = 100, message = "Email must not exceed 100 characters")
    private String email;
    
    @NotNull(message = "Version is required for optimistic locking")
    @Min(value = 0, message = "Version must be non-negative")
    private Long version;
}

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ErrorResponse {
    private String code;
    private String message;
    private LocalDateTime timestamp;
    private String path;
    
    public ErrorResponse(String code, String message) {
        this.code = code;
        this.message = message;
        this.timestamp = LocalDateTime.now();
    }
}
```

## Advanced Versioning Strategies

### 1. Timestamp-based Versioning

```java
@Entity
@Table(name = "documents")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EntityListeners(AuditingEntityListener.class)
public class Document {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    
    @Column(nullable = false)
    private String title;
    
    @Lob
    private String content;
    
    @Version
    @Column(name = "last_modified")
    private Timestamp lastModified;
    
    @CreatedDate
    @Column(updatable = false)
    private LocalDateTime createdAt;
    
    @CreatedBy
    @Column(updatable = false)
    private String createdBy;
}
```

### 2. Custom Version Strategy

```java
@Entity
@Table(name = "products")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EntityListeners(AuditingEntityListener.class)
public class Product {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    
    @Column(nullable = false)
    private String name;
    
    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal price;
    
    @Version
    private Long version;
    
    @CreatedDate
    @Column(updatable = false)
    private LocalDateTime createdAt;
    
    @LastModifiedDate
    private LocalDateTime updatedAt;
    
    // Custom method to handle version conflicts
    public void updatePrice(BigDecimal newPrice, Long expectedVersion) {
        if (!this.version.equals(expectedVersion)) {
            throw new OptimisticLockException("Product has been modified");
        }
        this.price = newPrice;
    }
}
```

### 3. Handling Version Conflicts with Retry Logic

```java
@Service
@Transactional
@RequiredArgsConstructor
@Slf4j
public class ProductService {
    
    private final ProductRepository productRepository;
    
    @Retryable(value = OptimisticLockException.class, maxAttempts = 3, backoff = @Backoff(delay = 100))
    public Product updateProductPrice(Long productId, BigDecimal newPrice) {
        Product product = productRepository.findById(productId)
            .orElseThrow(() -> new EntityNotFoundException("Product not found"));
        
        log.info("Updating product {} price from {} to {}", productId, product.getPrice(), newPrice);
        product.setPrice(newPrice);
        return productRepository.save(product);
    }
    
    @Recover
    public Product recover(OptimisticLockException ex, Long productId, BigDecimal newPrice) {
        log.error("Failed to update product {} price after all retry attempts: {}", productId, ex.getMessage());
        throw new BusinessException("Unable to update product after multiple attempts");
    }
    
    public Product createProduct(String name, BigDecimal price) {
        Product product = Product.builder()
            .name(name)
            .price(price)
            .build();
        
        log.info("Creating new product: {}", name);
        return productRepository.save(product);
    }
}
```

## Configuration

### Maven Dependencies

```xml
<dependencies>
    <!-- Spring Boot Starters -->
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-web</artifactId>
    </dependency>
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-data-jpa</artifactId>
    </dependency>
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-validation</artifactId>
    </dependency>
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-actuator</artifactId>
    </dependency>
    
    <!-- Database -->
    <dependency>
        <groupId>org.postgresql</groupId>
        <artifactId>postgresql</artifactId>
        <scope>runtime</scope>
    </dependency>
    
    <!-- Lombok -->
    <dependency>
        <groupId>org.projectlombok</groupId>
        <artifactId>lombok</artifactId>
        <optional>true</optional>
    </dependency>
    
    <!-- Retry Support -->
    <dependency>
        <groupId>org.springframework.retry</groupId>
        <artifactId>spring-retry</artifactId>
    </dependency>
    <dependency>
        <groupId>org.springframework</groupId>
        <artifactId>spring-aspects</artifactId>
    </dependency>
    
    <!-- Test Dependencies -->
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-test</artifactId>
        <scope>test</scope>
    </dependency>
    <dependency>
        <groupId>com.h2database</groupId>
        <artifactId>h2</artifactId>
        <scope>test</scope>
    </dependency>
</dependencies>
```

### Gradle Dependencies

```gradle
dependencies {
    // Spring Boot Starters
    implementation 'org.springframework.boot:spring-boot-starter-web'
    implementation 'org.springframework.boot:spring-boot-starter-data-jpa'
    implementation 'org.springframework.boot:spring-boot-starter-validation'
    implementation 'org.springframework.boot:spring-boot-starter-actuator'
    
    // Database
    runtimeOnly 'org.postgresql:postgresql'
    
    // Lombok
    compileOnly 'org.projectlombok:lombok'
    annotationProcessor 'org.projectlombok:lombok'
    
    // Retry Support
    implementation 'org.springframework.retry:spring-retry'
    implementation 'org.springframework:spring-aspects'
    
    // Test Dependencies
    testImplementation 'org.springframework.boot:spring-boot-starter-test'
    testRuntimeOnly 'com.h2database:h2'
}
```

### Application Properties

```properties
# Server Configuration
server.port=8080
server.servlet.context-path=/api

# Database Configuration
spring.datasource.url=jdbc:postgresql://localhost:5432/jpa_versioning_demo
spring.datasource.username=${DB_USERNAME:demo_user}
spring.datasource.password=${DB_PASSWORD:demo_password}
spring.datasource.driver-class-name=org.postgresql.Driver

# JPA/Hibernate Configuration
spring.jpa.hibernate.ddl-auto=validate
spring.jpa.show-sql=${SHOW_SQL:false}
spring.jpa.properties.hibernate.format_sql=true
spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.PostgreSQLDialect
spring.jpa.properties.hibernate.jdbc.time_zone=UTC

# Enable optimistic locking and batch processing
spring.jpa.properties.hibernate.jdbc.batch_size=25
spring.jpa.properties.hibernate.jdbc.batch_versioned_data=true
spring.jpa.properties.hibernate.order_inserts=true
spring.jpa.properties.hibernate.order_updates=true

# Connection Pool Settings (HikariCP)
spring.datasource.hikari.maximum-pool-size=20
spring.datasource.hikari.minimum-idle=5
spring.datasource.hikari.connection-timeout=20000
spring.datasource.hikari.idle-timeout=300000
spring.datasource.hikari.max-lifetime=1200000
spring.datasource.hikari.leak-detection-threshold=60000

# Jackson Configuration
spring.jackson.serialization.write-dates-as-timestamps=false
spring.jackson.serialization.fail-on-empty-beans=false
spring.jackson.deserialization.fail-on-unknown-properties=false
spring.jackson.default-property-inclusion=non_null

# Logging Configuration
logging.level.org.hibernate.SQL=${HIBERNATE_SQL_LOG_LEVEL:WARN}
logging.level.org.hibernate.type.descriptor.sql.BasicBinder=${HIBERNATE_BIND_LOG_LEVEL:WARN}
logging.level.com.yourcompany.demo=${APP_LOG_LEVEL:INFO}
logging.pattern.console=%d{yyyy-MM-dd HH:mm:ss} - %msg%n

# Actuator Configuration
management.endpoints.web.exposure.include=health,info,metrics
management.endpoint.health.show-details=when_authorized

# Validation Configuration
spring.mvc.throw-exception-if-no-handler-found=true
spring.web.resources.add-mappings=false

# Retry Configuration
spring.retry.enabled=true

# Transaction Configuration
spring.transaction.default-timeout=30
spring.jpa.properties.hibernate.connection.provider_disables_autocommit=true
```

### Configuration Classes

```java
@Configuration
@EnableJpaRepositories(basePackages = "com.yourcompany.demo.repository")
@EnableJpaAuditing(auditorAwareRef = "auditorProvider")
@EnableRetry
@RequiredArgsConstructor
@Slf4j
public class JpaConfig {
    
    @Bean
    public AuditorAware<String> auditorProvider() {
        return () -> {
            // In a real application, you would get this from SecurityContext
            // return Optional.ofNullable(SecurityContextHolder.getContext())
            //     .map(SecurityContext::getAuthentication)
            //     .map(Authentication::getName);
            
            // For demo purposes, return system user
            return Optional.of("system");
        };
    }
    
    @Bean
    @Primary
    public ObjectMapper objectMapper() {
        return Jackson2ObjectMapperBuilder.json()
            .dateFormat(new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss"))
            .timeZone(TimeZone.getTimeZone("UTC"))
            .propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
            .build();
    }
}

@Configuration
@EnableWebMvc
@RequiredArgsConstructor
public class WebConfig implements WebMvcConfigurer {
    
    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**")
            .allowedOrigins("http://localhost:3000", "http://localhost:8080")
            .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
            .allowedHeaders("*")
            .allowCredentials(true);
    }
    
    @Override
    public void configureMessageConverters(List<HttpMessageConverter<?>> converters) {
        converters.add(new MappingJackson2HttpMessageConverter());
    }
}

@ControllerAdvice
@Slf4j
public class GlobalExceptionHandler {
    
    @ExceptionHandler(OptimisticLockException.class)
    public ResponseEntity<ErrorResponse> handleOptimisticLockException(
            OptimisticLockException ex, HttpServletRequest request) {
        
        log.warn("Optimistic lock exception: {}", ex.getMessage());
        
        ErrorResponse error = ErrorResponse.builder()
            .code("CONFLICT")
            .message("The resource has been modified by another user. Please refresh and try again.")
            .timestamp(LocalDateTime.now())
            .path(request.getRequestURI())
            .build();
            
        return ResponseEntity.status(HttpStatus.CONFLICT).body(error);
    }
    
    @ExceptionHandler(EntityNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleEntityNotFoundException(
            EntityNotFoundException ex, HttpServletRequest request) {
        
        log.warn("Entity not found: {}", ex.getMessage());
        
        ErrorResponse error = ErrorResponse.builder()
            .code("NOT_FOUND")
            .message(ex.getMessage())
            .timestamp(LocalDateTime.now())
            .path(request.getRequestURI())
            .build();
            
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(error);
    }
    
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidationException(
            MethodArgumentNotValidException ex, HttpServletRequest request) {
        
        log.warn("Validation error: {}", ex.getMessage());
        
        String message = ex.getBindingResult().getFieldErrors().stream()
            .map(error -> error.getField() + ": " + error.getDefaultMessage())
            .collect(Collectors.joining(", "));
            
        ErrorResponse error = ErrorResponse.builder()
            .code("VALIDATION_ERROR")
            .message(message)
            .timestamp(LocalDateTime.now())
            .path(request.getRequestURI())
            .build();
            
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(error);
    }
}
```

## Best Practices

### 1. Always Include Version in DTOs
Always expose the version field in your API responses so clients can include it in update requests.

### 2. Handle OptimisticLockException Gracefully
This is now handled in the `GlobalExceptionHandler` class shown above, which provides consistent error handling across the application.

### 3. Use Retry Mechanisms
Implement retry logic for operations that might fail due to version conflicts.

### 4. Database Schema Considerations
```sql
-- Ensure version column has appropriate constraints
ALTER TABLE users ADD COLUMN version BIGINT NOT NULL DEFAULT 0;
CREATE INDEX idx_users_version ON users(version);
```

### 5. Testing Version Conflicts
```java
@Test
public void testOptimisticLocking() {
    // Create user
    User user = userService.createUser("john", "john@example.com");
    Long userId = user.getId();
    Long version = user.getVersion();
    
    // Simulate concurrent updates
    User user1 = userRepository.findById(userId).get();
    User user2 = userRepository.findById(userId).get();
    
    // First update succeeds
    user1.setEmail("john1@example.com");
    userRepository.save(user1);
    
    // Second update should fail
    user2.setEmail("john2@example.com");
    assertThrows(OptimisticLockException.class, () -> {
        userRepository.save(user2);
    });
}
```

## Common Pitfalls and Solutions

### 1. Forgetting to Include Version in Updates
**Problem**: Not passing the version field in update operations.
**Solution**: Always include version in your update DTOs and validate it.

### 2. Not Handling Version Conflicts in UI
**Problem**: Users get cryptic error messages.
**Solution**: Implement proper error handling and user-friendly messages.

### 3. Performance Issues with High Contention
**Problem**: Too many version conflicts in high-concurrency scenarios.
**Solution**: Consider pessimistic locking or redesign the data model.

### 4. Version Field Not Being Updated
**Problem**: Version field remains unchanged after updates.
**Solution**: Ensure the entity is properly managed by the persistence context.

## Conclusion

JPA versioning is essential for maintaining data integrity in concurrent environments. The `@Version` annotation provides a simple yet powerful way to implement optimistic locking. Key considerations include:

- Always expose version fields in your APIs
- Handle `OptimisticLockException` gracefully
- Implement retry mechanisms where appropriate
- Test concurrent scenarios thoroughly
- Consider the user experience when conflicts occur

This approach ensures your Spring Boot application can handle concurrent modifications safely while maintaining good performance characteristics.