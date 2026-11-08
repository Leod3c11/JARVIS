# Segurança e privacidade

1. O app não solicita INTERNET, MICROPHONE nem ACCESSIBILITY. Root é opcional e concedido pelo gerenciador (Magisk/KernelSU), nunca presumido.
2. Nunca executar saída do modelo como shell.
3. Ações privilegiadas devem ser operações nomeadas em allowlist.
4. Validar parâmetros no lado Android, fora do modelo.
5. Mostrar confirmação antes de ações sensíveis.
6. Registrar ação, parâmetros normalizados, horário e resultado localmente.
7. Disponibilizar cancelamento e mecanismo de recuperação quando possível.
8. Wake word deve ser opt-in, com indicador visível e opção de desligar.

## Root Broker (0.5)

- Allowlist em `PrivilegedAction`; o texto do usuário só escolhe uma operação via `IntentRouter` (determinístico).
- Comandos de shell são montados apenas com constantes e valores validados (inteiros dentro da faixa do hardware,
  governors anunciados em `scaling_available_governors`). Leitura restrita a `cpufreq` e `thermal`.
- Antes da primeira alteração o estado da CPU é salvo; “restaurar estado original” o reaplica.
- O boost durante respostas guarda o estado anterior e o restaura ao fim; se o app for encerrado no meio,
  a restauração ocorre na próxima abertura.
- Perfil Desempenho é recusado com estado térmico ≥ moderado ou bateria < 15%.
- Cada operação grava ação, resultado e horário em LOGS (sem conteúdo de conversa).
- Após gravar no kernel, o valor é relido; divergências são reportadas em vez de presumir sucesso.

