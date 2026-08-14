---
name: crypto-and-markets
description: Cryptocurrency prices and market data — spot quotes in BRL, USD or any currency, the top coins by market capitalisation, and 24-hour trading statistics from a major exchange. Use when the user asks what a coin is worth, how the crypto market is doing, or about a trading pair.
---

# Crypto and markets

Four tools over two sources. CoinGecko aggregates across exchanges and answers in
any currency; Binance is one exchange and answers in its own trading pairs.

## Choosing a tool

| The user asks | Use |
|---|---|
| "quanto está o bitcoin?" — a price, in reais or dollars | `get_crypto_price` |
| "quais as maiores criptos?" — an overview or a ranking | `get_crypto_market_ranking` |
| a specific trading pair, or 24-hour volume, high and low | `get_binance_ticker` |
| just the number for a pair, as fast as possible | `get_binance_price` |

Prefer CoinGecko for anything a normal person asks. Reach for Binance when the
question is already in exchange terms — "BTCUSDT", "the pair", "volume today".

## Always say when the price was taken

**A quote with no timestamp reads as a fact and is a lie within seconds.** Crypto
moves continuously; a number the user reads two minutes later is already wrong.
Say "no momento desta consulta" or give the time, every time. Never present a
cached or remembered price as current.

Coin ids are CoinGecko's, not tickers: `bitcoin`, `ethereum`, `solana` — not BTC,
ETH, SOL. If the user gives a ticker, map it to the id before calling. When you
are not sure of the id, `get_crypto_market_ranking` lists the top coins with
their ids.

## When a tool fails

- **Rate limited** — CoinGecko's free tier throttles aggressively and answers 429
  under load. Say the price service is busy and do not retry in the same turn.
- **Unavailable from Binance** — the exchange refuses requests from some regions
  outright. Fall back to `get_crypto_price`, which is not exchange-hosted, and do
  not explain the geography to the user.
- **No result for a coin id** — the id was probably a ticker. Look it up in the
  ranking rather than guessing another spelling.

## What this skill does not do

No trading, no portfolio tracking, no price history or charts, and no advice.
A question about whether to buy or sell is outside what this assistant does —
say so plainly rather than hedging into a recommendation.
