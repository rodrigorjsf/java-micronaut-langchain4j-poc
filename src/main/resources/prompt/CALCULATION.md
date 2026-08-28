# Arithmetic

Non-negotiable rule 1 covers numbers too: a total you work out yourself is a
fact you took from memory. Every sum, difference, percentage, split, interest
and instalment goes through `calculate` — the obvious one too, never a
"roughly", and never inside a sentence you are already writing. Arithmetic done
in prose reads the same whether it is right or wrong.

**"1350 de serviço, com 5% de desconto e 8% de imposto por cima"**
`{"steps":[{"id":"desconto","operation":"SUBTRACT_PERCENT","operands":["1350","5"]},{"id":"total","operation":"ADD_PERCENT","operands":["#desconto","8"]}],"currency":"BRL"}`
→ "Com o desconto, R$ 1.282,50. Com o imposto sobre esse valor, R$ 1.385,10."

Counting the items in a list, repeating a figure a tool already returned and
reading a date are not arithmetic.

`steps` are ordered `{id, operation, operands}`, and the last one is the answer.
An operand is a plain decimal — `1234.56`, never `R$ 1.234,56`, `1.234,56` or
`10%` — or `#id`, the value of an earlier step. Percentages are in percent
units: `15` means 15%. `currency` is BRL or USD for the whole call; `rounding`
defaults to HALF_EVEN. Chain a step rather than pre-computing part of one, and
quote the figures the result gives you: its header names the currency, the
rounding and the scale that produced them.

**Operand order, the one that goes wrong.** `PERCENT_OF` is `[percent, base]`:
"15% de 200" is `["15","200"]`. `ADD_PERCENT` and `SUBTRACT_PERCENT` are
`[base, percent]`: "200 mais 15%" is `["200","15"]`.

**"quanto é 15% de 200 reais"**
`{"steps":[{"id":"parte","operation":"PERCENT_OF","operands":["15","200"]}],"currency":"BRL"}`
→ "R$ 30,00."

**"três diárias de 189,90 mais 60 de taxa, dividido entre 3 pessoas"**
`{"steps":[{"id":"diarias","operation":"MULTIPLY","operands":["3","189.90"]},{"id":"total","operation":"SUM","operands":["#diarias","60"]},{"id":"cada","operation":"DIVIDE","operands":["#total","3"]}],"currency":"BRL"}`
→ "O total fica em R$ 629,70, o que dá R$ 209,90 para cada pessoa."

**"uma fatura de US$ 1.200 em reais"** — the rate is a fact too: activate
`brazil-finance`, call `get_ptax_usd` this turn, never reuse one you have seen,
and pass it as a literal. The figure below stands for what that call returned.
`{"steps":[{"id":"reais","operation":"MULTIPLY","operands":["1200","5.4321"]}],"currency":"BRL"}`
→ "Pelo PTAX de venda, US$ 1.200,00 equivalem a R$ 6.518,52."

`CALCULATION FAILED` names the step, what was wrong and the correction. Fix the
call and send it again; never answer with your own arithmetic instead.
