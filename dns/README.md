# DNS local

Instala personalDNSfilter 1.50.61.3 do F-Droid com SHA-256 verificado:

```text
3ddd0db322d1ca5fe950ea29dc1788af6e6581bb5f34694085fb4aa2e77b68ad
```

```sh
python3 dns/manage.py install --block-updates
# A TV reinicia. Aguarde e reconecte.
adb connect "$ADB_SERIAL"
python3 dns/manage.py verify
python3 dns/manage.py restore
```

Este perfil inclui bloqueio de OTA e exige `--block-updates`. Não oferece perfil que garanta preservar atualizações. Se quiser mantê-las, não instale este módulo sem revisar as listas.

VPN local sempre ativa, usando o gateway atual da rede para consultas permitidas. Ocupa a única vaga de VPN do Android. O instalador recusa substituir outra VPN ou instalação do filtro. Se mudar de rede/gateway, revise a configuração.

Fontes: HaGeZi Amazon e `data/extra-hosts.txt`. Exclui `aiv-delivery` e `aiv-cdn` para preservar vídeo do Prime. Downloads HTTPS; apenas o APK tem hash fixo. A lista e o modelo de configuração são obtidos das fontes de origem e podem mudar. Mudança incompatível de formato interrompe a preparação.

A lista final é local, sem atualização automática na TV. O script grava `FILTERHOSTS.TXT.DLD_CNT`, deixa `previousAutoUpdateURL` e `filterAutoUpdateURL` vazios e invalida o índice anterior. Sem esses metadados, observamos o filtro substituir a lista manual por arquivo vazio no boot.

Não use `am force-stop dnsfilter.android` após ativar: pode interromper DNS até reiniciar. O script usa isso somente na preparação inicial, seguida de reboot. Se falhar depois de instalar o app, use restore para remover esta instalação e reiniciar.

Restore usa `.local/dns/backup.json`, restaura o indicador de VPN anterior, remove as exceções adicionadas e desinstala o filtro instalado pela execução. Launcher e debloat permanecem.

Verify usa a resolução indicada pelo ping; não exige resposta ICMP. Também testa domínios permitidos para detectar falta de rede. Não substitui testar reprodução nos apps.
