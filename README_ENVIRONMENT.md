# Environment Configuration Guide

## Overview

This backend supports multiple environments through Spring Boot profiles and environment variables.

## Profiles

### Development (Default)
- **File**: `application.yml`
- **Usage**: Local development
- **Database**: Local PostgreSQL
- **Logging**: Verbose (DEBUG level)

### Production
- **File**: `application-prod.yml`
- **Usage**: Production deployment
- **Database**: Managed PostgreSQL (via env vars)
- **Logging**: Minimal (INFO/WARN level)
- **Security**: All secrets from environment variables

## Setting Up Environment Variables

### Option 1: System Environment Variables
```bash
export DATABASE_URL=jdbc:postgresql://localhost:5432/shifa
export DB_USERNAME=shifa
export DB_PASSWORD=your_password
export JWT_SECRET=your_secret_key
# ... etc
```

### Option 2: .env File (Development)
```bash
# Copy example file
cp .env.example .env

# Edit .env with your values
nano .env

# Load in shell (if using a tool like direnv)
source .env
```

### Option 3: Docker Environment
```yaml
# docker-compose.yml
services:
  backend:
    environment:
      - DATABASE_URL=${DATABASE_URL}
      - JWT_SECRET=${JWT_SECRET}
      # ... etc
```

## Running with Different Profiles

### Development (Default)
```bash
./gradlew bootRun
# Uses application.yml
```

### Production
```bash
# Set environment variables first
export SPRING_PROFILES_ACTIVE=prod
export DATABASE_URL=...
export JWT_SECRET=...

# Run
./gradlew bootRun
# Or with JAR:
java -jar build/libs/*.jar --spring.profiles.active=prod
```

## Generating Secure Secrets

### JWT Secret (64+ characters)
```bash
openssl rand -base64 64
```

### Database Password
```bash
openssl rand -base64 32
```

## Security Checklist

- [ ] All secrets are in environment variables (not in code)
- [ ] `.env` file is in `.gitignore`
- [ ] JWT secret is at least 64 characters
- [ ] Database password is strong (16+ characters)
- [ ] Production uses `ddl-auto: validate` (not `update`)
- [ ] Production logging is set to INFO/WARN (not DEBUG)

## Troubleshooting

### "Could not resolve placeholder"
- Check that environment variable is set
- Verify variable name matches exactly (case-sensitive)
- Check for typos in variable names

### "Connection refused" to database
- Verify DATABASE_URL is correct
- Check database is running and accessible
- Verify DB_USERNAME and DB_PASSWORD are correct

### "JWT validation failed"
- Verify JWT_SECRET matches between backend instances
- Check JWT_SECRET is not empty
- Ensure secret is long enough (64+ characters)
