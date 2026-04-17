# Admin Panel Setup Guide

## Initial Admin User

The seed migration `V15__seed_admin_user.sql` creates the first admin user:

- **Email**: `admin@shifa.local`
- **Password**: `Admin123!`
- **Role**: SUPER_ADMIN

⚠️ **IMPORTANT**: Change the default password immediately after first login!

### Generating a New Password Hash

If you need to change the default password hash in the migration, you can generate a BCrypt hash using:

1. **Kotlin/Java**:
   ```kotlin
   val encoder = BCryptPasswordEncoder()
   val hash = encoder.encode("YourPassword123!")
   println(hash)
   ```

2. **Online Tool**: Use a BCrypt hash generator (ensure strength 10)

3. **Spring Boot Test**:
   Create a simple test or use the Spring Boot shell to generate the hash.

## Admin Panel Access

1. Navigate to `/admin/login` in the Flutter app
2. Login with admin credentials
3. You'll be redirected to the admin dashboard

## Features

### Dashboard
- View system statistics (doctors, patients, users, tokens)
- Quick actions

### Token Management
- Generate invitation tokens for doctor onboarding
- Set expiration dates
- Revoke/regenerate tokens
- View token status (active, consumed, expired)

### User Management
- View all users (doctors, patients, admins)
- Filter by role and status
- Activate/deactivate users
- Reset passwords
- Force logout users
- Unlock locked accounts

### Audit Logs
- View all admin actions
- Filter by admin, entity type, action type
- Track all changes made by administrators

### System Configuration
- Manage system settings
- Update password policies
- Configure token expiration
- Toggle maintenance mode (requires SUPER_ADMIN)

## Security Notes

- All admin actions are logged in the audit log
- Role-based access control:
  - **READ_ONLY**: Can view but not modify
  - **ADMIN**: Standard admin permissions
  - **SUPER_ADMIN**: Full access including system config
- Failed login attempts are tracked
- Account lockout after 5 failed attempts (configurable)
- Force logout capability for security incidents

## API Endpoints

All admin endpoints are under `/api/admin/**` and require ADMIN role:

- `GET /api/admin/dashboard/stats` - Dashboard statistics
- `POST /api/admin/tokens/generate` - Generate token
- `GET /api/admin/tokens` - List tokens
- `POST /api/admin/tokens/{id}/revoke` - Revoke token
- `GET /api/admin/users` - List users
- `POST /api/admin/users/{id}/enable` - Enable/disable user
- `POST /api/admin/users/{id}/reset-password` - Reset password
- `POST /api/admin/users/{id}/force-logout` - Force logout
- `GET /api/admin/audit-logs` - View audit logs
- `GET /api/admin/config` - Get system config
- `PUT /api/admin/config/{key}` - Update config (SUPER_ADMIN only)
