# LimpaDroid
[![DOI](https://zenodo.org/badge/DOI/10.5281/zenodo.24418797.svg)](https://doi.org/10.5281/zenodo.24418797)

Aplicativo Android pessoal (Android 11 a Android 17 — `minSdk = 30`, `targetSdk = 37`) para análise e limpeza controlada do armazenamento interno compartilhado primário, 100% offline (sem permissão `INTERNET`) e com lixeira interna própria com retenção mínima de 30 dias.

## Licença

[![CC BY-NC 4.0](https://img.shields.io/badge/License-CC_BY--NC_4.0-lightgrey.svg)](https://creativecommons.org/licenses/by-nc/4.0/deed.pt-br)

Copyright © 2026 **Ricardo Coutinho Mello**. Distribuído sob a licença [Creative Commons Atribuição-NãoComercial 4.0 Internacional (CC BY-NC 4.0)](https://creativecommons.org/licenses/by-nc/4.0/deed.pt-br). Consulte o arquivo [LICENSE](LICENSE) para detalhes.

## Estado atual

- **Fase 0 (Base e infraestrutura) concluída:** estrutura Gradle (`minSdk = 30`, `compileSdk = 37`, `targetSdk = 37`, bytecode alvo `17`, Gradle Wrapper `9.3.1`), tema Material 3 claro/escuro com cores dinâmicas em API 31+, navegação type-safe, onboarding de permissões (`MANAGE_EXTERNAL_STORAGE` obrigatória e `POST_NOTIFICATIONS` opcional em API 33+), resolução dinâmica da raiz via `StorageManager`/`StorageVolume`, tela inicial responsiva, modo diagnóstico acionado por toque longo na versão (com cópia de relatório) e pipeline de CI (`.github/workflows/ci.yml`).
- **Fase 1 (Infraestrutura de varredura) concluída:** `PathGuard` com normalização e bloqueio de links simbólicos/`Android/data`/`Android/obb`/`.lixeira`, `FileWalker` iterativo (`Files.walkFileTree` sem seguir links simbólicos, contrapressão limitada e registro de erros por arquivo sem interromper a varredura), `Hasher` SHA-256 (buffer 1 MiB e hash parcial 64 KiB), contratos de classificação (`CandidateClassifier` e `ClassificationPolicy`), persistência Room via KSP (`scan_sessions`, `scan_candidates`, `scan_errors`, `hash_cache`), log rotativo local (`LocalScanLogger`), execução em primeiro plano com `WorkManager` (`ScanWorker` tipo `dataSync`), pausa, cancelamento, retomada por checkpoint exato e tela de detalhes da varredura.
- **Fase 2 (Núcleo da Lixeira) concluída:** lixeira unificada em `Documents/LimpaDroid/.lixeira` (com `.nomedia` e `metadata.json` por item), movimentação segura (`ATOMIC_MOVE` com fallback de cópia + verificação SHA-256), diário `PENDING -> IN_TRASH` com reconciliação pós-falha e reconstrução de índice após reinstalação, retenção mínima de 30 dias com `PurgeWorker` diário (`WorkManager`), atualização de `MediaStore` via `MediaScannerConnection.scanFile`, telas **Lixeira** (com contabilidade separada "Em lixeira" vs "Espaço efetivamente liberado" e confirmações) e **Histórico**, além de migração Room `MIGRATION_1_2`.
- **Fase 3 (Dados supérfluos) concluída:** motor `SuperfluousScanEngine` com as 7 regras isoladas e testadas da Seção 7.2 (`THUMBS`, `PARTIAL_DL`, `LOST_DIR`, `EMPTY_DIR`, `ZERO_BYTE`, `OLD_APK`, `STALE_MEDIASTORE` reparado via `MediaScannerConnection.scanFile` sem passar pela lixeira), cartão **Dados supérfluos** com métricas ao vivo na tela **Início**, tela genérica `CategoryResultsScreen` agrupada por regra (com pré-visualização de miniaturas, invariante de segurança marcando apenas confiança `HIGH`, seleção por grupo/item e confirmação de 30 dias) e tela `SettingsScreen` para ativação individual de regras e lista persistente de caminhos protegidos (exceções).
- **Fase 4 (Possíveis dados remanescentes de aplicativos removidos) concluída:** permissão `QUERY_ALL_PACKAGES` justificada na Seção 12.6 (`DECISOES.md`), registro histórico `known_apps` em Room v3 (`MIGRATION_2_3`), `AndroidInstalledAppsRepository` consultando `MATCH_UNINSTALLED_PACKAGES` e `MATCH_ARCHIVED_PACKAGES` com proteção explícita para aplicativos arquivados (`isArchived`), motor `OrphanScanEngine` com as 3 regras da Seção 7.3 (`ORPHAN_MEDIA_PKG` em confiança `HIGH`, `ORPHAN_ROOT_KNOWN` em `MEDIUM` e `ORPHAN_ROOT_PATTERN` em `LOW`), proteção de diretórios padrão do sistema/WhatsApp/`Documents/LimpaDroid` e exibição de nome do aplicativo removido e data de remoção nos resultados.
- **Fase 5 (Arquivos duplicados) concluída:** pipeline em 4 estágios em `DuplicateScanEngine` (agrupamento por tamanho exato, hash parcial SHA-256 de 64 KiB iniciais/finais + tamanho com paralelismo limitado a 4, hash completo SHA-256 com reaproveitamento via tabela Room `hash_cache` e revalidação imediata de tamanho/`mtime`/SHA-256 antes da movimentação), política `DuplicateKeepPolicy` com preservação automática por pastas de prioridade configuráveis, desempate por `lastModified` e caminho lexicográfico, selo **"Sem pasta de prioridade"** (`Confidence.MEDIUM`), troca manual da cópia preservada por grupo, invariante de domínio/repositório/lixeira garantindo ao menos uma cópia fora da lixeira, configurações de tamanho mínimo e pastas de prioridade em `DataStore` e migração Room `MIGRATION_3_4` (`v4`).
- **Fase 6 (Mídia do WhatsApp e acabamento) concluída:** motor `WhatsAppScanEngine` para WhatsApp e WhatsApp Business (raízes modernas em `Android/media` e caminhos legados deduplicados por caminho canônico, incluindo subpastas `Sent` e semanais de `Voice Notes`, com bloqueio estrito de `.Statuses`, `Backups`, `Databases`, `WhatsApp Documents`, `WhatsApp Stickers` e pastas ocultas), filtro configurável de idade (`7` a `730` dias, padrão `90`) e tipos de mídia (`Animated Gifs` opcional desativado por padrão), primeira execução desmarcada mesmo em confiança `HIGH` com opção persistida em `DataStore`, avisos obrigatórios da Seção 7.5 na tela de resultados e diálogo de confirmação, além de revisão de acessibilidade (TalkBack, cabeçalhos semânticos, contraste WCAG e alvos mínimos de `48.dp`).
- **Fase 7 (Auditoria final e lançamento `v1.0.0`) concluída:** regras de R8/ProGuard em `app/proguard-rules.pro` (preservando rotas serializáveis e `TrashMetadataPayload`, gerando APK release minificado de `3,7 MiB`), fallback padrão de versão atualizado para `1.0.0` em `app/build.gradle.kts`, deduplicação de concorrência por ramo em `.github/workflows/ci.yml` e workflow `.github/workflows/release.yml` para tags `v*` com validação obrigatória dos 4 GitHub Secrets, normalização segura do alias em cópia temporária, assinatura release, verificação automática via `apksigner verify`, geração de checksum `.sha256` e publicação automática em GitHub Releases.

## Escopo e limitações da versão 1

- **Suportado:** armazenamento interno compartilhado primário e `Android/media`.
- **Fora do escopo (v1):** cartão SD, unidades USB/OTG, `Android/data` e dados privados internos de outros aplicativos.
- **Lixeira própria:** localizada em `Documents/LimpaDroid/.lixeira` (com `.nomedia`, `metadata.json` por item e índice Room v4 com `MIGRATION_1_2`, `MIGRATION_2_3` e `MIGRATION_3_4` para recuperação inclusive após reinstalação). Desinstalar o aplicativo remove arquivos internos do app, portanto mantenha cópia de segurança do keystore para atualizar sobre a versão instalada sem precisar desinstalar.

## Uso básico

1. **Onboarding:** conceda `Acesso a todos os arquivos` (`MANAGE_EXTERNAL_STORAGE`) e escolha se deseja ativar notificações de progresso em segundo plano.
2. **Varredura e Limpeza total:** na tela **Início**, toque em **Varredura** para analisar de uma só vez todas as categorias (**Dados supérfluos**, **Possíveis dados remanescentes de aplicativos removidos**, **Arquivos duplicados** e **Mídia do WhatsApp**). Em seguida, toque em **Limpeza total** para confirmar a movimentação em lote dos itens seguros pré-selecionados para a lixeira de 30 dias.
3. **Revisão por categoria e Lixeira:** toque em qualquer linha da seção **Categorias identificadas** para inspecionar detalhes, ajustar seleções manuais (`LOW`/`MEDIUM` e primeira execução do WhatsApp vêm desmarcados por segurança) ou trocar a cópia preservada em grupos de duplicados.
4. **Configurações, Histórico e Diagnóstico:** ajuste regras, exceções protegidas, pastas de prioridade de duplicados e filtros de idade/mídia do WhatsApp pelo ícone de engrenagem; acompanhe auditoria em **Histórico** e abra o **Modo Diagnóstico** mantendo pressionada a versão no rodapé da tela **Início**.

## Compilação local

Requisitos: JDK 21 em tempo de compilação/testes (exigido pelo Robolectric 4.18 na API 37, gerando bytecode alvo Java 17), Android SDK Platform 37 (`platforms;android-37.0` via canal Canary) e Gradle Wrapper (`9.3.1`). Caso `debug.keystore` não exista na raiz do repositório clonado, gere a chave de depuração localmente com `keytool` antes da compilação:

```bash
keytool -genkeypair -v \
  -keystore debug.keystore \
  -storepass android \
  -alias androiddebugkey \
  -keypass android \
  -dname "CN=Android Debug,O=Android,C=US" \
  -keyalg RSA \
  -keysize 2048 \
  -validity 10000

./gradlew lint testDebugUnitTest assembleDebug
```

## Assinatura de lançamento, GitHub Secrets e publicação (`v1.0.0`)

1. **Gerar keystore de lançamento (uma única vez, fora do Git):**
   ```bash
   keytool -genkeypair -v \
     -keystore limpadroid-release.jks \
     -alias upload \
     -keyalg RSA \
     -keysize 2048 \
     -validity 10000
   ```
   Guarde `limpadroid-release.jks` e as senhas em cópia de segurança segura (cofre de senhas). Nunca versionar arquivos `.jks` ou `.keystore`. Sem a mesma chave, não será possível atualizar o APK diretamente sem desinstalar o aplicativo (o que apagaria o índice local da lixeira).

2. **Codificar em Base64 (linha única) e cadastrar no GitHub:**
   ```bash
   base64 -w0 limpadroid-release.jks
   ```
   Em **Settings -> Secrets and variables -> Actions** no repositório do GitHub, configure:
   - `RELEASE_KEYSTORE_BASE64`: saída Base64 em linha única do arquivo `.jks`.
   - `KEY_ALIAS`: alias da chave (recomendado `upload`; caso use outro alias, `release.yml` normaliza a cópia temporária durante o build).
   - `STORE_PASSWORD`: senha do keystore.
   - `KEY_PASSWORD`: senha da chave privada.

3. **Publicar versão:**
   ```bash
   git tag -a v1.0.0 -m "chore(release): v1.0.0"
   git push origin v1.0.0
   ```
   O workflow `.github/workflows/release.yml` executa `lint`, `testDebugUnitTest`, `assembleRelease`, valida a assinatura com `apksigner verify`, calcula `sha256sum` e publica `limpadroid-1.0.0-release.apk` e `.sha256` em **GitHub Releases**.

## Instalação e atualização no dispositivo

1. Baixe `limpadroid-<versão>-release.apk` na aba **Releases** do repositório GitHub.
2. No aparelho, autorize a instalação de aplicativos desconhecidos para o navegador ou gerenciador de arquivos utilizado.
3. Confirme eventual aviso do Google Play Protect para aplicativo pessoal não listado na loja.
4. Para futuras atualizações, baixe o novo APK da nova release e instale diretamente sobre o existente (sem desinstalar), preservando configurações, histórico e lixeira.

## Licença

Copyright (c) 2026 **Ricardo Coutinho Mello**.

Este projeto é distribuído sob a licença **Creative Commons Attribution-NonCommercial 4.0 International (CC BY-NC 4.0)** — consulte o arquivo `LICENSE` na raiz do repositório. As fontes tipográficas embutidas permanecem sob a **SIL Open Font License 1.1** (`licenses/OFL.txt`).

## Documentação complementar

- `LICENSE` — licença Creative Commons CC BY-NC 4.0 em nome de Ricardo Coutinho Mello.
- `DECISOES.md` — verificações técnicas, divergências e decisões definitivas registradas.
- `docs/AVALIACOES.md` — registro das auditorias independentes da Seção 14 por fase.
- `docs/ROTEIRO_TESTE.md` — roteiro de validação manual em dispositivo físico.
- `licenses/OFL.txt` — licença SIL Open Font License 1.1 para as fontes embutidas.
