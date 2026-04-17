# Memory Leak Fixes - Critical Code Issues

## Problem Identified

The OutOfMemoryError was caused by **memory leaks in the code**, not just heap size. Multiple endpoints were loading ALL records from the database into memory and filtering in application code.

## Critical Issues Fixed

### 1. MessageService.searchUsers() - CRITICAL FIX
**Before:**
```kotlin
val doctors = userRepo.findAll().filter { ... }  // Loads ALL users
val patients = patientProfileRepo.findAll().filter { ... }  // Loads ALL patients
```

**After:**
```kotlin
val doctorUsers = userRepo.searchByRoleAndQuery(doctorUserId, Role.DOCTOR, query)  // Database query
val patients = patientProfileRepo.searchByQuery(query)  // Database query
```

**Impact:** If you have 10,000 users, this was loading all 10,000 into memory. Now it only loads matching records.

### 2. MessageService.searchDoctorsForPatient() - CRITICAL FIX
**Before:**
```kotlin
val doctors = doctorProfileRepo.findAll().filter { ... }  // Loads ALL doctors
```

**After:**
```kotlin
val doctors = doctorProfileRepo.searchByQuery(query)  // Database query
```

**Impact:** Same issue - was loading all doctors into memory.

### 3. PublicDoctorController.listDoctors() - PARTIAL FIX
**Before:**
```kotlin
var doctors = doctorProfiles.findAll()  // Loads ALL doctors
// Then filters in memory
```

**After:**
```kotlin
var doctors = if (!search.isNullOrBlank() || !profession.isNullOrBlank()) {
    doctorProfiles.searchWithFilters(search, profession)  // Database query
} else {
    doctorProfiles.findAll()  // Still loads all if no filters
}
```

**Impact:** When search/profession filters are provided, uses database query. Still loads all if no filters (needed for distance calculations).

### 4. PatientsController.getAllPatients() - PAGINATION ADDED
**Before:**
```kotlin
val patients = patientsRepo.findAll()  // Loads ALL patients
return patients.map { toDto(it) }  // Also loads ALL documents for each patient
```

**After:**
```kotlin
val patients = patientsRepo.findAll(pageable).content  // Paginated (50 per page)
return patients.map { toDto(it) }
```

**Impact:** Now loads max 50 patients per request instead of all patients.

## New Repository Queries Added

### UserRepository
- `searchByRoleAndQuery()` - Searches users by role and query string at database level

### PatientProfileRepository  
- `searchByQuery()` - Searches patients by name/email/phone at database level

### DoctorProfileRepository
- `searchByQuery()` - Searches doctors by name/email/phone/profession/clinic at database level
- `searchWithFilters()` - Searches doctors with search and profession filters at database level

## Memory Impact

### Before Fixes
- **MessageService.searchUsers()**: Could load 10,000+ users + 10,000+ patients = 20,000+ records
- **MessageService.searchDoctorsForPatient()**: Could load 1,000+ doctors
- **PublicDoctorController.listDoctors()**: Could load 1,000+ doctors
- **PatientsController.getAllPatients()**: Could load 10,000+ patients + all their documents

**Total potential memory**: 30,000+ records × ~1KB each = 30MB+ just for these queries

### After Fixes
- **MessageService.searchUsers()**: Only loads matching records (typically 0-50)
- **MessageService.searchDoctorsForPatient()**: Only loads matching records (typically 0-50)
- **PublicDoctorController.listDoctors()**: Uses database query when filters provided
- **PatientsController.getAllPatients()**: Max 50 patients per request

**Total potential memory**: ~200 records × ~1KB = ~200KB

## Remaining Optimizations

### PatientsController.toDto()
- Currently loads ALL documents for each patient: `Hibernate.initialize(p.documents)`
- **Recommendation**: Consider lazy-loading documents or limiting document count
- **Impact**: If a patient has 100 documents, loading 50 patients = 5,000 document records

### PublicDoctorController.listDoctors()
- Still loads all doctors when no search/profession filter provided
- **Recommendation**: Add default limit (e.g., top 100 by rating) or require search parameter
- **Impact**: If you have 1,000 doctors, still loads all when no filters

## Files Changed

1. ✅ `UserRepository.kt` - Added `searchByRoleAndQuery()`
2. ✅ `PatientProfileRepository.kt` - Added `searchByQuery()`
3. ✅ `DoctorProfileRepository.kt` - Added `searchByQuery()` and `searchWithFilters()`
4. ✅ `MessageService.kt` - Fixed `searchUsers()` and `searchDoctorsForPatient()`
5. ✅ `PublicDoctorController.kt` - Fixed `listDoctors()` to use database queries
6. ✅ `PatientsController.kt` - Added pagination to `getAllPatients()`

## Testing

After deployment, test:
1. User search in messages - should be fast and not cause memory issues
2. Doctor listing with search - should be fast
3. Patient listing - should return paginated results
4. Monitor Railway memory usage - should be stable

## Expected Results

- ✅ No more OutOfMemoryError
- ✅ Faster response times (database filtering is faster)
- ✅ Lower memory usage (only loads needed records)
- ✅ Better scalability (works with large databases)
