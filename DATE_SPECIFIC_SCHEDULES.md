# Date-Specific Schedules Implementation

## Overview

This feature allows doctors to define schedules for specific date ranges and expand existing schedules without overriding them. For example, if a doctor has a weekly schedule 8AM-5PM, they can add a date-specific rule for today that extends it to 8AM-11PM, but they cannot override the existing 8AM-5PM.

## Key Features

### 1. Date Range Scheduling
- Define schedules for specific date ranges (e.g., Jan 27 - Jan 31, 2026)
- Each rule applies to all dates within the range
- Date-specific rules override weekly rules for those specific dates

### 2. Expansion-Only Policy
- **Cannot override existing schedules**: If a schedule exists for 8AM-5PM, you cannot create a new rule that starts before 5PM
- **Can only expand after**: New rules must start exactly where existing schedules end
- **Example**: 
  - Existing: 8AM-5PM
  - ✅ Allowed: 5PM-11PM (starts exactly at 5PM)
  - ❌ Not allowed: 4PM-11PM (overlaps with existing)
  - ❌ Not allowed: 9AM-11PM (overlaps with existing)

### 3. Validation
- Prevents overlapping rules for the same date
- Validates time ranges (start < end)
- Enforces minimum slot size (5 minutes)
- Limits maximum slots per day (200)

## API Endpoints

### GET /api/schedule/date-specific
Get all date-specific schedule rules for the authenticated doctor.

**Response**:
```json
[
  {
    "id": 1,
    "startDate": "2026-01-27",
    "endDate": "2026-01-31",
    "startTime": "17:00",
    "endTime": "23:00",
    "slotMinutes": 30
  }
]
```

### POST /api/schedule/date-specific
Create a new date-specific schedule rule.

**Request**:
```json
{
  "startDate": "2026-01-27",
  "endDate": "2026-01-31",
  "startTime": "17:00",
  "endTime": "23:00",
  "slotMinutes": 30
}
```

**Validation**:
- Checks for conflicts with existing weekly rules
- Checks for conflicts with existing date-specific rules
- Ensures new rule only expands (starts after existing end time)

**Error Example**:
```json
{
  "error": "Cannot override existing weekly schedule for 2026-01-27 (Monday). Existing schedule: 08:00-17:00. You can only expand the schedule after 17:00."
}
```

### PUT /api/schedule/date-specific/{id}
Update an existing date-specific schedule rule.

**Request**: Same as POST

**Validation**: Same as POST, but excludes the current rule from conflict checks

### DELETE /api/schedule/date-specific/{id}
Delete a date-specific schedule rule.

## How It Works

### Calendar Generation Priority

1. **Date-Specific Rules First**: If a date-specific rule exists for a date, it is used instead of weekly rules
2. **Weekly Rules Fallback**: If no date-specific rule exists, weekly rules are used
3. **No Mixing**: For a given date, either date-specific OR weekly rules are used, not both

### Expansion Example

**Scenario**: Doctor has weekly schedule Monday 8AM-5PM, wants to extend today (Monday, Jan 27) to 11PM.

**Step 1**: Check existing schedule
- Weekly rule: Monday 8AM-5PM
- Today is Monday, so existing schedule is 8AM-5PM

**Step 2**: Create date-specific rule
```json
POST /api/schedule/date-specific
{
  "startDate": "2026-01-27",
  "endDate": "2026-01-27",
  "startTime": "17:00",  // Must start exactly at 5PM (where weekly ends)
  "endTime": "23:00",
  "slotMinutes": 30
}
```

**Result**: 
- For Jan 27: Calendar shows 8AM-11PM (date-specific rule overrides weekly)
- For other Mondays: Calendar shows 8AM-5PM (weekly rule)

### Multiple Date Range Example

**Scenario**: Doctor wants to extend schedule for the entire week (Jan 27-31).

```json
POST /api/schedule/date-specific
{
  "startDate": "2026-01-27",
  "endDate": "2026-01-31",
  "startTime": "17:00",
  "endTime": "23:00",
  "slotMinutes": 30
}
```

**Result**: 
- Jan 27-31: Extended schedule (date-specific)
- Other dates: Regular weekly schedule

## Database Schema

### Table: `date_specific_schedule_rules`

```sql
CREATE TABLE date_specific_schedule_rules (
    id BIGSERIAL PRIMARY KEY,
    doctor_id BIGINT NOT NULL REFERENCES doctor_profiles(id) ON DELETE CASCADE,
    start_date DATE NOT NULL,
    end_date DATE NOT NULL,
    start_time TIME NOT NULL,
    end_time TIME NOT NULL,
    slot_minutes INTEGER NOT NULL CHECK (slot_minutes >= 5),
    CONSTRAINT check_date_range CHECK (start_date <= end_date),
    CONSTRAINT check_time_range CHECK (start_time < end_time)
);
```

**Indexes**:
- `idx_date_specific_rules_doctor_date`: For lookups by doctor and date
- `idx_date_specific_rules_date_range`: For date range overlap queries

## Files Changed

1. ✅ `DateSpecificScheduleRule.kt` - New entity for date-specific schedules
2. ✅ `DateSpecificScheduleRuleRepository.kt` - Repository with date range queries
3. ✅ `ScheduleController.kt` - Added endpoints for date-specific schedules
4. ✅ `CalendarController.kt` - Updated to use both weekly and date-specific rules
5. ✅ `V31__create_date_specific_schedule_rules.sql` - Database migration

## Usage Examples

### Example 1: Extend Today's Schedule

**Current**: Weekly schedule Monday 8AM-5PM
**Goal**: Extend today (Monday) to 11PM

```bash
POST /api/schedule/date-specific
{
  "startDate": "2026-01-27",
  "endDate": "2026-01-27",
  "startTime": "17:00",
  "endTime": "23:00",
  "slotMinutes": 30
}
```

### Example 2: Extend Next Week

**Current**: Weekly schedule Monday-Friday 9AM-5PM
**Goal**: Extend next week (Feb 3-7) to 8PM

```bash
POST /api/schedule/date-specific
{
  "startDate": "2026-02-03",
  "endDate": "2026-02-07",
  "startTime": "17:00",
  "endTime": "20:00",
  "slotMinutes": 30
}
```

### Example 3: Override Attempt (Will Fail)

**Current**: Weekly schedule Monday 8AM-5PM
**Attempt**: Create rule 4PM-11PM for today

```bash
POST /api/schedule/date-specific
{
  "startDate": "2026-01-27",
  "endDate": "2026-01-27",
  "startTime": "16:00",  // ❌ Starts before existing ends
  "endTime": "23:00",
  "slotMinutes": 30
}
```

**Error Response**:
```json
{
  "error": "Cannot override existing weekly schedule for 2026-01-27 (Monday). Existing schedule: 08:00-17:00. You can only expand the schedule after 17:00."
}
```

## Frontend Integration

### UI Requirements

1. **Date Range Picker**: Allow selecting start and end dates
2. **Time Picker**: Allow selecting start and end times
3. **Slot Duration**: Allow selecting slot minutes (minimum 5)
4. **Validation Feedback**: Show error messages when expansion rules are violated
5. **Existing Schedule Display**: Show existing schedules so users know where they can expand

### Suggested UI Flow

1. User selects a date or date range
2. System fetches existing schedules for those dates
3. UI shows existing schedule times (e.g., "8AM-5PM")
4. User can only select start time after existing end time
5. User selects end time and slot duration
6. System validates and creates rule

## Testing

### Test Cases

1. **Create date-specific rule for single date**
   - ✅ Should create successfully
   - ✅ Should override weekly rule for that date

2. **Create date-specific rule for date range**
   - ✅ Should create successfully
   - ✅ Should override weekly rules for all dates in range

3. **Try to override existing schedule**
   - ❌ Should fail with clear error message
   - ❌ Should not create rule

4. **Expand after existing schedule**
   - ✅ Should succeed if starts exactly at existing end time
   - ✅ Should fail if starts before existing end time

5. **Multiple date-specific rules for same date**
   - ❌ Should fail if they overlap
   - ✅ Should succeed if they don't overlap (e.g., 8AM-5PM and 5PM-11PM)

## Migration Notes

The migration `V31__create_date_specific_schedule_rules.sql` will:
1. Create the new table
2. Create indexes for efficient queries
3. Add constraints for data integrity

**No data migration needed** - this is a new feature, existing weekly schedules continue to work.

## Benefits

1. **Flexibility**: Doctors can adjust schedules for specific dates without changing weekly rules
2. **Safety**: Prevents accidental overrides of existing schedules
3. **Clarity**: Clear error messages guide users on how to expand schedules
4. **Performance**: Efficient queries with proper indexing
5. **Scalability**: Works with existing weekly schedule system
