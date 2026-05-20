UPDATE treatment_plans tp
SET estimated_total_minor = COALESCE(lt.total, 0),
    actual_total_minor = COALESCE(lt.total, 0),
    paid_amount_minor = COALESCE(pt.paid, 0),
    remaining_amount_minor = GREATEST(COALESCE(lt.total, 0) - COALESCE(pt.paid, 0), 0)
FROM (
    SELECT plan_id, SUM(GREATEST(unit_price_minor * quantity - discount_minor, 0)) AS total
    FROM treatment_plan_lines
    GROUP BY plan_id
) lt
LEFT JOIN (
    SELECT plan_id, SUM(amount_minor) AS paid
    FROM treatment_plan_payments
    GROUP BY plan_id
) pt ON lt.plan_id = pt.plan_id
WHERE tp.id = lt.plan_id;
