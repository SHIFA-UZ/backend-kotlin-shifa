# Backend Location Storage - Summary

## ✅ What's Stored

The backend now stores **all necessary location information** in structured fields:

### Tables

#### 1. `doctor_profiles` table
- **Coordinates** (for distance calculations):
  - `latitude` (DOUBLE PRECISION)
  - `longitude` (DOUBLE PRECISION)

- **Structured Location Fields** (for search & filtering):
  - `location_country` (VARCHAR(100)) - e.g., "Uzbekistan"
  - `location_region` (VARCHAR(100)) - e.g., "Toshkent Viloyati" (Viloyat)
  - `location_district` (VARCHAR(100)) - e.g., "Mirzo Ulug'bek" (Tuman)
  - `location_city` (VARCHAR(100)) - e.g., "Tashkent"
  - `location_postal_code` (VARCHAR(20)) - Postal code if available
  - `location_street_address` (VARCHAR(255)) - Editable street address

- **Legacy Fields** (still used):
  - `address` (VARCHAR) - Used for clinic/workplace name
  - `clinic` (VARCHAR) - Clinic name

#### 2. `patient_profiles` table
- Same structure as `doctor_profiles`:
  - `latitude`, `longitude`
  - `location_country`, `location_region`, `location_district`, `location_city`, `location_postal_code`, `location_street_address`
  - `address` (VARCHAR) - General address field

## 📊 Database Indexes

Indexes created for efficient location-based queries:
- `idx_doctor_profiles_location_country`
- `idx_doctor_profiles_location_region`
- `idx_doctor_profiles_location_district`
- `idx_doctor_profiles_location_city`
- `idx_patient_profiles_location_country`
- `idx_patient_profiles_location_region`
- `idx_patient_profiles_location_district`
- `idx_patient_profiles_location_city`

## 🔄 API Endpoints Updated

### Doctor Endpoints (`/api/doctors/me`)
- **GET `/api/doctors/me`** - Returns all profile data including structured location
- **PATCH `/api/doctors/me/profile`** - Accepts and saves structured location fields

### Patient Endpoints (`/api/patients/me`)
- **GET `/api/patients/me/profile`** - Returns all profile data including structured location
- **PATCH `/api/patients/me/profile`** - Accepts and saves structured location fields

## 📝 DTOs Updated

### `DoctorProfileDto`
```kotlin
data class DoctorProfileDto(
    // ... existing fields ...
    val latitude: Double?,
    val longitude: Double?,
    val locationCountry: String?,
    val locationRegion: String?,
    val locationDistrict: String?,
    val locationCity: String?,
    val locationPostalCode: String?,
    val locationStreetAddress: String?
)
```

### `PatientProfileDto`
```kotlin
data class PatientProfileDto(
    // ... existing fields ...
    val latitude: Double?,
    val longitude: Double?,
    val locationCountry: String?,
    val locationRegion: String?,
    val locationDistrict: String?,
    val locationCity: String?,
    val locationPostalCode: String?,
    val locationStreetAddress: String?
)
```

## 🚀 Migration

Migration file: `V29__add_structured_location_fields.sql`

This migration:
1. Adds 6 new columns to both `doctor_profiles` and `patient_profiles` tables
2. Creates indexes for efficient filtering by country, region, district, and city
3. Does NOT drop existing `latitude` and `longitude` columns (they're still needed for distance calculations)

## 💡 Future Use Cases Enabled

With this structured data, you can now:

1. **Search doctors by location**:
   - "Show all doctors in Tashkent"
   - "Show doctors in Mirzo Ulug'bek district"
   - "Show doctors in Toshkent Viloyati region"

2. **Filter by multiple criteria**:
   - Profession + Region + District
   - City + Postal Code

3. **Analytics**:
   - Doctor distribution by region
   - Patient distribution by district
   - Insurance coverage by location

4. **Regulatory compliance**:
   - Different regulations per region
   - License validation by location

## 🔍 Example Query (Future Implementation)

```sql
-- Find doctors in a specific district
SELECT * FROM doctor_profiles 
WHERE location_district = 'Mirzo Ulug''bek' 
  AND location_city = 'Tashkent'
  AND profession = 'Cardiologist';
```

## 📋 Next Steps

1. ✅ Migration created
2. ✅ Entities updated
3. ✅ DTOs updated
4. ✅ Controllers updated
5. ⏳ Update frontend to send structured location data
6. ⏳ Update `PublicDoctorController` to support filtering by location fields
7. ⏳ Add search/filter endpoints for location-based queries
