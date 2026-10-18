# Recuperação

Guarde `.local/` e a chave do launcher fora do Git. Backups desta versão organizada têm formato próprio; não coloque backups antigos de outro formato nos scripts.

| Situação | Ação |
| --- | --- |
| Voltar à tela Amazon | `python3 launcher/manage.py restore` com seu backup original. |
| App falhou após debloat | `python3 debloat/manage.py restore`, reinicie e revise o plano. |
| DNS falhou | Reinicie; se persistir, `python3 dns/manage.py restore`. |
| ADB unauthorized | Aceite o pedido na TV. |
| Assinatura incompatível | Use a chave original. Não desinstale sem guardar preferências. |
| Reaplicar após restauração | Arquive a pasta de estado antes de criar outro plano. |

HOME pode voltar à Amazon após atualização: revise acessibilidade e reinstale com a mesma chave. Não desative a tela Amazon para tentar corrigir.

Não desative `com.amazon.vizzini`: pode causar aviso persistente de controle remoto. Não use root, remount, alterações em `/system` ou reset de fábrica.

Restore repõe configurações do backup e pode substituir mudanças manuais posteriores nas mesmas chaves. Não é uma imagem completa do sistema nem recupera dados apagados por outros meios. Mantenha o backup mesmo após restaurar.
