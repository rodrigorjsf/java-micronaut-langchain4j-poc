---
name: countries-and-economy
description: Country facts and world economics — capital, population, currencies and region for any country, World Bank indicators like GDP, inflation and life expectancy, and exchange rates between world currencies, now or in the past. Use for cross-country and currency questions.
---

# Countries and economy

Eight tools over three sources. Which source answers depends on the shape of the
question: **REST Countries** answers "what is this country", the **World Bank**
answers "how has this number moved", and the **exchange-rate** tools answer "what
is this worth somewhere else".

## Choosing a tool

| The user gives you | Use |
|---|---|
| a country name, and wants a fact about it | `get_country_by_name` |
| a 2- or 3-letter country code you already hold | `get_country_by_code` |
| a country and a measure over time (GDP, inflation, life expectancy) | `get_worldbank_indicator` |
| a country, and asks about its region or income group | `get_worldbank_country_meta` |
| an amount and two currencies, today | `convert_currency` |
| two currencies and a past date | `get_historical_fx_rate` |
| a currency you have no code for, or one that was rejected | `list_supported_currencies` |
| a currency the ECB does not publish (ARS, CLP, COP, NGN, VND…) | `get_fx_rates_broad` |

## How to combine them

- **Comparing two countries.** Call `get_worldbank_indicator` once per country
  with the *same* indicator code, then compare. Never compare two different
  indicators and present it as one measure.
- **Name to code.** `get_country_by_name` returns the ISO codes; feed them to the
  World Bank tools rather than guessing that Switzerland is `SWI` (it is `CHE`).
- **Converting money.** `convert_currency` returns the rate for **one unit** of
  the base currency. Multiply the user's amount yourself, show the arithmetic if
  the amount is large, and always quote the `date` the rate carries.
- **Then and now.** `get_historical_fx_rate` for the old date plus
  `convert_currency` for today gives you both ends of a "how much has the dollar
  moved" question in two calls.
- **Fallback order for currencies.** Try `convert_currency` first. Only if the
  currency is missing from the answer, reach for `get_fx_rates_broad`, and tell
  the user that rate is a daily approximation rather than an ECB fixing.

## Indicator codes worth knowing

`get_worldbank_indicator` needs a code, not a description. The common ones:

| Question | Code |
|---|---|
| GDP, current US$ | `NY.GDP.MKTP.CD` |
| GDP per capita, current US$ | `NY.GDP.PCAP.CD` |
| GDP growth, annual % | `NY.GDP.MKTP.KD.ZG` |
| Inflation, consumer prices, annual % | `FP.CPI.TOTL.ZG` |
| Population, total | `SP.POP.TOTL` |
| Life expectancy at birth, years | `SP.DYN.LE00.IN` |
| Unemployment, % of labour force | `SL.UEM.TOTL.ZS` |
| Gini index | `SI.POV.GINI` |

If the user asks for a measure that is not on this list, ask which of these comes
closest instead of inventing a code — a wrong code comes back as an
`Invalid value` message rather than as an error, and it is easy to mistake for
"no data".

## Reading the results

- **World Bank.** The payload is a pair: paging information first, then the rows.
  Each row carries a `date` (the year) and a `value`, newest first, and `value`
  can be `null` for years the country did not report. A `null` is "not reported",
  never zero. Quote the year with the number — a GDP figure without its year is
  useless.
- **Countries.** `population` is a recent estimate, not a live count. A partial
  name match can return several countries with a `_more` marker; if more than one
  came back, say which one you answered about.
- **Rates.** Both rate tools quote a date or an update time. Reference rates are
  published on business days only, so a weekend request answers with the previous
  fixing — repeat the date the tool returned rather than the date the user asked
  for.

## What this skill does not do

No stock prices, no crypto, no company financials, no tax or customs rates, and
no forecasts of any kind — these are historical and current observations. It also
does not cover Brazilian domestic series such as SELIC, CDI or IPCA; for a
Brazilian address, company or holiday, use the `brazil-civic-data` skill.
Converting **into** BRL is fine — it is an ordinary world currency here.

## When a tool fails

- **Invalid arguments** names what was wrong. Country codes and indicator codes
  are the usual cause; fix and retry once, or call `get_country_by_name` to get
  the correct code first.
- **Not found** means the country or the date has no record. Report it; do not
  substitute a neighbouring year or a similar country without saying so.
- **Rate limited or unavailable** — answer from what you already have and say the
  source is temporarily unavailable. Do not retry in the same turn, and do not
  quote a rate from memory: an exchange rate you did not just fetch is a guess.
