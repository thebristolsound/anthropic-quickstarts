# JPA Versioning API Usage Examples

This document shows how to use the User API with automatic JPA versioning (no manual version checks needed).

## Key Points

- **No Manual Version Checking**: The service automatically handles version validation
- **Hibernate Does the Work**: When you set `user.setVersion(clientVersion)` and call `save()`, Hibernate automatically compares with database version
- **Automatic Exception**: `OptimisticLockException` is thrown automatically if versions don't match
- **Version Increment**: Hibernate automatically increments the version on successful updates

## API Examples

### 1. Create a User

```bash
POST /api/users
Content-Type: application/json

{
  "username": "johndoe",
  "email": "john@example.com"
}
```

**Response:**
```json
{
  "id": 1,
  "username": "johndoe", 
  "email": "john@example.com",
  "version": 0,
  "createdAt": "2024-01-15T10:30:00",
  "updatedAt": "2024-01-15T10:30:00"
}
```

### 2. Get User (to see current version)

```bash
GET /api/users/1
```

**Response:**
```json
{
  "id": 1,
  "username": "johndoe",
  "email": "john@example.com", 
  "version": 0,
  "createdAt": "2024-01-15T10:30:00",
  "updatedAt": "2024-01-15T10:30:00"
}
```

### 3. Update User (Successful - Correct Version)

```bash
PUT /api/users/1
Content-Type: application/json

{
  "email": "john.doe@example.com",
  "version": 0
}
```

**Response:**
```json
{
  "id": 1,
  "username": "johndoe",
  "email": "john.doe@example.com",
  "version": 1,
  "createdAt": "2024-01-15T10:30:00", 
  "updatedAt": "2024-01-15T10:35:00"
}
```

### 4. Update User (Conflict - Wrong Version)

```bash
PUT /api/users/1
Content-Type: application/json

{
  "email": "different@example.com",
  "version": 0
}
```

**Response:**
```
HTTP 409 Conflict
```

The user was already updated to version 1, so version 0 is stale.

### 5. Update with Retry Mechanism

```bash
PUT /api/users/1/retry?email=retry@example.com
```

This endpoint automatically retries on version conflicts by fetching the latest version.

## How It Works Under the Hood

### In UserService.updateUser():

```java
public User updateUser(Long id, String newEmail, Long version) {
    // 1. Fetch user from database
    User user = userRepository.findById(id)
        .orElseThrow(() -> new EntityNotFoundException("User not found"));
    
    // 2. Set the version from client request - THIS IS KEY!
    user.setVersion(version);
    user.setEmail(newEmail);
    
    // 3. Save - Hibernate automatically:
    //    - Compares user.version (0) with database version (1)  
    //    - Throws OptimisticLockException because 0 != 1
    //    - OR increments version and updates if they match
    return userRepository.save(user);
}
```

### What Hibernate Does:

1. **Version Check**: Compares entity version with database version
2. **SQL Generation**: 
   - Success: `UPDATE users SET email=?, version=version+1 WHERE id=? AND version=?`
   - Failure: If WHERE clause matches 0 rows → `OptimisticLockException`
3. **Automatic Increment**: Version is automatically incremented on successful update

## Testing Concurrent Updates

You can test this with two browser tabs or API clients:

1. **Tab 1**: GET `/api/users/1` → version: 0
2. **Tab 2**: GET `/api/users/1` → version: 0  
3. **Tab 1**: PUT with version 0 → SUCCESS, user now version 1
4. **Tab 2**: PUT with version 0 → CONFLICT (409), because user is now version 1

## Benefits of This Approach

1. **No Manual Checks**: No `if (version != user.getVersion())` code needed
2. **Automatic**: Hibernate handles everything
3. **Standard JPA**: Uses standard `@Version` annotation
4. **Clean Code**: Service layer is much cleaner
5. **Reliable**: Can't forget to check versions - it's automatic

## Error Handling

The controller catches `OptimisticLockException` and returns HTTP 409:

```java
@PutMapping("/{id}")
public ResponseEntity<UserDto> updateUser(@PathVariable Long id, 
                                        @Valid @RequestBody UpdateUserRequest request) {
    try {
        User user = userService.updateUser(id, request.getEmail(), request.getVersion());
        return ResponseEntity.ok(UserDto.fromEntity(user));
    } catch (OptimisticLockException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).build();
    }
}
```

Clients should handle 409 responses by:
1. Refreshing the entity to get latest version
2. Re-applying their changes  
3. Retrying the update