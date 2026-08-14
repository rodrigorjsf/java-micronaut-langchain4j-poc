# Como ler uma previsão do tempo

## Unidades

Temperatura em graus Celsius, vento em quilômetros por hora, precipitação em
milímetros. Um milímetro de chuva é um litro por metro quadrado.

## Códigos WMO

O código de tempo (`weather_code`) é um número padronizado pela Organização
Meteorológica Mundial, não um texto. A tradução:

| Código | Significado |
|---|---|
| 0 | céu limpo |
| 1, 2, 3 | parcialmente nublado até encoberto |
| 45, 48 | nevoeiro |
| 51, 53, 55 | garoa |
| 56, 57 | garoa congelante |
| 61, 63, 65 | chuva fraca, moderada, forte |
| 66, 67 | chuva congelante |
| 71, 73, 75 | neve fraca, moderada, forte |
| 77 | grãos de neve |
| 80, 81, 82 | pancadas de chuva |
| 85, 86 | pancadas de neve |
| 95 | trovoada |
| 96, 99 | trovoada com granizo |

## Fuso horário

A previsão vem no fuso do próprio lugar consultado. "Hoje" quer dizer hoje lá,
não hoje no servidor. Isso importa quando a diferença passa da meia-noite.

## Limites

A previsão vai até sete dias. Quanto mais distante o dia, menor a confiança —
uma previsão de sete dias acerta a tendência, não o detalhe. Não há histórico e
não há alerta de tempo severo.
