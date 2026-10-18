# Launcher

Versão visual instalada: `1.1-ptBR`, código 3, pacote `tv.dumbtv.home`. Java nativo, sem Gradle e sem permissões solicitadas. Menus pt-BR selecionados conforme o idioma do aparelho. Paisagem, relógio/data, dock horizontal, foco ampliado e cor ambiente; ordenação, ocultação, nomes e fundos personalizados preservados.

## Compilação e instalação

Requer JDK 17, `ANDROID_HOME`, `JAVA_HOME`, Android SDK com `platforms;android-28` e build-tools, por exemplo 34.0.0.

```sh
./launcher/build.sh
python3 launcher/manage.py install --device '<IP_DA_TV>:5555'
```

O build gera `launcher/build/dumbtv-home.apk` e cria uma chave local caso não exista. Você pode definir `DUMBTV_KEYSTORE` e `DUMBTV_KEYPASS`. A senha padrão de desenvolvimento é pública; proteja sua chave de distribuição e use senha própria. Nunca publique a chave. A chave usada na nossa TV não está neste repositório.

O instalador usa somente o APK local. Guarda as configurações HOME/acessibilidade e tenta copiar as preferências externas se o launcher já estiver instalado. Mantém outros serviços de acessibilidade. Usa `adb install -r`; não desinstala automaticamente se a assinatura for diferente. Para atualizar mantendo dados, use a mesma chave e aumente `VERSION_CODE` em `version.env`.

Quem instalou o APK clássico de retorno, código 4, precisa de uma versão superior a 4 para atualizar normalmente. Esta árvore mantém o código 3 da versão visual instalada.

```sh
python3 launcher/manage.py restore
```

Restaura as configurações HOME anteriores e abre a tela Amazon. Mantém o app e suas preferências; não reverte o código de um APK atualizado. O efeito de vidro usa transparência/Canvas, sem desfoque contínuo. Não inclui recomendações online ou rotação de paisagens. Licenças em `third-party/`, `res/raw/` e `WALLPAPER-CREDITS.md`.
