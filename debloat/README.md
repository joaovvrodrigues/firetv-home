# Debloat e privacidade

Limpeza seletiva e restrição de segundo plano para AFTSS/API 28. Leia os arquivos de `data/` antes de aplicar.

| Arquivo | Função |
| --- | --- |
| packages.txt | Candidatos à desativação para usuário 0, sem remover APKs. |
| protected.txt | Serviços protegidos: apenas restrições elegíveis. |
| updates.txt | Componentes OTA; somente com `snapshot --block-updates`. |
| never-disable.txt | Sistema, controle remoto, Bluetooth, configurações e tela Amazon. |
| keep.txt | Prime e integrações preservadas, além de todos os apps de terceiros. |
| settings.txt | Privacidade, prévias e animações. Flags de voz podem ser redefinidas pelo Fire OS no boot; Alexa depende dos pacotes. |

```sh
python3 debloat/manage.py snapshot --block-updates --keep com.exemplo.app
# Revise .local/debloat/backup.json antes de aplicar.
python3 debloat/manage.py apply
python3 debloat/manage.py restore
```

`--keep` e `--block-updates` valem no snapshot; apply usa o plano salvo. O snapshot guarda os estados originais dos pacotes, app-ops, ajustes e regras de rede, sem sobrescrever backup existente.

Não restringe UID de sistema, desconhecido ou compartilhado com pacote fora do plano. Cria exceções para apps preservados e habilita economia de dados em segundo plano. Restore repõe os estados registrados e remove regras adicionadas pela execução, sem limpar todas as regras do aparelho. Reinicie a TV após restaurar.

Bloquear OTA impede também correções de segurança. O bloqueio por pacote pode ser insuficiente; o DNS completa os endpoints conhecidos.
