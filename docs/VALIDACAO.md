# Validação

Referência: Fire TV Stick HD AFTSS/sheldon, Fire OS 7.7.1.7 (PS7717.5741N), Android 9/API 28, armeabi-v7a. Sessão de 18/10/2026, sem identificadores pessoais.

## Conferido no aparelho durante a sessão original

- Launcher 1.1-ptBR instalado e ativo; assinatura verificada.
- Navegação/foco, Opções em português e configurações reais do Fire OS.
- YouCine aberto e retorno por HOME após redesign; YouTube, Prime e Spotify abertos nas etapas anteriores.
- Ordem e preferências preservadas; migração única do fundo preto para paisagem, sem substituir outros fundos personalizados.
- 58 componentes desativados nas duas etapas, além de um pacote já desativado antes. Quantidades variam por aparelho.
- Restrições em 63 pacotes, três casos de UID compartilhado/sistema preservados.
- DNS ativo após reinício: endpoints de atualização, telemetria e Alexa bloqueados; YouTube, loja e imagens Amazon resolveram.
- DNS e lista de pacotes desativados mantidos na troca visual.

Não houve teste completo de reprodução em todos os serviços ou teste em outros modelos.

## Reorganização para este repositório

A lógica foi separada em módulos parametrizados. Testes locais cobrem proteção de apps, UIDs compartilhados, backup exclusivo e restauração de ajustes ausentes. Scripts passam por compilação Python e CLI de ajuda. O launcher foi recompilado desta árvore.

Os novos comandos de aplicação/restauração não foram reaplicados na TV já configurada. Isso evita criar um falso estado original sobre as alterações existentes. Os backups pessoais da sessão ficam fora do repositório.
