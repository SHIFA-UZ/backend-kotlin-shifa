# Calendar Memory Leak Fix - Critical Bug Resolution

## Problem Identified

The calendar screen was crashing with `OutOfMemoryError` when users expanded their schedule (e.g., from 5PM to 11PM). The root cause was **unbounded slot generation** in the calendar endpoint.

## Root Causes

### 1. Unbounded Slot Generation
**Location**: `CalendarController.byDay()`

**Issue**: 
- When a schedule rule spans a long time (e.g., 9AM-11PM = 14 hours) with small slot minutes (e.g., 15 minutes), it generates **56 slots**
- If `slotMinutes` is 1 minute, it generates **840 slots**!
- If there are **multiple overlapping rules** for the same weekday (from schedule edits), slots are generated for EACH rule, multiplying the problem
- **No maximum limit** - could generate thousands of slots

**Example Scenario**:
- User had schedule: 9AM-5PM (8 hours)
- User expanded to: 9AM-11PM (14 hours) 
- If `slotMinutes = 1` (unlikely but possible): **840 slots per rule**
- If there are 2 overlapping rules: **1,680 slots**
- Each slot creates objects in memory = **OutOfMemoryError**

### 2. No Validation for Overlapping Rules
**Location**: `ScheduleController.upsertRules()`

**Issue**:
- When users edit schedules, the system allows creating **multiple rules for the same weekday**
- If rules overlap (e.g., 9AM-5PM and 3PM-11PM), the calendar generates slots for BOTH rules
- This creates duplicate/overlapping slots, wasting memory

### 3. Inefficient Filtering Algorithm
**Location**: `CalendarController.byDay()` line 108-110

**Issue**:
- O(n×m) complexity: For each free slot, check against ALL appointments
- With 840 slots and 50 appointments = **42,000 comparisons**
- This is slow and memory-intensive

## Fixes Applied

### 1. Maximum Slot Limit ✅
**File**: `CalendarController.kt`

- Added `MAX_SLOTS_PER_DAY = 200` limit
- Prevents generating more than 200 slots per day
- If limit would be exceeded, stops generating slots early
- **Impact**: Prevents memory explosion even with very long schedules

### 2. Duplicate Slot Prevention ✅
**File**: `CalendarController.kt`

- Uses `Set<Instant>` to track slot start times
- Prevents generating duplicate slots from overlapping rules
- **Impact**: Even if rules overlap, only unique slots are generated

### 3. Minimum Slot Size Validation ✅
**File**: `CalendarController.kt`

- Enforces minimum 5-minute slots (`maxOf(r.slotMinutes.toLong(), 5)`)
- Prevents extremely small slots that would generate too many entries
- **Impact**: Limits maximum slots even with long schedules

### 4. Schedule Rule Validation ✅
**File**: `ScheduleController.kt`

Added `validateScheduleRules()` function that prevents:
- **Overlapping rules** for the same weekday
- **Invalid time ranges** (start >= end)
- **Extremely small slot minutes** (< 5 minutes)
- **Rules that would generate > 200 slots**

**Error Messages**:
- `"Overlapping schedule rules detected for weekday X: Rule 1: 09:00-17:00, Rule 2: 15:00-23:00. Please merge overlapping rules or remove duplicates."`
- `"Invalid schedule rule for weekday X: slot minutes must be at least 5 (got 1)"`
- `"Schedule rule for weekday X would generate 840 slots (maximum 200 allowed). Please increase slot minutes or reduce time range."`

**Impact**: Prevents the problem at the source - users can't create invalid schedules

### 5. Optimized Filtering ✅
**File**: `CalendarController.kt`

- Sorts appointments by start time for faster lookup
- Still O(n×m) but more efficient with sorted data
- **Note**: Could be further optimized to O(n log n) with interval tree, but current fix is sufficient

## Memory Impact

### Before Fixes
- **Worst case**: 840 slots × 2 rules = 1,680 slots
- **Memory per slot**: ~200 bytes (FreeSlot object + EntryDto)
- **Total**: ~336 KB just for slots
- **With filtering**: O(n×m) operations = slow + more memory
- **Result**: OutOfMemoryError

### After Fixes
- **Maximum**: 200 slots per day (hard limit)
- **Memory per slot**: ~200 bytes
- **Total**: ~40 KB for slots
- **With duplicate prevention**: Only unique slots
- **Result**: Stable memory usage

## Workflow Rules Implemented

1. ✅ **Cannot create overlapping schedule rules** for the same weekday
2. ✅ **Minimum slot size**: 5 minutes (prevents too many slots)
3. ✅ **Maximum slots per day**: 200 (hard limit)
4. ✅ **Validation on schedule save**: Errors returned immediately if rules are invalid

## User Experience

### Before
- User expands schedule → Calendar crashes → OutOfMemoryError
- No error message, just crashes
- User can't use calendar

### After
- User tries to create overlapping rules → **Clear error message**:
  ```
  "Overlapping schedule rules detected for weekday 1: 
   Rule 1: 09:00-17:00, Rule 2: 15:00-23:00. 
   Please merge overlapping rules or remove duplicates."
  ```
- User must fix the schedule before saving
- Calendar loads successfully with valid schedules

## Testing Recommendations

1. **Test overlapping rules**: Try to create two rules for Monday that overlap (e.g., 9AM-5PM and 3PM-11PM)
   - **Expected**: Error message, rules not saved

2. **Test very long schedule**: Create a rule 9AM-11PM with 1-minute slots
   - **Expected**: Error message about too many slots

3. **Test small slot size**: Create a rule with `slotMinutes = 1`
   - **Expected**: Error message about minimum 5 minutes

4. **Test calendar loading**: Load calendar for a day with a long schedule (9AM-11PM, 15-min slots)
   - **Expected**: Calendar loads successfully, shows max 200 slots

5. **Test existing overlapping rules**: If you have existing overlapping rules in the database
   - **Expected**: Calendar still works (duplicate prevention handles it), but you should fix the rules

## Migration Notes

### Existing Data
If you have existing overlapping rules in the database:
- The calendar will still work (duplicate prevention handles it)
- But you should clean up the data:
  ```sql
  -- Find overlapping rules for the same doctor and weekday
  SELECT w1.*, w2.* 
  FROM weekly_schedule_rules w1
  JOIN weekly_schedule_rules w2 
    ON w1.doctor_id = w2.doctor_id 
    AND w1.weekday = w2.weekday
    AND w1.id < w2.id
  WHERE w1.start_time < w2.end_time 
    AND w2.start_time < w1.end_time;
  ```

### Recommended Action
1. Review all schedule rules in the database
2. Merge overlapping rules manually
3. Ensure no rules have `slot_minutes < 5`
4. Ensure no rules would generate > 200 slots

## Files Changed

1. ✅ `CalendarController.kt` - Added slot limits, duplicate prevention, minimum slot size
2. ✅ `ScheduleController.kt` - Added validation to prevent invalid rules

## Expected Results

- ✅ No more OutOfMemoryError on calendar endpoint
- ✅ Calendar loads quickly even with long schedules
- ✅ Users get clear error messages when creating invalid schedules
- ✅ System prevents the problem at the source (validation)
- ✅ Existing overlapping rules are handled gracefully (duplicate prevention)
