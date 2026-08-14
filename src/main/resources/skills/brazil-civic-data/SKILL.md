---
name: brazil-civic-data
description: Brazilian public registries — postal codes (CEP), telephone area codes (DDD), federal public holidays, and company records by CNPJ. Use for any question about a Brazilian address, phone area code, holiday date, or registered company.
---

# Brazilian civic data

Four tools over Brazilian public registries. Every one of them returns
authoritative data, so answer from the tool result and never from memory: postal
codes and municipal codes change, and a plausible-looking guess is worse than
saying you could not find it.

## Choosing a tool

| The user gives you | Use |
|---|---|
| an 8-digit postal code, or an address they want the code for | `lookup_cep` |
| a 2-digit phone area code, or a city whose area code they want | `lookup_ddd` |
| a year, or a date they want to know is a holiday | `list_national_holidays` |
| a 14-digit company number, or a Brazilian company they want details on | `lookup_company_by_cnpj` |

## How to combine them

- **Address to full context.** `lookup_cep` already returns the city, state, IBGE
  municipal code and coordinates in one call. Do not chain another lookup just to
  restate what you already have.
- **Coordinates for weather.** The `location` field of a CEP result carries
  latitude and longitude. Hand those to the `geo-and-weather` skill rather than
  geocoding the address again.
- **Holiday questions.** `list_national_holidays` covers federal holidays only.
  State and municipal holidays are not in it — say so rather than implying the
  list is complete.

## Reading a CEP result

`cep` is the code without punctuation, `street` and `neighborhood` may be empty
for codes that cover a whole municipality, and `ibge.city` is the 7-digit
municipal code used by every other Brazilian public dataset.

## When a tool fails

- **Not found** for a CEP usually means the code does not exist or was mistyped.
  Ask the user to confirm the eight digits; do not invent a nearby code.
- **Rate limited** — answer from what you already have and tell the user the
  registry is busy. Do not retry in the same turn.
