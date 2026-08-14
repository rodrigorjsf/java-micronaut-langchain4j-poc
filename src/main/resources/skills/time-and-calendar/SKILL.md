---
name: time-and-calendar
description: Public holidays and the current time anywhere — holidays for any country and year, the next ones coming up, long weekends, and the local time in any timezone. Use for questions about holidays, days off, bridges, or what time it is somewhere.
---

# Time and calendar

Three holiday tools and a clock, covering any country rather than only Brazil.

## Choosing a tool

| The user asks | Use |
|---|---|
| "quais os feriados de 2026?" — a whole year | `get_holidays` |
| "qual o próximo feriado?" — what is coming up | `get_next_holidays` |
| "quando cai um feriadão?" — bridges and long weekends | `get_long_weekends` |
| "que horas são em Tóquio?" — the current local time | `get_current_time` |

Country codes are two-letter ISO: `BR`, `PT`, `US`, `JP`. Timezones are IANA
names: `America/Sao_Paulo`, `Europe/Lisbon`, `Asia/Tokyo` — not offsets and not
abbreviations.

## Reading a holiday result

- `global: true` means the holiday applies nationwide; `false` means it applies
  only to the states or regions listed on the entry. **Say which**, because "é
  feriado no Brasil" and "é feriado em São Paulo" are different answers.
- `fixed: false` means the date moves between years — Carnaval and Corpus Christi
  do. Never carry a date from one year to another.
- The names come back in the country's own language plus English. Use the local
  name when answering in that country's language.

## Two things worth passing on

This source covers **national and regional** holidays as its data provides them,
but municipal holidays are patchy and frequently absent — in many Brazilian
cities those are most of the days off. If the user is asking about a specific
city, say the municipal ones are not covered.

`get_current_time` is a clock, not a converter. For "what time is it there when
it is 3pm here", get both times and do the arithmetic in the answer.

## When a tool fails

- **No result for a country** — the code was probably wrong, or it is a country
  the source does not cover. Confirm the ISO code rather than trying variations.
- **No result for a timezone** — the name was not an IANA identifier. Ask for the
  city; do not guess an offset.
