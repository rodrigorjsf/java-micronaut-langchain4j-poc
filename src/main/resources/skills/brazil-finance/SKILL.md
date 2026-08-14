---
name: brazil-finance
description: Brazilian money data — SELIC, CDI and IPCA rates, IBGE indicators, Banco Central time series, official PTAX and closing exchange rates, live BRL quotes, banks by COMPE code, and FIPE used-vehicle prices. Use when a question involves Brazilian interest rates, inflation, the dollar in reais, a bank number or car prices.
---

# Brazilian finance

Twelve tools over Banco Central, IBGE, BrasilAPI and a live quote feed. These
numbers move daily and some of them are legally cited, so always answer from a
tool result — never from memory, and never by interpolating between two dates.

## Choosing a tool

| The user asks about | Use |
|---|---|
| today's SELIC, CDI or inflation | `get_brazil_interest_rates` |
| a rate's history, or its value in a past month | `get_bcb_time_series` |
| an official IBGE indicator such as the monthly IPCA | `get_ibge_indicator` |
| which variable id an IBGE aggregate holds | `describe_ibge_aggregate` |
| "how much is the dollar right now" | `get_fx_quote_brl` |
| how a currency moved over the last days or weeks | `get_fx_history_brl` |
| the official rate for a currency on a given date | `get_bcb_fx_rate` |
| the PTAX dollar, or "the official dollar" for a contract or tax filing | `get_ptax_usd` |
| whether a currency is quoted at all | `list_bcb_currencies` |
| a bank number on a boleto or transfer, e.g. 001 or 341 | `lookup_bank` |
| what a used car, motorcycle or truck is worth | `list_fipe_brands`, then `list_fipe_models` |

## Official rate or market price — pick deliberately

This is the distinction that decides most of these calls, and getting it wrong
gives a number that is right-looking and unusable.

- **Market price.** `get_fx_quote_brl` and `get_fx_history_brl` are live trading
  quotes. Use them for "how much is the dollar today", travel money, and trends.
- **Official rate.** `get_bcb_fx_rate` and `get_ptax_usd` are what Banco Central
  published for that day. Use them whenever the answer feeds a contract, an
  invoice, a tax filing, accounting, or any sentence with the word *official*.
  `get_ptax_usd` is the dollar specifically; `get_bcb_fx_rate` covers other
  currencies and takes a date.

When the user does not make it clear, say which one you used.

## How to combine them

- **FIPE is a two-step lookup.** `list_fipe_brands` returns each make with a
  numeric code in its `valor` field; `list_fipe_models` needs that code, not the
  brand name. Keep the same vehicle type across both calls.
- **IBGE indicators need ids.** `get_ibge_indicator` takes an aggregate id and a
  variable id. Aggregate 7060 / variable 63 is the monthly IPCA variation; for
  anything else call `describe_ibge_aggregate` first rather than guessing an id.
- **Rate now versus rate then.** `get_brazil_interest_rates` answers "what is the
  SELIC" in one call. Only reach for `get_bcb_time_series` when the question has a
  time dimension — a trend, a past month, a comparison.
- **Currency codes.** `list_bcb_currencies` is a sanity check before
  `get_bcb_fx_rate`, not an answer in itself. Do not list currencies at a user.

## Reading the results

- **Rates** come back as plain numbers in percent per year: `{"nome":"Selic","valor":14}`
  means 14% a year. Say the unit; a bare "14" is meaningless.
- **`get_bcb_fx_rate`** returns several bulletins for one day inside `cotacoes`.
  The last entry is the closing quote — quote that one, and say it is the close.
  `cotacao_compra` is the buy side, `cotacao_venda` the sell side.
- **`get_ptax_usd`** returns `cotacaoCompra` and `cotacaoVenda` for the day.
- **FIPE lists** are capped — 40 brands, 25 models, with a trailing `_more` count.
  Report the count instead of implying the list is complete.
- **Time series** dates are `DD/MM/YYYY` and values are strings; read them as
  Brazilian dates, not US ones.

## What this skill does not cover

Stocks, funds, B3 tickers, crypto, bank account operations, PIX transfers or
participant lookups, brokerage registries, credit scores, tax calculations, and
the FIPE price of one specific vehicle — this skill gets you as far as the model
list. Say so plainly rather than approximating.

## When a tool fails

- **"The market was closed"** is a real answer, not an error. Banco Central
  publishes nothing on weekends and national holidays. Ask about the previous
  business day; never average two dates into an invented rate.
- **Not found** on a bank code or a FIPE brand means the code does not exist.
  Ask the user to confirm it rather than picking the nearest one.
- **Rate limited** — answer from what you already have and say the source is busy.
  Do not retry in the same turn.
- **Unavailable** — these are free public sources and one can be down while the
  rest answer. Tell the user which figure you could not get, and give the ones you
  did get rather than dropping the whole answer.
