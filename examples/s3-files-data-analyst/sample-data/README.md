# Sample Data — Regional Sales

Mock data for demonstrating the Data Analyst Agent.

## Files

| File | Description |
|------|-------------|
| `sales-q1-2026.csv` | Q1 2026 regional sales data |
| `sales-q2-2026.csv` | Q2 2026 regional sales data |

## Schema

| Column | Type | Description |
|--------|------|-------------|
| Region | string | US geographic region |
| Q*_Revenue | integer | Total revenue in USD |
| Q*_Units | integer | Units sold |
| Q*_Customers | integer | Unique customers |
| Q*_Returns | integer | Product returns |

## Example Questions to Ask the Agent

- "What were the top 3 regions by revenue in Q2?"
- "Which regions grew the most between Q1 and Q2?"
- "Calculate the return rate by region and flag any above 1.5%"
- "Generate a bar chart comparing Q1 vs Q2 revenue by region"
- "Which region has the highest revenue per customer?"
