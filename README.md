# TermoVigia

App Android que monitora o celular em segundo plano para descobrir **o que está fazendo ele esquentar**.

- Mede temperatura da bateria, estado térmico do Android, consumo (W), velocidade do processador, brilho, rede/sinal e qual app está na tela.
- Salva o histórico (gráfico de 1h a 3 dias) e aponta os **apps que mais esquentam**.
- Diagnóstico automático: calor carregando, gasto com tela desligada, sinal fraco, sistema limitando desempenho etc.
- Alertas por notificação quando passa do limite escolhido.
- Liga sozinho quando o celular reinicia.

## Baixar
Cada envio para a branch `main` gera um APK novo em **Releases** (GitHub Actions).

## Permissões no Poco / HyperOS
1. Acesso ao uso
2. Notificações
3. Bateria: "Sem restrições"
4. Início automático
5. Travar o app nos recentes (cadeado)
