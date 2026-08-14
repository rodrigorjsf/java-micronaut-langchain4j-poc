---
name: brazil-civic-data
description: Brazilian public registries and IBGE reference data — postal codes (CEP) and address search, phone area codes (DDD), federal holidays, companies by CNPJ, states and municipalities, name census statistics, .br domains and NCM codes. Use for any question about a Brazilian address, city, company or official record.
---

# Brazilian civic data

Thirteen tools over three registries: BrasilAPI, ViaCEP and IBGE. Every one of
them returns authoritative data, so answer from the tool result and never from
memory — postal codes and municipal codes change, and a plausible-looking guess
is worse than saying you could not find it.

## Choosing a tool

| The user gives you | Use |
|---|---|
| an 8-digit postal code | `lookup_cep` |
| a postal code that `lookup_cep` could not resolve, or a need for the DDD / IBGE code | `lookup_cep_fallback` |
| a street and a city, but no postal code | `search_cep_by_address` |
| a 2-digit phone area code, or a city whose area code they want | `lookup_ddd` |
| a year, or a date they want to know is a holiday | `list_national_holidays` |
| a 14-digit company number, or a Brazilian company they want details on | `lookup_company_by_cnpj` |
| a state name or acronym, when you need its numeric IBGE code | `list_brazil_states` |
| a state, when they want its cities | `list_municipalities_of_state` |
| a first name, and a question about how common or how dated it is | `census_name_stats` |
| a request for the most popular names, nationally or in one state | `census_name_ranking` |
| "what has IBGE just published" | `ibge_news` |
| a `.br` domain they want to know is taken | `check_br_domain` |
| a product they want the import/export tariff code for | `search_ncm` |

## How to combine them

- **Address to full context.** `lookup_cep` already returns the city, state and
  coordinates in one call. Do not chain another lookup just to restate what you
  already have.
- **The two CEP tools are not interchangeable.** `lookup_cep` is the primary and
  the only one that returns coordinates. `lookup_cep_fallback` is a different
  provider: reach for it when the first found nothing or was unavailable, or when
  you specifically need the `ddd` and `ibge` fields it adds. Calling both for the
  same code, unprompted, is a wasted round trip.
- **Address without a code.** `search_cep_by_address` needs a state, a city and at
  least three letters of the street. It returns at most ten candidates; if there
  are more, narrow the street fragment rather than showing an arbitrary ten.
- **State code first.** `list_municipalities_of_state`, and the state filter on
  `census_name_ranking`, want the *numeric* IBGE code (35 for SP, 33 for RJ), not
  the acronym. Call `list_brazil_states` when you only have "SP".
- **Coordinates for weather.** The `location` field of a CEP result carries
  latitude and longitude. Hand those to the `geo-and-weather` skill rather than
  geocoding the address again.

## Reading the results

- **CEP.** `cep` is the code without punctuation; `street` and `neighborhood` may
  be empty for codes that cover a whole municipality. In the fallback provider the
  Portuguese field names are the same data: `logradouro` is the street,
  `bairro` the neighborhood, `localidade` the city, `uf` the state.
- **Municipalities.** The list is capped at 50 names and a trailing `_more` field
  counts the rest. Say "and 595 others" — never imply the 50 are all of them.
- **Census names.** Frequencies are counts of people registered in that decade,
  not percentages, and the periods are written as half-open ranges like
  `[1970,1980[`.
- **Holidays.** `list_national_holidays` covers federal holidays only. State and
  municipal holidays are not in it — say so rather than implying the list is
  complete.
- **Domains.** `status` `REGISTERED` means taken; `AVAILABLE` means free. The
  registry answers for `.br` names only.

## What this skill does not cover

Municipal and state holidays, tax calculations, company financial statements,
electoral or judicial records, and anything about money — interest rates, exchange
rates, banks and vehicle prices live in the `brazil-finance` skill.

## When a tool fails

- **Not found** for a CEP usually means the code does not exist or was mistyped.
  Try `lookup_cep_fallback` once, then ask the user to confirm the eight digits.
  Do not invent a nearby code.
- **Not found** from a search tool means the query matched nothing, not that the
  registry is broken. Suggest a different spelling or a broader term.
- **Rate limited** — answer from what you already have and tell the user the
  registry is busy. Do not retry in the same turn.
- **Unavailable** — BrasilAPI fronts several upstreams and one of them can be down
  while the others answer. `check_br_domain` and `search_ncm` are the two most
  likely to fail this way. Say the source is temporarily unavailable and move on;
  retrying will not help within the same conversation turn.
